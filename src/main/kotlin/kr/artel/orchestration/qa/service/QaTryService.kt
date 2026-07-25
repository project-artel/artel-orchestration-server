package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.qa.dto.QaStatusPayload
import kr.artel.orchestration.qa.dto.QaTryResponse
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testscenario.service.TestScenarioAccessService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class QaTryPersistenceService(
    private val tryRepository: QaTryRepository,
    private val logService: QaLogService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    @Transactional
    fun createStarting(
        testScenarioId: Long,
        gameInstanceId: Long,
        startedBy: Long
    ): Mono<Pair<QaTryEntity, QaLogAppendResult>> {
        val now = Instant.now(clock)
        return tryRepository.save(
            QaTryEntity(
                testScenarioId = testScenarioId,
                gameInstanceId = gameInstanceId,
                startedBy = startedBy,
                status = "STARTING",
                startedAt = now
            )
        ).flatMap { qaTry ->
            logService.append(
                qaTryId = requireNotNull(qaTry.id),
                direction = "ORCHE_INTERNAL",
                type = "STATUS",
                message = "QA execution is starting.",
                payload = objectMapper.valueToTree(QaStatusPayload("STARTING", null))
            ).map { qaTry to it }
        }
    }

    @Transactional
    fun attachAndMarkRunning(
        qaTry: QaTryEntity,
        agentSessionId: String
    ): Mono<Pair<QaTryEntity, QaLogAppendResult>> {
        val now = Instant.now(clock)
        return tryRepository.attachAgentSession(requireNotNull(qaTry.id), agentSessionId, now)
            .filter { it == 1 }
            .switchIfEmpty(Mono.error(IllegalStateException("QA try cannot attach an Agent session")))
            .then(tryRepository.transition(requireNotNull(qaTry.id), "STARTING", "RUNNING", null, now))
            .filter { it == 1 }
            .switchIfEmpty(Mono.error(IllegalStateException("QA try is no longer STARTING")))
            .then(tryRepository.findById(requireNotNull(qaTry.id)))
            .flatMap { running ->
                logService.append(
                    qaTryId = requireNotNull(running.id),
                    direction = "ORCHE_INTERNAL",
                    type = "STATUS",
                    message = "QA execution is running.",
                    payload = objectMapper.valueToTree(QaStatusPayload("RUNNING", null))
                ).map { running to it }
            }
    }
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
    fun create(testScenarioId: Long, gameInstanceId: Long, userId: Long): Mono<QaTryResponse> =
        Mono.zip(
            scenarioAccessService.accessibleScenario(testScenarioId, userId),
            instanceRepository.findAccessibleByIdForMember(gameInstanceId, userId)
        ).switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.NOT_FOUND)))
            .flatMap { tuple ->
                if (tuple.t1.projectId != tuple.t2.projectId) {
                    return@flatMap Mono.error(ResponseStatusException(HttpStatus.NOT_FOUND))
                }
                if (!sessionManager.hasSession(gameInstanceId.toString())) {
                    return@flatMap Mono.error(
                        ResponseStatusException(HttpStatus.CONFLICT, "Game instance SDK is not connected")
                    )
                }
                tryRepository.findActiveByGameInstanceId(gameInstanceId)
                    .flatMap<QaTryResponse> {
                        Mono.error(ResponseStatusException(HttpStatus.CONFLICT, "An active QA try already exists"))
                    }
                    .switchIfEmpty(Mono.defer {
                        persistence.createStarting(testScenarioId, gameInstanceId, userId)
                            .flatMap { (starting, startLog) ->
                                logService.publish(startLog)
                                val qaTryId = requireNotNull(starting.id)
                                val context = QaAgentSessionContext(
                                    qaTryId = qaTryId.toString(),
                                    gameInstanceId = gameInstanceId.toString(),
                                    testScenarioId = testScenarioId.toString(),
                                    scenario = objectMapper.readTree(tuple.t1.payload.asString())
                                )
                                agentPort.createSession(
                                    context = context,
                                    onMessage = { envelope -> inboundRouter.handle(envelope) },
                                    onDisconnect = {
                                        failureService.agentDisconnected(qaTryId)
                                    }
                                ).flatMap { agent ->
                                    persistence.attachAndMarkRunning(starting, agent.sessionId)
                                        .doOnNext { (_, runningLog) -> logService.publish(runningLog) }
                                        .map { (running, _) -> running.toResponse() }
                                }.onErrorResume { error ->
                                    failureService.failStarting(qaTryId, "QA Agent session creation failed.")
                                        .then(Mono.error(
                                            ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, error.message)
                                        ))
                                }
                            }
                    })
            }

    fun get(qaTryId: Long, userId: Long): Mono<QaTryResponse> =
        tryRepository.findAccessibleById(qaTryId, userId).map { it.toResponse() }

    fun listByProject(projectId: Long, userId: Long, size: Int) =
        tryRepository.findByProject(projectId, userId, size).map { it.toResponse() }

    /**
     * Relays one operator message to the Agent mid-run.
     *
     * Persisted before it is sent, and from the user's own direction: the message
     * steers the next decision, so it is evidence in the same timeline as the
     * frames it influences. The Agent's reply arrives later as an inbound CHAT
     * frame, which is why nothing here waits for one.
     */
    fun sendMessage(qaTryId: Long, userId: Long, message: String): Mono<Void> =
        requireAccessible(qaTryId, userId)
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.NOT_FOUND)))
            .flatMap { qaTry ->
                if (qaTry.status != "RUNNING") {
                    return@flatMap Mono.error<Void>(
                        ResponseStatusException(HttpStatus.CONFLICT, "QA try is not running")
                    )
                }
                val sessionId = qaTry.agentSessionId
                    ?: return@flatMap Mono.error<Void>(
                        ResponseStatusException(HttpStatus.CONFLICT, "QA agent is not attached")
                    )
                val payload = objectMapper.createObjectNode().put("message", message)
                logService.append(
                    qaTryId = qaTryId,
                    direction = "USER_TO_ORCHE",
                    type = "CHAT",
                    message = message,
                    payload = payload
                ).flatMap { inbound ->
                    logService.publish(inbound)
                    logService.append(
                        qaTryId = qaTryId,
                        direction = "ORCHE_TO_AGENT",
                        type = "CHAT",
                        message = message,
                        payload = payload
                    ).flatMap { outbound ->
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
                }
            }

    /**
     * Ends a run at the operator's request.
     *
     * A run that already finished answers 409 rather than pretending to cancel —
     * "cancelled" and "completed" mean different things to whoever reads the
     * timeline later.
     */
    fun cancel(qaTryId: Long, userId: Long): Mono<Void> =
        requireAccessible(qaTryId, userId)
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.NOT_FOUND)))
            .flatMap { failureService.cancelled(qaTryId, "QA execution was cancelled by the user.") }
            .flatMap { cancelled ->
                if (cancelled) Mono.empty()
                else Mono.error(ResponseStatusException(HttpStatus.CONFLICT, "QA try has already ended"))
            }

    fun requireAccessible(qaTryId: Long, userId: Long): Mono<QaTryEntity> =
        tryRepository.findAccessibleById(qaTryId, userId)

    fun logs(qaTryId: Long, userId: Long, beforeId: Long?, size: Int) =
        requireAccessible(qaTryId, userId).flatMap { logService.page(qaTryId, beforeId, size) }

    fun events(qaTryId: Long, userId: Long, afterId: Long) =
        requireAccessible(qaTryId, userId).flatMapMany {
            logService.stream(qaTryId, afterId, it.status in setOf("COMPLETED", "FAILED", "CANCELLED"))
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
