package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono

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
    private val transactionalOperator: TransactionalOperator
) {
    fun dispatch(
        qaTryId: Long,
        agentMessageId: String,
        message: String?,
        actionsPayload: JsonNode
    ): Mono<Boolean> {
        require(agentMessageId.isNotBlank()) { "ACTION messageId is required" }
        val actionsNode = actionsPayload.path("actions")
        require(actionsNode.isArray && !actionsNode.isEmpty) { "ACTION payload.actions must be a non-empty array" }
        val actions = actionsNode.map {
            val item = objectMapper.treeToValue(it, ActionItemDto::class.java)
            require(item.jsonrpc == "2.0") { "ACTION jsonrpc must be 2.0" }
            require(item.method.isNotBlank()) { "ACTION method is required" }
            item
        }
        return tryRepository.findById(qaTryId)
            .filter { it.status == "STARTING" || it.status == "RUNNING" }
            .switchIfEmpty(Mono.error(IllegalStateException("QA try is not active")))
            .flatMap { qaTry ->
                // inbound append + outbound append + SDK 전송을 한 트랜잭션으로 묶는다. 원자화 전에는
                // inbound만 커밋되고 outbound 전에 죽으면, Agent 재시도가 !inserted로 걸려 ACTION이
                // 영영 안 나가고 무음 삼켜졌다. 이제 실패 시 전부 롤백돼 재시도가 깨끗이 다시 보낸다.
                val delivery = logService.append(
                    qaTryId = qaTryId,
                    direction = "AGENT_TO_ORCHE",
                    type = "ACTION",
                    messageId = agentMessageId,
                    message = message,
                    payload = actionsPayload
                ).flatMap { inbound ->
                    if (!inbound.inserted) return@flatMap Mono.just(false)
                    insertOutbound(qaTryId, agentMessageId, message, actionsPayload)
                        .flatMap { outbound ->
                            val outboundResponse = logService.response(outbound)
                            val outerId = requireNotNull(outbound.id)
                            sessionManager.sendAction(
                                qaTry.gameInstanceId.toString(),
                                ActionResponseDto(id = outerId, actions = actions)
                            ).thenReturn(outboundResponse)
                        }
                        .doOnNext { outbound ->
                            logService.publish(inbound)
                            logService.publish(QaLogAppendResult(outbound, true))
                        }
                        .thenReturn(true)
                }.`as`(transactionalOperator::transactional)

                // ERROR 감사 로그는 트랜잭션 밖에서 남긴다. 안에서 남기면 롤백에 함께 지워진다.
                delivery.onErrorResume { error ->
                    logService.append(
                        qaTryId = qaTryId,
                        direction = "ORCHE_INTERNAL",
                        type = "ERROR",
                        correlationId = agentMessageId,
                        message = "SDK action delivery failed.",
                        payload = objectMapper.createObjectNode().put("error", error.message)
                    ).doOnNext(logService::publish)
                        .then(Mono.error(error))
                }
            }
    }

    private fun insertOutbound(
        qaTryId: Long,
        correlationId: String,
        message: String?,
        payload: JsonNode
    ) = databaseClient.sql(
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
        .flatMap { logRepository.findById(it) }
}
