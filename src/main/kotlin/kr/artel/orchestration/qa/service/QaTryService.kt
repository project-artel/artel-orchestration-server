package kr.artel.orchestration.qa.service

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
import kr.artel.orchestration.qa.dto.QaLogPageResponse
import kr.artel.orchestration.qa.dto.QaLogResponse
import kr.artel.orchestration.qa.dto.QaStatusPayload
import kr.artel.orchestration.qa.dto.QaTryResponse
import kr.artel.orchestration.qa.dto.CreateQaTryRequest
import kr.artel.orchestration.qa.dto.QaReasoningRequest
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.service.SessionManager
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
            throw ConflictException("Game instance SDK is not connected")
        }
        if (tryRepository.findActiveByGameInstanceId(gameInstanceId) != null) {
            throw ConflictException("An active QA try already exists")
        }

        val (starting, startLog) = persistence.createStarting(testScenarioId, gameInstanceId, userId, knowledge)
        logService.publish(startLog)
        val qaTryId = requireNotNull(starting.id)
        val context = QaAgentSessionContext(
            qaTryId = qaTryId.toString(),
            gameInstanceId = gameInstanceId.toString(),
            testScenarioId = testScenarioId.toString(),
            scenario = objectMapper.readTree(scenario.payload.asString()),
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
        completedAt = completedAt,
        model = model,
        promptVersion = promptVersion,
        agentArch = agentArch,
        agentFingerprint = agentFingerprint,
        runConfig = objectMapper.readTree(runConfig.asString()),
        knowledgeScopeId = knowledgeScopeId?.toString()
    )
}
