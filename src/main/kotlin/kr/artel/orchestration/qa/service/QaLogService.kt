package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.qa.dto.QaLogPageResponse
import kr.artel.orchestration.qa.dto.QaLogResponse
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

private const val MAX_QA_PAYLOAD_BYTES = 1024 * 1024
private val DIRECTIONS = setOf("AGENT_TO_ORCHE", "ORCHE_TO_AGENT", "ORCHE_TO_SDK", "SDK_TO_ORCHE", "ORCHE_INTERNAL")
private val TYPES = setOf("LOG", "ACTION", "ACTION_RESULT", "GAME_STATE", "STATUS", "ERROR")

data class QaLogAppendResult(val log: QaLogResponse, val inserted: Boolean)

@Service
class QaLogService(
    private val repository: QaLogRepository,
    private val objectMapper: ObjectMapper,
    private val streamManager: QaLogStreamManager
) {
    fun append(
        qaTryId: Long,
        direction: String,
        type: String,
        messageId: String? = null,
        correlationId: String? = null,
        message: String? = null,
        payload: JsonNode = objectMapper.createObjectNode()
    ): Mono<QaLogAppendResult> {
        require(direction in DIRECTIONS) { "Unsupported QA log direction: $direction" }
        require(type in TYPES) { "Unsupported QA log type: $type" }
        val serialized = objectMapper.writeValueAsString(payload)
        require(serialized.toByteArray(StandardCharsets.UTF_8).size <= MAX_QA_PAYLOAD_BYTES) {
            "QA log payload exceeds 1 MiB"
        }
        val entity = QaLogEntity(
            qaTryId = qaTryId,
            messageId = messageId,
            correlationId = correlationId,
            direction = direction,
            type = type,
            message = message,
            payload = Json.of(serialized)
        )
        return repository.save(entity)
            .map { QaLogAppendResult(it.toResponse(), true) }
            .onErrorResume(DataIntegrityViolationException::class.java) { error ->
                if (messageId == null) Mono.error(error)
                else repository.findByQaTryIdAndDirectionAndMessageId(qaTryId, direction, messageId)
                    .map { existing -> QaLogAppendResult(existing.toResponse(), false) }
                    .switchIfEmpty(Mono.error(error))
            }
    }

    /** Call only after the transaction containing the append has committed. */
    fun publish(result: QaLogAppendResult) {
        if (result.inserted) streamManager.publish(result.log)
    }

    fun page(qaTryId: Long, beforeId: Long?, size: Int): Mono<QaLogPageResponse> =
        repository.findPage(qaTryId, beforeId, size + 1)
            .collectList()
            .map { selected ->
                val hasMore = selected.size > size
                val page = selected.take(size).reversed().map { it.toResponse() }
                QaLogPageResponse(
                    items = page,
                    nextBeforeId = if (hasMore) page.firstOrNull()?.id else null,
                    hasMore = hasMore
                )
            }

    fun stream(qaTryId: Long, afterId: Long, terminal: Boolean): Flux<QaLogResponse> =
        Flux.defer {
            if (terminal) {
                return@defer repository.findHighWater(qaTryId)
                    .flatMapMany { highWater ->
                        repository.findReplay(qaTryId, afterId, highWater).map { it.toResponse() }
                    }
            }
            val cursor = AtomicLong(afterId)
            Flux.interval(Duration.ZERO, Duration.ofMillis(250))
                .concatMap {
                    repository.findHighWater(qaTryId).flatMapMany { highWater ->
                        if (highWater <= cursor.get()) Flux.empty()
                        else repository.findReplay(qaTryId, cursor.get(), highWater)
                            .map { it.toResponse() }
                            .doOnNext { cursor.set(it.id.toLong()) }
                    }
                }
                .takeUntil { log ->
                    log.type == "STATUS" &&
                        log.payload.path("status").asText() in setOf("COMPLETED", "FAILED", "CANCELLED")
                }
        }

    fun response(entity: QaLogEntity): QaLogResponse = entity.toResponse()

    private fun QaLogEntity.toResponse(): QaLogResponse =
        QaLogResponse(
            id = requireNotNull(id).toString(),
            qaTryId = qaTryId.toString(),
            messageId = messageId,
            correlationId = correlationId,
            direction = direction,
            type = type,
            message = message,
            payload = objectMapper.readTree(payload.asString()),
            createdAt = requireNotNull(createdAt)
        )
}
