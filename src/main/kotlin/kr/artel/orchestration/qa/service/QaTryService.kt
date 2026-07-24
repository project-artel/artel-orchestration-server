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
    private val objectMapper: ObjectMapper
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
