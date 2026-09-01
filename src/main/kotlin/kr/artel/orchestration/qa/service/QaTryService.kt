package kr.artel.orchestration.qa.service

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.knowledge.entity.KnowledgeMode
import kr.artel.orchestration.knowledge.service.KnowledgeCitationService
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
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import kr.artel.orchestration.testscenario.service.TestScenarioAccessService
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * `qa_try.run_config` 안에서 지식 게이트가 사는 키(ARTEL-256).
 *
 * 여기서 쓰고 [kr.artel.orchestration.qa.service.QaAgentInboundRouter]가 읽는다. Agent가 채우는
 * 다른 키들과 달리 이것은 Orchestration이 넣는 값이지만, 축별 집계(ARTEL-243)가 run_config 한 곳만
 * 읽게 하려고 같은 객체에 둔다.
 */
internal const val KNOWLEDGE_MODE_FIELD = "knowledge_mode"

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
 * 이 런에 지식창고를 어떻게 열어 줄지(ARTEL-256).
 *
 * [QaRunSettings]와 나란히 두되 합치지 않는다. 저쪽은 **Agent에 전달되어 Agent가 해석하는** 값이고,
 * 이쪽은 Orchestration이 직접 집행하는 값이다. 지식 게이트가 Agent로 넘어가면 arm마다 Agent
 * 프롬프트가 달라져, 실험에서 달라지는 변수가 "지식 가용성" 하나로 좁혀지지 않는다.
 *
 * @param scopeId 이 런이 읽고 쓸 스코프. null이면 운영 런이라 이 기능 이전과 동작이 같다.
 * @param mode 읽기/쓰기 게이트. 기본값은 지금까지의 동작([KnowledgeMode.LEARNING])이다.
 */
data class QaKnowledgeSettings(
    val scopeId: Long? = null,
    val mode: KnowledgeMode = KnowledgeMode.DEFAULT
)

/**
 * 요청의 지식 설정을 파싱한다. 잘못된 값은 [BadRequestException]으로 거절한다 — 조용히 기본값으로
 * 떨어지면 대조군으로 돌린 arm이 사실은 학습을 하고, 그 오염은 결과가 그럴듯해서 드러나지 않는다.
 */
fun CreateQaTryRequest.toKnowledgeSettings(): QaKnowledgeSettings {
    val scopeId = knowledgeScopeId?.trim()?.takeIf { it.isNotEmpty() }?.let {
        it.toLongOrNull() ?: throw BadRequestException("knowledgeScopeId must be a decimal string")
    }
    val mode = knowledgeMode?.let {
        KnowledgeMode.fromWire(it)
            ?: throw BadRequestException("knowledgeMode must be one of ${KnowledgeMode.WIRE_NAMES}")
    } ?: KnowledgeMode.DEFAULT
    return QaKnowledgeSettings(scopeId = scopeId, mode = mode)
}

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
    /**
     * @param knowledge 이 런의 지식 스코프와 모드(ARTEL-256). **런이 만들어지는 순간 기록된다.**
     *   Agent 세션이 붙기를 기다리지 않는 것이 중요하다: 세션 개설 중에도 try는 이미 STARTING이라
     *   라우터가 프레임을 받고, 그 사이 모드가 비어 있으면 `frozen`으로 돌린 arm이 그 창에서
     *   지식창고에 쓸 수 있다. `attachAndMarkRunning`이 Agent 설정을 덮어쓸 때 같은 값을 다시 얹는다.
     */
    suspend fun createStarting(
        testScenarioId: Long,
        gameInstanceId: Long,
        startedBy: Long,
        knowledge: QaKnowledgeSettings
    ): Pair<QaTryEntity, QaLogAppendResult> =
        transactionalOperator.executeAndAwait {
            val now = Instant.now(clock)
            val qaTry = tryRepository.save(
                QaTryEntity(
                    testScenarioId = testScenarioId,
                    gameInstanceId = gameInstanceId,
                    startedBy = startedBy,
                    status = "STARTING",
                    knowledgeScopeId = knowledge.scopeId,
                    runConfig = Json.of(
                        objectMapper.writeValueAsString(
                            objectMapper.createObjectNode().put(KNOWLEDGE_MODE_FIELD, knowledge.mode.wire)
                        )
                    ),
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
            // Agent 스냅샷이 객체가 아니면(없거나 스칼라) 빈 객체에서 시작한다.
            //
            // knowledge_mode는 **호출자가 다시 주는 것이 아니라 STARTING 행에서 옮겨 온다**
            // (ARTEL-256). 이 UPDATE가 run_config를 통째로 갈아치우므로 어디선가는 다시 얹어야
            // 하는데, 파라미터로 받으면 호출자가 createStarting 때와 다른 값을 넘길 수 있고 그러면
            // 게이트가 이미 집행된 뒤에 기록만 바뀐다. 여기서 읽어 오면 그 어긋남이 불가능하다.
            val carriedMode = objectMapper.readTree(qaTry.runConfig.asString()).path(KNOWLEDGE_MODE_FIELD)
            val storedConfig = ((runConfig as? ObjectNode)?.deepCopy() ?: objectMapper.createObjectNode())
                .apply { if (carriedMode.isTextual) put(KNOWLEDGE_MODE_FIELD, carriedMode.asText()) }
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
                    runConfig = objectMapper.writeValueAsString(storedConfig),
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
    private val platformAccessService: PlatformAccessService,
    private val compositionService: ScenarioCompositionService,
    private val testRunService: TestRunService,
    private val instanceRepository: GameInstanceRepository,
    private val sessionManager: SessionManager,
    private val agentPort: QaAgentPort,
    private val inboundRouter: QaAgentInboundRouter,
    private val failureService: QaExecutionFailureService,
    private val citationService: KnowledgeCitationService,
    private val persistence: QaTryPersistenceService,
    private val readings: QaReadingsService,
    private val logService: QaLogService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    suspend fun create(
        testScenarioId: Long,
        gameInstanceId: Long,
        userId: Long,
        settings: QaRunSettings = QaRunSettings(),
        knowledge: QaKnowledgeSettings = QaKnowledgeSettings()
    ): QaTryResponse {
        val scenario = scenarioAccessService.accessibleScenario(testScenarioId, userId)
            ?: throw NotFoundException()
        val instance = instanceRepository.findAccessibleByIdForMember(gameInstanceId, userId)
            ?: throw NotFoundException()
        if (scenario.projectId != instance.projectId) {
            throw NotFoundException()
        }
        if (!sessionManager.hasSession(gameInstanceId.toString())) {
            throw SdkDisconnectedException()
        }
        if (tryRepository.findActiveByGameInstanceId(gameInstanceId) != null) {
            throw ActiveQaRunException("An active QA try already exists")
        }

        val (starting, startLog) = persistence.createStarting(testScenarioId, gameInstanceId, userId, knowledge)
        logService.publish(startLog)
        val qaTryId = requireNotNull(starting.id)
        val context = QaAgentSessionContext(
            qaTryId = qaTryId.toString(),
            gameInstanceId = gameInstanceId.toString(),
            // 인스턴스에서 그대로 온다 (ARTEL-676). `lastGameBuildId` 는 SDK 가 붙은 적 없는
            // 인스턴스에서 비고, 그때는 Agent 가 조회 없이 런을 시작한다 — 런을 막지 않는다.
            projectId = instance.projectId.toString(),
            gameBuildId = instance.lastGameBuildId?.toString(),
            testScenarioId = testScenarioId.toString(),
            scenario = objectMapper.valueToTree(scenario.toDraft(objectMapper)),
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
            // 런이 정말 시작한 뒤에 켠다 (ARTEL-507). 연결이 아니라 세션이 켜는 이유는
            // `QaReadingsService` 주석에 있다 — 연결 시점은 전체 씬 순회와 겹친다.
            readings.start(gameInstanceId)
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
        settings: QaRunSettings = QaRunSettings(),
        force: Boolean = false
    ): QaRunResponse {
        val scenarios = testRunService.getScenarios(testRunId, userId)
            ?: throw NotFoundException()
        val scenarioIds = scenarios.items.map { it.testScenarioId.toLong() }
        if (scenarioIds.isEmpty()) {
            throw EmptyTestRunException()
        }
        val instance = instanceRepository.findAccessibleByIdForMember(gameInstanceId, userId)
            ?: throw NotFoundException()
        val firstScenario = scenarioAccessService.accessibleScenario(scenarioIds.first(), userId)
            ?: throw NotFoundException()
        if (firstScenario.projectId != instance.projectId) {
            throw NotFoundException()
        }
        if (!sessionManager.hasSession(gameInstanceId.toString())) {
            throw SdkDisconnectedException()
        }
        // 이어받기는 SDK 연결 확인 **뒤에** 온다. 붙어 있지도 않은 게임 때문에 남의 런을 끊는 것은
        // 어느 쪽에도 이득이 없다 — 새 런은 어차피 시작하지 못한다.
        if (force) {
            takeOverActiveRun(gameInstanceId, userId)
        }
        if (
            runRepository.findActiveByGameInstanceId(gameInstanceId) != null ||
            tryRepository.findActiveByGameInstanceId(gameInstanceId) != null
        ) {
            throw ActiveQaRunException()
        }

        val started = persistence.createRunStarting(testRunId, gameInstanceId, userId, scenarioIds)
        val agentScenarios = started.tries.zip(scenarioIds).map { (qaTry, scenarioId) ->
            val entity = scenarioAccessService.accessibleScenario(scenarioId, userId)
                ?: throw NotFoundException()
            QaAgentScenario(
                qaTryId = requireNotNull(qaTry.id).toString(),
                testScenarioId = scenarioId.toString(),
                scenario = compositionService.agentScenario(entity.toDraft(objectMapper))
            )
        }
        val context = QaAgentSessionContext(
            gameInstanceId = gameInstanceId.toString(),
            // 단일 시나리오 경로와 같다 (ARTEL-676).
            projectId = instance.projectId.toString(),
            gameBuildId = instance.lastGameBuildId?.toString(),
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
            // 시나리오가 여럿이어도 여기서 한 번이다. 판독은 인스턴스에 붙는 것이지 시나리오에
            // 붙는 것이 아니라, 시나리오마다 켜면 같은 말을 N 번 하는 것이 된다.
            readings.start(gameInstanceId)
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
        // try들이 방금 종단으로 갔으므로 미인용 행을 확정한다(ARTEL-293). 세션 개설 실패는
        // 검색이 한 번도 안 돌았을 가능성이 높지만 "높다"는 근거가 아니다 — 시나리오 하나를
        // 돌린 뒤 소켓이 죽은 런도 이 경로로 온다.
        citationService.finalizeRun(runId)
        // 같은 이유로 판독도 여기서 끈다 (ARTEL-507). 이 경로는 `onDisconnect` 로도 오는데,
        // 그때는 런이 이미 돌고 있었으므로 판독이 켜져 있다. 세션 개설이 실패한 경우에는 아직
        // 켠 적이 없지만 `stopIfIdle` 이 멱등이라 무해하다.
        readings.stopIfIdle(started.qaRun.gameInstanceId)
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
        tryRepository
            .findByProject(
                projectId = projectId,
                userId = userId,
                seesAllProjects = platformAccessService.seesAllProjects(userId),
                limit = size
            )
            .map { it.toResponse() }
            .toList()

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
        // 세션은 **활성 try가 있든 없든** 끊는다. 위 `failureService.cancelled`는 활성 try의
        // agent_session_id로만 끊는데, 활성 try가 없는 창이 실제로 존재한다: 런이 아직 STARTING이라
        // try가 전부 PENDING인 구간, 그리고 시나리오 N이 끝나고 N+1의 첫 프레임이 도착하기 전
        // 구간(그 사이 Agent는 게임을 리셋하고 있어 창이 길다). 그 창에서 취소하면 DB만 닫히고
        // Agent 세션은 살아남아 다음 시나리오를 계속 돌렸다 — 운영자가 보기에 "종료가 안 먹힌다".
        // 세션 id는 런이 들고 있으므로 그것으로 끊는다. 이미 끊긴 세션이면 no-op이다.
        failureService.releaseAgentSession(run.agentSessionId)
        // 활성 try는 위 `failureService.cancelled`가 이미 확정했다. 여기서 남는 것은 그 앞뒤로
        // 종단이 된 시나리오들이며, 두 번 도는 것이 안전하다(확정은 cited IS NULL만 건드린다).
        citationService.finalizeRun(qaRunId)
        // 런과 그 시도들이 전부 닫힌 뒤다. 활성 시도가 있었다면 `failureService.cancelled` 가
        // 이미 한 번 껐지만 `stopIfIdle` 은 멱등이고, 활성 시도가 없던 런은 여기서만 꺼진다.
        readings.stopIfIdle(run.gameInstanceId)
    }

    /**
     * 그 게임 인스턴스에서 아직 안 끝난 QA를 끝내고 자리를 비운다(런 이어받기).
     *
     * 운영자가 "진행 중인 QA를 종료하고 실행"을 고른 경우에만 불린다. 스테일 런이 게임을 영구
     * 점유하는 일이 잦은데 — Orchestration이 배포로 재시작하면 소켓만 죽고 DB의 런은 RUNNING으로
     * 남는다 — 그때마다 운영자를 다른 화면으로 보내 종료시키고 돌아오게 하는 것은 같은 결정을 두
     * 번 시키는 것이다.
     *
     * 취소는 [cancelRun]을 그대로 쓴다. 접근 검사·Agent 세션 종료·인용 확정·채점이 전부 거기 붙어
     * 있고, 이어받기라고 해서 그중 하나라도 건너뛸 이유가 없다. 그 사이 런이 스스로 끝났으면
     * [ConflictException]이 오는데 그것은 실패가 아니라 원하던 결과라 삼킨다.
     */
    private suspend fun takeOverActiveRun(gameInstanceId: Long, userId: Long) {
        runRepository.findActiveByGameInstanceId(gameInstanceId)?.let { active ->
            try {
                cancelRun(requireNotNull(active.id), userId)
            } catch (_: ConflictException) {
                // 이미 끝났다. 자리는 비었으므로 계속 간다.
            }
        }
        // 런에 딸리지 않은 단일 try(qa_run 이전 경로)는 위에서 안 닫힌다. 남아 있으면 그것이
        // 그대로 다음 런을 막으므로 따로 끊는다.
        tryRepository.findActiveByGameInstanceId(gameInstanceId)?.let { orphan ->
            failureService.cancelled(
                requireNotNull(orphan.id),
                "QA execution was cancelled to start a new run."
            )
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
        completedAt = completedAt,
        model = model,
        promptVersion = promptVersion,
        agentArch = agentArch,
        agentFingerprint = agentFingerprint,
        runConfig = objectMapper.readTree(runConfig.asString()),
        knowledgeScopeId = knowledgeScopeId?.toString()
    )
}
