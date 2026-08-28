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
    private val actionObservations: QaActionObservationPort,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator
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
                // 나간 **뒤에** 알린다 (ARTEL-450). 앞에 두면 전송이 실패해 롤백된 액션이 관측
                // 타임라인에는 남아, 나가지도 않은 조작이 앞선 창의 배타성을 깬다.
                actionObservations.dispatched(qaTry.gameInstanceId, outerId, actions)
                logService.publish(inbound)
                logService.publish(QaLogAppendResult(outboundResponse, true))
                true
            } ?: false
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
            .map { row -> requireNotNull(row.get("id", java.lang.Long::class.java)).toLong() }
            .one()
            .awaitSingle()
        return requireNotNull(logRepository.findById(id))
    }
}
