package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * Entry point for a future QA Agent adapter. The persisted outbound log ID is
 * allocated by PostgreSQL and used as the SDK outer action ID.
 */
@Service
class QaActionDispatchService(
    private val tryRepository: QaTryRepository,
    private val logRepository: QaLogRepository,
    private val logService: QaLogService,
    private val databaseClient: DatabaseClient,
    private val sessionManager: SessionManager,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val sdkBridge: QaSdkBridgeService
) {
    suspend fun dispatch(
        qaTryId: Long,
        agentMessageId: String,
        message: String?,
        actionsPayload: JsonNode
    ): Boolean {
        require(agentMessageId.isNotBlank()) { "ACTION messageId is required" }
        val actionsNode = actionsPayload.path("actions")
        require(actionsNode.isArray && !actionsNode.isEmpty) { "ACTION payload.actions must be a non-empty array" }
        val actions = actionsNode.map {
            val item = objectMapper.treeToValue(it, ActionItemDto::class.java)
            require(item.jsonrpc == "2.0") { "ACTION jsonrpc must be 2.0" }
            require(item.method.isNotBlank()) { "ACTION method is required" }
            item
        }
        val qaTry = tryRepository.findById(qaTryId)
            ?.takeIf { it.status == "STARTING" || it.status == "RUNNING" }
            ?: throw IllegalStateException("QA try is not active")

        val rejections = functionRejections(qaTry.runConfig.asString(), actions)
        if (rejections.isNotEmpty()) {
            // SDK 로 보내지 않는다. 배치 전체를 거절하고, 이유를 agent 에게 `ACTION_RESULT` 실패로 돌려준다.
            // 던지지 않는 것은 던진 예외가 수신 체인에서 삼켜져 agent 가 답을 기다리다 멈추기 때문이다.
            val inbound = logService.append(
                qaTryId = qaTryId,
                direction = "AGENT_TO_ORCHE",
                type = "ACTION",
                messageId = agentMessageId,
                message = message,
                payload = actionsPayload
            )
            logService.publish(inbound)
            if (inbound.inserted) sdkBridge.rejectActions(qaTry, agentMessageId, actions, rejections)
            return false
        }

        try {
            // inbound append + outbound append + SDK 전송을 한 트랜잭션으로 묶는다. 원자화 전에는
            // inbound만 커밋되고 outbound 전에 죽으면, Agent 재시도가 !inserted로 걸려 ACTION이
            // 영영 안 나가고 무음 삼켜졌다. 이제 실패 시 전부 롤백돼 재시도가 깨끗이 다시 보낸다.
            return transactionalOperator.executeAndAwait {
                val inbound = logService.append(
                    qaTryId = qaTryId,
                    direction = "AGENT_TO_ORCHE",
                    type = "ACTION",
                    messageId = agentMessageId,
                    message = message,
                    payload = actionsPayload
                )
                if (!inbound.inserted) return@executeAndAwait false
                val outbound = insertOutbound(qaTryId, agentMessageId, message, actionsPayload)
                val outboundResponse = logService.response(outbound)
                val outerId = requireNotNull(outbound.id)
                sessionManager.sendAction(
                    qaTry.gameInstanceId.toString(),
                    ActionResponseDto(id = outerId, actions = actions)
                )
                logService.publish(inbound)
                logService.publish(QaLogAppendResult(outboundResponse, true))
                true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // ERROR 감사 로그는 트랜잭션 밖에서 남긴다. 안에서 남기면 롤백에 함께 지워진다.
            val audit = logService.append(
                qaTryId = qaTryId,
                direction = "ORCHE_INTERNAL",
                type = "ERROR",
                correlationId = agentMessageId,
                message = "SDK action delivery failed.",
                payload = objectMapper.createObjectNode().put("error", error.message)
            )
            logService.publish(audit)
            throw error
        }
    }

    /**
     * `invoke_cheat` 와 `start_behavior` 중 이 try 의 허용 목록에 없는 이름을 부르는 항목을 골라 이유를 단다.
     *
     * 허용 목록은 세션을 열 때 agent 에게 실제로 보낸 이름들이다(`run_config.effective_cheats`, `effective_behaviors`).
     * agent 가 받은 적 없는 이름을 부르면 환각이거나 `cheatMode = off` 를 우회하려는 호출이므로 SDK 에 닿기 전에 막는다.
     * `stop_behavior` 와 `extend_behavior` 는 이름을 싣지 않고 이미 시작한 id 만 가리키므로 검사하지 않는다.
     *
     * @return `ACTION` 항목의 `id` 에서 거절 이유로 가는 맵. 비어 있으면 모두 통과다.
     */
    private fun functionRejections(runConfigJson: String, actions: List<ActionItemDto>): Map<Long, String> {
        val rejections = LinkedHashMap<Long, String>()
        var allowList: QaFunctionAllowList? = null
        for (action in actions) {
            val kind = when (action.method) {
                INVOKE_CHEAT_METHOD -> "cheat"
                START_BEHAVIOR_METHOD -> "behavior"
                else -> continue
            }
            val list = allowList ?: QaFunctionAllowList.of(runConfigJson, objectMapper).also { allowList = it }
            val params = objectMapper.valueToTree<JsonNode>(action.params)
            val name = params.path(0).path("name").takeIf { it.isTextual }?.asText()
            val allowed = if (kind == "cheat") list.effectiveCheats else list.effectiveBehaviors
            when {
                name.isNullOrBlank() -> rejections[action.id] = "bad argument name: ${action.method} needs params[0].name"
                name !in allowed -> rejections[action.id] =
                    "$kind not allowed in this run: $name. Allowed: ${allowed.joinToString(", ").ifEmpty { "none" }}"
            }
        }
        return rejections
    }

    private suspend fun insertOutbound(
        qaTryId: Long,
        correlationId: String,
        message: String?,
        payload: JsonNode
    ): QaLogEntity {
        val id = databaseClient.sql(
            """
            WITH allocated AS (SELECT nextval(pg_get_serial_sequence('qa_log', 'id')) AS id)
            INSERT INTO qa_log (
                id, qa_try_id, message_id, correlation_id, direction, type, message, payload
            )
            SELECT id, :qaTryId, CAST(id AS VARCHAR), :correlationId,
                   'ORCHE_TO_SDK', 'ACTION', :message, CAST(:payload AS JSONB)
            FROM allocated
            RETURNING id
            """.trimIndent()
        )
            .bind("qaTryId", qaTryId)
            .bind("correlationId", correlationId)
            .let { spec ->
                if (message == null) spec.bindNull("message", String::class.java)
                else spec.bind("message", message)
            }
            .bind("payload", objectMapper.writeValueAsString(payload))
            .map { row -> requireNotNull(row.get("id", Long::class.javaObjectType)) }
            .one()
            .awaitSingle()
        return requireNotNull(logRepository.findById(id))
    }
}
