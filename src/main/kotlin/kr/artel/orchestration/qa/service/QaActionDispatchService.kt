package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.common.error.ApiException
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.sdk.service.SdkActionDelivery
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.reactive.socket.WebSocketSession

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
    private val sdkBridge: QaSdkBridgeService,
    private val delivery: SdkActionDelivery,
    private val inputPreflight: QaInputPreflight,
) {
    private val logger = LoggerFactory.getLogger(QaActionDispatchService::class.java)

    suspend fun dispatch(
        qaTryId: Long,
        agentMessageId: String,
        message: String?,
        actionsPayload: JsonNode
    ): Boolean {
        val deliveryDeadline = System.currentTimeMillis() + SdkActionDelivery.LIFETIME_MILLIS
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
        val runConfig = qaTry.runConfig.asString()
        val inputMode = inputModeOf(runConfig, objectMapper)
        val sdkAction = ActionResponseDto(id = 0, actions = actions, inputMode = inputMode.wire)
        val sdkPayload = objectMapper.valueToTree<JsonNode>(sdkAction)

        var rejections = functionRejections(runConfig, actions)
        var inputSession: WebSocketSession? = null
        if (rejections.isEmpty()) {
            try {
                // 재연결한 구 SDK가 os 필드를 무시해 virtual로 실행하지 않도록 session마다 다시 확인한다.
                inputSession = inputPreflight.requireAvailable(qaTry.gameInstanceId, inputMode)
            } catch (error: ApiException) {
                rejections = actions.associate { it.id to "OS input is unavailable: ${error.message}" }
            }
        }
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
            // 로그 둘을 먼저 commit한다. rollback된 요청이 복구 루프에서 실행되지 않도록
            // pending 등록과 최초 SDK 전송은 executeAndAwait가 반환된 뒤에만 수행한다.
            val stored = transactionalOperator.executeAndAwait {
                val inbound = logService.append(
                    qaTryId = qaTryId,
                    direction = "AGENT_TO_ORCHE",
                    type = "ACTION",
                    messageId = agentMessageId,
                    message = message,
                    payload = actionsPayload
                )
                if (!inbound.inserted) return@executeAndAwait null
                val outbound = insertOutbound(qaTryId, agentMessageId, message, sdkPayload)
                val outboundResponse = logService.response(outbound)
                Triple(inbound, outboundResponse, requireNotNull(outbound.id))
            } ?: return false
            val (inbound, outboundResponse, outerId) = stored
            logService.publish(inbound)
            logService.publish(QaLogAppendResult(outboundResponse, true))
            val instanceId = qaTry.gameInstanceId.toString()
            val pendingAhead = sessionManager.pendingOutbound(instanceId)
            val registered = delivery.send(
                instanceId, qaTryId, sdkAction.copy(id = outerId), deliveryDeadline, expectedSession = inputSession,
            )
            logger.info(
                "SDK ACTION 전달 등록 [qaTryId={}, requestId={}, registered={}, pendingAhead={}]",
                qaTryId, outerId, registered, pendingAhead
            )
            if (!registered) throw SdkActionUndeliveredException()
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: SdkActionUndeliveredException) {
            // DB 로그는 이미 commit됐다. 닫힌 전송 큐의 요청은 복구 대상으로 등록하지 않고 실패를 돌려준다.
            val audit = logService.append(
                qaTryId = qaTryId,
                direction = "ORCHE_INTERNAL",
                type = "ERROR",
                correlationId = agentMessageId,
                message = "SDK action delivery failed.",
                payload = objectMapper.createObjectNode().put("error", error.message)
            )
            logService.publish(audit)
            sdkBridge.rejectActions(qaTry, agentMessageId, actions, actions.associate { it.id to UNDELIVERED_REASON })
            return false
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
                   'ORCHE_TO_SDK', 'ACTION', :message, CAST(:payload AS JSONB) || jsonb_build_object('id', id)
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

/** 세션은 있는데 전송 큐가 닫혀 ACTION 을 SDK 로 보내지 못했다. */
private class SdkActionUndeliveredException :
    IllegalStateException("SDK send queue is closed; the action was not sent to the game")

/** 큐가 닫혀 보내지 못한 ACTION 에 agent 가 받는 이유. agent 가 읽으므로 영어다. */
private const val UNDELIVERED_REASON =
    "not delivered: the game connection is not accepting messages; the game did not receive this action"
