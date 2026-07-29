package kr.artel.orchestration.qa.service

import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.qa.dto.QaLogPageResponse
import kr.artel.orchestration.qa.dto.QaLogResponse
import kr.artel.orchestration.qa.dto.QaStatusPayload
import kr.artel.orchestration.qa.dto.QaTryResponse
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testscenario.service.TestScenarioAccessService
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class QaTryPersistenceService(
    private val tryRepository: QaTryRepository,
    private val logService: QaLogService,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    suspend fun createStarting(
        testScenarioId: Long,
        gameInstanceId: Long,
        startedBy: Long
    ): Pair<QaTryEntity, QaLogAppendResult> =
        transactionalOperator.executeAndAwait {
            val now = Instant.now(clock)
            val qaTry = tryRepository.save(
                QaTryEntity(
                    testScenarioId = testScenarioId,
                    gameInstanceId = gameInstanceId,
                    startedBy = startedBy,
                    status = "STARTING",
                    startedAt = now
                )
            )
            val startLog = logService.append(
                qaTryId = requireNotNull(qaTry.id),
                direction = "ORCHE_INTERNAL",
                type = "STATUS",
                message = "QA execution is starting.",
                payload = objectMapper.valueToTree(QaStatusPayload("STARTING", null))
            )
            qaTry to startLog
        } ?: error("QA try creation returned no result")

    suspend fun attachAndMarkRunning(
        qaTry: QaTryEntity,
        agentSessionId: String
    ): Pair<QaTryEntity, QaLogAppendResult> =
        transactionalOperator.executeAndAwait {
            val now = Instant.now(clock)
            val id = requireNotNull(qaTry.id)
            if (tryRepository.attachAgentSession(id, agentSessionId, now) != 1) {
                throw IllegalStateException("QA try cannot attach an Agent session")
            }
            if (tryRepository.transition(id, "STARTING", "RUNNING", null, now) != 1) {
                throw IllegalStateException("QA try is no longer STARTING")
            }
            val running = requireNotNull(tryRepository.findById(id))
            val runningLog = logService.append(
                qaTryId = requireNotNull(running.id),
                direction = "ORCHE_INTERNAL",
                type = "STATUS",
                message = "QA execution is running.",
                payload = objectMapper.valueToTree(QaStatusPayload("RUNNING", null))
            )
            running to runningLog
        } ?: error("QA try transition returned no result")
}

@Service
class QaTryService(
    private val tryRepository: QaTryRepository,
    private val scenarioAccessService: TestScenarioAccessService,
    private val instanceRepository: GameInstanceRepository,
    private val sessionManager: SessionManager,
    private val agentPort: QaAgentPort,
    private val inboundRouter: QaAgentInboundRouter,
    private val failureService: QaExecutionFailureService,
    private val persistence: QaTryPersistenceService,
    private val logService: QaLogService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    suspend fun create(testScenarioId: Long, gameInstanceId: Long, userId: Long): QaTryResponse {
        val scenario = scenarioAccessService.accessibleScenario(testScenarioId, userId)
            ?: throw NotFoundException()
        val instance = instanceRepository.findAccessibleByIdForMember(gameInstanceId, userId)
            ?: throw NotFoundException()
        if (scenario.projectId != instance.projectId) {
            throw NotFoundException()
        }
        if (!sessionManager.hasSession(gameInstanceId.toString())) {
            throw ConflictException("Game instance SDK is not connected")
        }
        if (tryRepository.findActiveByGameInstanceId(gameInstanceId) != null) {
            throw ConflictException("An active QA try already exists")
        }

        val (starting, startLog) = persistence.createStarting(testScenarioId, gameInstanceId, userId)
        logService.publish(startLog)
        val qaTryId = requireNotNull(starting.id)
        val context = QaAgentSessionContext(
            qaTryId = qaTryId.toString(),
            gameInstanceId = gameInstanceId.toString(),
            testScenarioId = testScenarioId.toString(),
            scenario = objectMapper.readTree(scenario.payload.asString())
        )

        return try {
            val agent = agentPort.createSession(
                context = context,
                onMessage = { envelope -> inboundRouter.handle(envelope) },
                onDisconnect = { failureService.agentDisconnected(qaTryId) }
            )
            val (running, runningLog) = persistence.attachAndMarkRunning(starting, agent.sessionId)
            logService.publish(runningLog)
            running.toResponse()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failureService.failStarting(qaTryId, "QA Agent session creation failed.")
            throw UpstreamUnavailableException(
                error.message ?: "QA Agent 세션 생성에 실패했습니다.",
                cause = error
            )
        }
    }

    suspend fun get(qaTryId: Long, userId: Long): QaTryResponse? =
        tryRepository.findAccessibleById(qaTryId, userId)?.toResponse()

    suspend fun listByProject(projectId: Long, userId: Long, size: Int): List<QaTryResponse> =
        tryRepository.findByProject(projectId, userId, size).map { it.toResponse() }.toList()

    /**
     * Relays one operator message to the Agent mid-run.
     *
     * Persisted before it is sent, and from the user's own direction: the message
     * steers the next decision, so it is evidence in the same timeline as the
     * frames it influences. The Agent's reply arrives later as an inbound CHAT
     * frame, which is why nothing here waits for one.
     */
    suspend fun sendMessage(qaTryId: Long, userId: Long, message: String) {
        val qaTry = requireAccessible(qaTryId, userId)
            ?: throw NotFoundException()
        if (qaTry.status != "RUNNING") {
            throw ConflictException("QA try is not running")
        }
        val sessionId = qaTry.agentSessionId
            ?: throw ConflictException("QA agent is not attached")
        val payload = objectMapper.createObjectNode().put("message", message)
        val inbound = logService.append(
            qaTryId = qaTryId,
            direction = "USER_TO_ORCHE",
            type = "CHAT",
            message = message,
            payload = payload
        )
        logService.publish(inbound)
        val outbound = logService.append(
            qaTryId = qaTryId,
            direction = "ORCHE_TO_AGENT",
            type = "CHAT",
            message = message,
            payload = payload
        )
        logService.publish(outbound)
        agentPort.send(
            sessionId,
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = "CHAT",
                qaTryId = qaTryId.toString(),
                timestamp = Instant.now(clock),
                payload = payload
            )
        )
    }

    /**
     * Ends a run at the operator's request.
     *
     * A run that already finished answers 409 rather than pretending to cancel —
     * "cancelled" and "completed" mean different things to whoever reads the
     * timeline later.
     */
    suspend fun cancel(qaTryId: Long, userId: Long) {
        requireAccessible(qaTryId, userId)
            ?: throw NotFoundException()
        val cancelled = failureService.cancelled(qaTryId, "QA execution was cancelled by the user.")
        if (!cancelled) {
            throw ConflictException("QA try has already ended")
        }
    }

    suspend fun requireAccessible(qaTryId: Long, userId: Long): QaTryEntity? =
        tryRepository.findAccessibleById(qaTryId, userId)

    suspend fun logs(qaTryId: Long, userId: Long, beforeId: Long?, size: Int): QaLogPageResponse? {
        requireAccessible(qaTryId, userId) ?: return null
        return logService.page(qaTryId, beforeId, size)
    }

    fun events(qaTryId: Long, userId: Long, afterId: Long): Flow<QaLogResponse> = flow {
        val qaTry = requireAccessible(qaTryId, userId) ?: return@flow
        emitAll(
            logService.stream(
                qaTryId,
                afterId,
                qaTry.status in setOf("COMPLETED", "FAILED", "CANCELLED")
            )
        )
    }

    private fun QaTryEntity.toResponse() = QaTryResponse(
        id = requireNotNull(id).toString(),
        testScenarioId = testScenarioId.toString(),
        gameInstanceId = gameInstanceId.toString(),
        startedBy = startedBy.toString(),
        status = status,
        startedAt = startedAt,
        completedAt = completedAt
    )
}
