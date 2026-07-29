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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

private const val MAX_QA_PAYLOAD_BYTES = 1024 * 1024
private val DIRECTIONS = setOf(
    "AGENT_TO_ORCHE",
    "ORCHE_TO_AGENT",
    "ORCHE_TO_SDK",
    "SDK_TO_ORCHE",
    "ORCHE_INTERNAL",
    "USER_TO_ORCHE"
)
private val TYPES =
    setOf("LOG", "ACTION", "ACTION_RESULT", "GAME_STATE", "STATUS", "ERROR", "CHAT", "SCREENSHOT")

data class QaLogAppendResult(val log: QaLogResponse, val inserted: Boolean)

private val TERMINAL_STATUSES = setOf("COMPLETED", "FAILED", "CANCELLED")

/**
 * Whether a STATUS log ends the whole run.
 *
 * The status word alone is not enough: the Agent reuses COMPLETED/FAILED for a
 * single step's verdict. `completedAt` is the run-scoped marker — it is stamped
 * only by the terminal transition in [QaAgentInboundRouter] and by
 * [QaExecutionFailurePersistence], and is absent on per-step frames and null on
 * STARTING/RUNNING.
 */
fun isRunTerminal(log: QaLogResponse): Boolean =
    log.type == "STATUS" &&
        log.payload.path("status").asText() in TERMINAL_STATUSES &&
        log.payload.hasNonNull("completedAt")

@Service
class QaLogService(
    private val repository: QaLogRepository,
    private val objectMapper: ObjectMapper,
    private val streamManager: QaLogStreamManager,
    private val clock: Clock
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
            payload = Json.of(serialized),
            // Stamped here rather than left to the column default: R2DBC does not
            // read defaulted columns back after an insert, so the saved entity would
            // carry a null createdAt and every append would fail on the way out.
            createdAt = Instant.now(clock)
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
                .takeUntil(::isRunTerminal)
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
