package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.qa.dto.QaLogPageResponse
import kr.artel.orchestration.qa.dto.QaLogResponse
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant

private const val MAX_QA_PAYLOAD_BYTES = 1024 * 1024
private val DIRECTIONS = setOf(
    "AGENT_TO_ORCHE",
    "ORCHE_TO_AGENT",
    "ORCHE_TO_SDK",
    "SDK_TO_ORCHE",
    "ORCHE_INTERNAL",
    "USER_TO_ORCHE"
)
/**
 * 적재를 허용하는 타입.
 *
 * **게이트가 둘이다.** 여기의 `require` 와 `qa_log_type_check` 제약이 같은 목록을 각자 들고
 * 있고, 한쪽만 열면 통과한 값이 INSERT 에서 죽는다. 두 목록이 어긋나면
 * [kr.artel.orchestration.qa.QaLogTypeGateParityTest] 가 실패한다 — `internal` 인 이유가
 * 그 테스트다.
 */
internal val TYPES =
    setOf(
        "LOG", "ACTION", "ACTION_RESULT", "GAME_STATE", "STATUS", "ERROR", "CHAT", "SCREENSHOT",
        // 판독(ARTEL-414). 전량이 실측 약 18 KB 라 다른 타입보다 무겁고, 이 행은 SSE 로도
        // 발행된다. 원문을 런 단위 스토리지로 옮기고 여기엔 도착 사실만 남기는 것이
        // ARTEL-449 의 몫이다 — 그때까지는 본문째 남는다.
        "PULSE"
    )

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
    suspend fun append(
        qaTryId: Long,
        direction: String,
        type: String,
        messageId: String? = null,
        correlationId: String? = null,
        message: String? = null,
        payload: JsonNode = objectMapper.createObjectNode()
    ): QaLogAppendResult {
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
        return try {
            QaLogAppendResult(repository.save(entity).toResponse(), true)
        } catch (error: DataIntegrityViolationException) {
            if (messageId == null) throw error
            val existing = repository.findByQaTryIdAndDirectionAndMessageId(qaTryId, direction, messageId)
                ?: throw error
            QaLogAppendResult(existing.toResponse(), false)
        }
    }

    /** Call only after the transaction containing the append has committed. */
    fun publish(result: QaLogAppendResult) {
        if (result.inserted) streamManager.publish(result.log)
    }

    suspend fun page(qaTryId: Long, beforeId: Long?, size: Int): QaLogPageResponse {
        val selected = repository.findPage(qaTryId, beforeId, size + 1).toList()
        val hasMore = selected.size > size
        val page = selected.take(size).reversed().map { it.toResponse() }
        return QaLogPageResponse(
            items = page,
            nextBeforeId = if (hasMore) page.firstOrNull()?.id else null,
            hasMore = hasMore
        )
    }

    fun stream(qaTryId: Long, afterId: Long, terminal: Boolean): Flow<QaLogResponse> =
        flow {
            if (terminal) {
                val highWater = repository.findHighWater(qaTryId)
                for (entity in repository.findReplay(qaTryId, afterId, highWater).toList()) {
                    emit(entity.toResponse())
                }
                return@flow
            }
            // Poll immediately, then every 250ms — the former Flux.interval(ZERO, 250ms).
            var cursor = afterId
            while (true) {
                val highWater = repository.findHighWater(qaTryId)
                if (highWater > cursor) {
                    for (entity in repository.findReplay(qaTryId, cursor, highWater).toList()) {
                        val response = entity.toResponse()
                        cursor = response.id.toLong()
                        emit(response)
                        // takeUntil(::isRunTerminal) was inclusive: emit the terminal
                        // frame, then stop polling.
                        if (isRunTerminal(response)) return@flow
                    }
                }
                delay(250)
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
