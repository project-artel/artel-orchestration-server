package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.util.UUID

private val SUPPORTED_TYPES = setOf("LOG", "ACTION", "STATUS", "ERROR", "CHAT")

@Service
class QaAgentInboundRouter(
    private val tryRepository: QaTryRepository,
    private val logService: QaLogService,
    private val actionDispatch: QaActionDispatchService,
    private val streamManager: QaLogStreamManager,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    fun handle(envelope: QaAgentEnvelope): Mono<Void> {
        // 파싱은 동기 throw 대신 값으로 검증한다. 프레임 하나가 throw하면 receive 파이프라인이
        // onError로 끊겨 WS가 닫히고, 그게 onDisconnect로 이어져 try 전체가 fail 처리된다.
        val qaTryId = parseId(envelope.qaTryId) ?: return Mono.empty()
        if (!isUuid(envelope.messageId)) {
            return appendError(qaTryId, envelope, "Agent messageId must be a UUID")
        }
        if (envelope.type !in SUPPORTED_TYPES) {
            return appendError(qaTryId, envelope, "Unsupported Agent message type: ${envelope.type}")
        }
        val message = envelope.payload.path("message").takeIf { it.isTextual }?.asText()
        if (message.isNullOrBlank()) {
            return appendError(qaTryId, envelope, "${envelope.type} payload.message is required")
        }
        // A frame for an unknown/already-finished try is dropped, not raised: an error
        // here propagates out of the WebSocket receive chain, which closes the socket
        // and fails the whole run via onDisconnect.
        return tryRepository.findById(qaTryId)
            .filter { it.status == "STARTING" || it.status == "RUNNING" }
            .flatMap { qaTry ->
                when (envelope.type) {
                    "ACTION" -> actionDispatch.dispatch(
                        qaTryId,
                        envelope.messageId,
                        message,
                        envelope.payload
                    ).then()
                    "STATUS" -> routeStatus(qaTry.status, qaTryId, envelope, message)
                    else -> logService.append(
                        qaTryId = qaTryId,
                        direction = "AGENT_TO_ORCHE",
                        type = envelope.type,
                        messageId = envelope.messageId,
                        correlationId = envelope.correlationId,
                        message = message,
                        payload = envelope.payload
                    ).doOnNext(logService::publish).then()
                }
            }
    }

    private fun routeStatus(
        currentStatus: String,
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        message: String
    ): Mono<Void> {
        // Agent STATUS is 2-scope: per-step frames reuse COMPLETED/FAILED for the step's
        // own verdict and carry result=null — they must NOT end the run. Only a
        // run-terminal frame carries result PASSED|FAILED, and CANCELLED is always
        // terminal. Key on result, never on the status word alone.
        val status = envelope.payload.path("status").takeIf { it.isTextual }?.asText()
        val result = envelope.payload.path("result").takeIf { it.isTextual }?.asText()
        val resolved = when {
            status == "CANCELLED" -> "CANCELLED"
            result == "PASSED" -> "COMPLETED"
            result == "FAILED" -> "FAILED"
            else -> null
        }
        val completedAt = if (resolved != null) Instant.now(clock) else null
        val transition = if (resolved != null) {
            tryRepository.transition(qaTryId, currentStatus, resolved, completedAt, completedAt!!)
                .filter { it == 1 }
                .switchIfEmpty(Mono.error(IllegalStateException("Illegal QA status transition")))
                .then()
        } else Mono.empty()
        return transition.then(
            logService.append(
                qaTryId = qaTryId,
                direction = "AGENT_TO_ORCHE",
                type = "STATUS",
                messageId = envelope.messageId,
                correlationId = envelope.correlationId,
                message = message,
                payload = if (resolved != null) {
                    // Keep the agent's rich terminal payload (result, summary) but stamp
                    // the resolved try status + completion time for downstream readers.
                    (envelope.payload.deepCopy() as ObjectNode)
                        .put("status", resolved)
                        .put("completedAt", completedAt.toString())
                } else envelope.payload
            )
        ).doOnNext {
            logService.publish(it)
            if (resolved != null) streamManager.complete(qaTryId)
        }.then()
    }

    private fun appendError(
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        reason: String
    ): Mono<Void> =
        logService.append(
            qaTryId = qaTryId,
            direction = "ORCHE_INTERNAL",
            type = "ERROR",
            correlationId = envelope.messageId,
            message = reason,
            payload = objectMapper.createObjectNode().put("reason", reason)
        ).doOnNext(logService::publish).then()

    private fun parseId(value: String): Long? =
        value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()

    private fun isUuid(value: String): Boolean =
        try {
            UUID.fromString(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
}
