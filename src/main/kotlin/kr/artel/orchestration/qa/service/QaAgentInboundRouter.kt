package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.util.UUID

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
        val qaTryId = parseId(envelope.qaTryId)
        UUID.fromString(envelope.messageId)
        if (envelope.type !in setOf("LOG", "ACTION", "STATUS", "ERROR")) {
            return appendError(qaTryId, envelope, "Unsupported Agent message type: ${envelope.type}")
        }
        val message = envelope.payload.path("message").takeIf { it.isTextual }?.asText()
        if (message.isNullOrBlank()) {
            return appendError(qaTryId, envelope, "${envelope.type} payload.message is required")
        }
        return tryRepository.findById(qaTryId)
            .filter { it.status == "STARTING" || it.status == "RUNNING" }
            .switchIfEmpty(Mono.error(IllegalStateException("QA try is not active")))
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
        val requested = envelope.payload.path("status").takeIf { it.isTextual }?.asText()
        val terminal = requested in setOf("COMPLETED", "FAILED", "CANCELLED")
        val completedAt = if (terminal) Instant.now(clock) else null
        val transition = if (terminal) {
            tryRepository.transition(qaTryId, currentStatus, requested!!, completedAt, completedAt!!)
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
                payload = if (terminal) {
                    objectMapper.createObjectNode()
                        .put("message", message)
                        .put("status", requested)
                        .put("completedAt", completedAt.toString())
                } else envelope.payload
            )
        ).doOnNext {
            logService.publish(it)
            if (terminal) streamManager.complete(qaTryId)
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

    private fun parseId(value: String): Long =
        value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
            ?: throw IllegalArgumentException("qaTryId must be a decimal string")
}
