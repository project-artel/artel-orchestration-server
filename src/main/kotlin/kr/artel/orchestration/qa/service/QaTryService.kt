package kr.artel.orchestration.qa.service

import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import com.fasterxml.jackson.databind.JsonNode
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
import kr.artel.orchestration.qa.dto.QaRunResponse
import kr.artel.orchestration.qa.dto.QaStatusPayload
import kr.artel.orchestration.qa.dto.QaTryResponse
import kr.artel.orchestration.qa.dto.CreateQaRunRequest
import kr.artel.orchestration.qa.dto.CreateQaTryRequest
import kr.artel.orchestration.qa.dto.QaReasoningRequest
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import kr.artel.orchestration.testscenario.service.TestScenarioAccessService
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal fun QaReasoningRequest.toAgentPayload(objectMapper: ObjectMapper) =
    objectMapper.createObjectNode().apply {
        effort?.let { put("effort", it) }
        maxTokens?.let { put("max_tokens", it) }
    }

/**
 * What a caller asked one run to be executed with.
 *
 * Grouped rather than passed as five nullable parameters in a row, which is a
 * shape two of them will eventually be swapped in. Every field is optional and
 * an absent one is not forwarded at all, so the Agent applies its own default —
 * see [QaSessionOpenRequest]'s NON_NULL.
 *
 * None of this is what gets recorded. These are wishes; the Agent answers with
 * what it resolved them to, and that answer is what reaches [QaTryEntity].
 */
data class QaRunSettings(
    val model: String? = null,
    val language: String? = null,
    val promptVersion: String? = null,
    val reasoning: JsonNode? = null,
    val arch: JsonNode? = null
)

fun CreateQaTryRequest.toRunSettings(objectMapper: ObjectMapper) = QaRunSettings(
    model = model,
    language = language,
    promptVersion = promptVersion,
    reasoning = reasoning?.toAgentPayload(objectMapper),
    arch = arch
)

fun CreateQaRunRequest.toRunSettings(objectMapper: ObjectMapper) = QaRunSettings(
    model = model,
    language = language,
    promptVersion = promptVersion,
    reasoning = reasoning?.toAgentPayload(objectMapper),
    arch = arch
)

/**
 * The comparison axes, lifted out of the Agent's resolved config.
 *
 * Copies, deliberately: the whole config is stored as JSONB and is the record,
 * while these four are columns so that grouping a few thousand runs by model or
 * by structure is an index scan rather than a JSON traversal per row. When the
 * two disagree the JSONB is right — a column is only ever derived from it.
 */
private fun JsonNode?.textAt(vararg path: String): String? {
    var node: JsonNode = this ?: return null
    for (name in path) {
        node = node.get(name) ?: return null
    }
    return node.takeIf { it.isTextual }?.asText()
}

/** 런 단위 실행 적재 결과: 부모 qa_run(STARTING) + 시나리오당 qa_try(PENDING) N개(순서=시나리오 순서). */
data class QaRunStarting(val qaRun: QaRunEntity, val tries: List<QaTryEntity>)

@Service
class QaTryPersistenceService(
    private val tryRepository: QaTryRepository,
    private val runRepository: QaRunRepository,
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

    /**
     * 런 단위 실행을 적재한다(ARTEL-259): qa_run(STARTING) 하나 + 시나리오당 qa_try(PENDING) N개를
     * 한 트랜잭션으로 만든다. [scenarioIds]는 런의 시나리오 순서(position). 각 qa_try는 qa_run_id로
     * 부모를 가리키고, 자기 차례가 오면 PENDING→RUNNING으로 활성된다(활성은 항상 하나).
     */
    suspend fun createRunStarting(
        testRunId: Long,
        gameInstanceId: Long,
        startedBy: Long,
        scenarioIds: List<Long>
    ): QaRunStarting =
        transactionalOperator.executeAndAwait {
            val now = Instant.now(clock)
            val qaRun = runRepository.save(
                QaRunEntity(
                    testRunId = testRunId,
                    gameInstanceId = gameInstanceId,
                    startedBy = startedBy,
                    status = "STARTING",
                    startedAt = now
                )
            )
            val runId = requireNotNull(qaRun.id)
            val tries = scenarioIds.map { scenarioId ->
                val qaTry = tryRepository.save(
                    QaTryEntity(
                        testScenarioId = scenarioId,
                        gameInstanceId = gameInstanceId,
                        qaRunId = runId,
                        startedBy = startedBy,
                        status = "PENDING",
                        startedAt = now
                    )
                )
                logService.append(
                    qaTryId = requireNotNull(qaTry.id),
                    direction = "ORCHE_INTERNAL",
                    type = "STATUS",
                    message = "QA scenario is queued.",
                    payload = objectMapper.valueToTree(QaStatusPayload("PENDING", null))
                )
                qaTry
            }
            QaRunStarting(qaRun, tries)
        } ?: error("QA run creation returned no result")

    /**
     * 런 세션을 부착하고 실행 상태로 만든다(ARTEL-259): qa_run STARTING→RUNNING + Agent가 확정한
     * 세션 공통 run_config 반영 + 첫 시나리오 qa_try PENDING→RUNNING(활성). 이후 시나리오는 인바운드
     * 라우터가 그 차례에 activatePending으로 활성한다.
     */
    suspend fun attachRunAndMarkRunning(
        qaRun: QaRunEntity,
        firstTryId: Long,
        agentSessionId: String,
        runConfig: JsonNode? = null
    ): QaRunEntity =
        transactionalOperator.executeAndAwait {
            val now = Instant.now(clock)
            val runId = requireNotNull(qaRun.id)
            val configJson = objectMapper.writeValueAsString(runConfig ?: objectMapper.createObjectNode())
            if (runRepository.attachAgentSession(runId, agentSessionId, configJson, now) != 1) {
                throw IllegalStateException("QA run cannot attach an Agent session")
            }
            if (runRepository.transition(runId, "STARTING", "RUNNING", null, now) != 1) {
                throw IllegalStateException("QA run is no longer STARTING")
            }
            if (
                tryRepository.activatePending(
                    id = firstTryId,
                    agentSessionId = agentSessionId,
                    model = runConfig.textAt("model"),
                    reasoningEffort = runConfig.textAt("reasoning", "effort"),
                    promptVersion = runConfig.textAt("prompt_version"),
                    agentArch = runConfig.textAt("agent_arch"),
                    agentFingerprint = runConfig.textAt("agent_fingerprint"),
                    runConfig = configJson,
                    updatedAt = now
                ) != 1
            ) {
                throw IllegalStateException("first QA try cannot be activated")
            }
            logService.append(
                qaTryId = firstTryId,
                direction = "ORCHE_INTERNAL",
                type = "STATUS",
                message = "QA execution is running.",
                payload = objectMapper.valueToTree(QaStatusPayload("RUNNING", null))
            )
            requireNotNull(runRepository.findById(runId))
        } ?: error("QA run transition returned no result")

    /**
     * @param runConfig what the Agent resolved the session to, or null if it did
     *   not say. A run whose settings are unknown is a gap in the comparison; a
     *   run that refuses to start over it is an outage, so null is written
     *   through rather than rejected.
     */
    suspend fun attachAndMarkRunning(
        qaTry: QaTryEntity,
        agentSessionId: String,
        runConfig: JsonNode? = null
    ): Pair<QaTryEntity, QaLogAppendResult> =
        transactionalOperator.executeAndAwait {
            val now = Instant.now(clock)
            val id = requireNotNull(qaTry.id)
            // The settings ride along with the session id rather than following in
            // a second update: two statements would leave a window where the try
            // reads as started and is not attributable, and nothing goes back for it.
            if (
                tryRepository.attachAgentSession(
                    id = id,
                    agentSessionId = agentSessionId,
                    model = runConfig.textAt("model"),
                    reasoningEffort = runConfig.textAt("reasoning", "effort"),
                    promptVersion = runConfig.textAt("prompt_version"),
                    agentArch = runConfig.textAt("agent_arch"),
                    agentFingerprint = runConfig.textAt("agent_fingerprint"),
                    runConfig = objectMapper.writeValueAsString(runConfig ?: objectMapper.createObjectNode()),
                    updatedAt = now
                ) != 1
            ) {
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
    private val runRepository: QaRunRepository,
    private val scenarioAccessService: TestScenarioAccessService,
    private val compositionService: ScenarioCompositionService,
    private val testRunService: TestRunService,
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
    suspend fun create(
        testScenarioId: Long,
        gameInstanceId: Long,
        userId: Long,
        settings: QaRunSettings = QaRunSettings()
    ): QaTryResponse {
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
            scenario = compositionService.agentScenario(testScenarioId, userId, scenario.payload.asString()),
            model = settings.model,
            language = settings.language,
            promptVersion = settings.promptVersion,
            reasoning = settings.reasoning,
            arch = settings.arch
        )

        return try {
            val agent = agentPort.createSession(
                context = context,
                onMessage = { envelope -> inboundRouter.handle(envelope) },
                onDisconnect = { failureService.agentDisconnected(qaTryId) }
            )
            val (running, runningLog) =
                persistence.attachAndMarkRunning(starting, agent.sessionId, agent.runConfig)
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

    /**
     * 런(TR) 단위 QA를 시작한다(ARTEL-259): 런의 시나리오들을 qa_run + 시나리오당 qa_try(PENDING)로
     * 적재하고, scenarios[](각 qa_try_id + cases 본문)를 담아 Agent 세션을 연다. 세션이 붙으면 첫
     * 시나리오가 활성되고, 이후 시나리오는 인바운드 라우터가 그 차례에 활성한다. 실패 시 런과 그
     * 시나리오 try를 모두 FAILED로 정리한다.
     */
    suspend fun createRun(
        testRunId: Long,
        gameInstanceId: Long,
        userId: Long,
        settings: QaRunSettings = QaRunSettings()
    ): QaRunResponse {
        val scenarios = testRunService.getScenarios(testRunId, userId)
            ?: throw NotFoundException()
        val scenarioIds = scenarios.items.map { it.testScenarioId.toLong() }
        if (scenarioIds.isEmpty()) {
            throw ConflictException("Test run has no scenarios to execute")
        }
        val instance = instanceRepository.findAccessibleByIdForMember(gameInstanceId, userId)
            ?: throw NotFoundException()
        val firstScenario = scenarioAccessService.accessibleScenario(scenarioIds.first(), userId)
            ?: throw NotFoundException()
        if (firstScenario.projectId != instance.projectId) {
            throw NotFoundException()
        }
        if (!sessionManager.hasSession(gameInstanceId.toString())) {
            throw ConflictException("Game instance SDK is not connected")
        }
        if (
            runRepository.findActiveByGameInstanceId(gameInstanceId) != null ||
            tryRepository.findActiveByGameInstanceId(gameInstanceId) != null
        ) {
            throw ConflictException("An active QA run already exists")
        }

        val started = persistence.createRunStarting(testRunId, gameInstanceId, userId, scenarioIds)
        val agentScenarios = started.tries.zip(scenarioIds).map { (qaTry, scenarioId) ->
            val entity = scenarioAccessService.accessibleScenario(scenarioId, userId)
                ?: throw NotFoundException()
            QaAgentScenario(
                qaTryId = requireNotNull(qaTry.id).toString(),
                testScenarioId = scenarioId.toString(),
                scenario = compositionService.agentScenario(scenarioId, userId, entity.payload.asString())
            )
        }
        val context = QaAgentSessionContext(
            gameInstanceId = gameInstanceId.toString(),
            qaRunId = requireNotNull(started.qaRun.id).toString(),
            scenarios = agentScenarios,
            model = settings.model,
            language = settings.language,
            promptVersion = settings.promptVersion,
            reasoning = settings.reasoning,
            arch = settings.arch
        )

        return try {
            val agent = agentPort.createSession(
                context = context,
                onMessage = { envelope -> inboundRouter.handle(envelope) },
                onDisconnect = { failRun(started) }
            )
            val running = persistence.attachRunAndMarkRunning(
                started.qaRun, requireNotNull(started.tries.first().id), agent.sessionId, agent.runConfig
            )
            running.toRunResponse(started.tries)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failRun(started)
            throw UpstreamUnavailableException(
                error.message ?: "QA Run 세션 생성에 실패했습니다.",
                cause = error
            )
        }
    }

    private suspend fun failRun(started: QaRunStarting) {
        val now = Instant.now(clock)
        val runId = requireNotNull(started.qaRun.id)
        runCatching {
            // 세션이 붙기 전(STARTING)이든 실행 중(RUNNING)이든 모두 FAILED로 닫는다. 둘 중
            // 실제 상태에 맞는 한 문장만 1행을 바꾸고 나머지는 no-op이다. RUNNING을 빠뜨리면
            // 런이 영구히 활성으로 남아 그 게임 인스턴스의 다음 런을 막는다(과거 버그).
            runRepository.transition(runId, "STARTING", "FAILED", now, now)
            runRepository.transition(runId, "RUNNING", "FAILED", now, now)
            tryRepository.failByQaRunId(runId, now)
        }
    }

    private fun QaRunEntity.toRunResponse(tries: List<QaTryEntity>) = QaRunResponse(
        id = requireNotNull(id).toString(),
        testRunId = testRunId.toString(),
        gameInstanceId = gameInstanceId.toString(),
        startedBy = startedBy.toString(),
        status = status,
        startedAt = startedAt,
        completedAt = completedAt,
        tries = tries.map { it.toResponse() }
    )

    /**
     * 런 하나 + 그 아래 시나리오별 qa_try. FE 런 화면이 이것을 (종단까지) 폴링해 시나리오별
     * 진행 상태를 따라간다. 실시간 상세는 여전히 qa_try의 SSE — 여긴 개요다.
     */
    suspend fun getRun(qaRunId: Long, userId: Long): QaRunResponse? {
        val run = runRepository.findAccessibleById(qaRunId, userId) ?: return null
        val tries = tryRepository.findByQaRunId(qaRunId).toList()
        return run.toRunResponse(tries)
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

    /**
     * 런(TR) 전체를 취소한다. 활성 시나리오 try는 Agent 세션 종료까지 포함해 취소하고, 아직 차례가
     * 오지 않은(PENDING 등) 미종단 try는 정리한 뒤 qa_run을 CANCELLED로 닫는다. 이미 끝난 런은 409.
     *
     * 재실행 가드는 qa_run(STARTING/RUNNING)을 보므로, 이 경로가 있어야 스테일 런이 게임 인스턴스를
     * 영구 점유하지 않는다("먼저 열어보거나 종료하라"의 실제 종료 동작).
     */
    suspend fun cancelRun(qaRunId: Long, userId: Long) {
        val run = runRepository.findAccessibleById(qaRunId, userId) ?: throw NotFoundException()
        if (run.status != "STARTING" && run.status != "RUNNING") {
            throw ConflictException("QA run has already ended")
        }
        val active = tryRepository.findByQaRunId(qaRunId).toList()
            .firstOrNull { it.status == "STARTING" || it.status == "RUNNING" }
        if (active != null) {
            // 활성 try는 Agent에 CANCEL 통보 + 세션 종료까지 포함해 취소.
            failureService.cancelled(requireNotNull(active.id), "QA run was cancelled by the user.")
        }
        val now = Instant.now(clock)
        // 아직 안 돈 미종단(PENDING 등) try를 정리하고, 런을 CANCELLED로 닫는다.
        tryRepository.failByQaRunId(qaRunId, now)
        runRepository.transition(qaRunId, "STARTING", "CANCELLED", now, now)
        runRepository.transition(qaRunId, "RUNNING", "CANCELLED", now, now)
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
        completedAt = completedAt,
        model = model,
        promptVersion = promptVersion,
        agentArch = agentArch,
        agentFingerprint = agentFingerprint,
        runConfig = objectMapper.readTree(runConfig.asString())
    )
}
