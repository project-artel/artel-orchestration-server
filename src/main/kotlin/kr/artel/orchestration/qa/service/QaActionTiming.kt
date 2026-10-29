package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** `qa_log` 의 `payload.event` 값. 액션 하나의 시각 기록 행을 찾는 키다. */
const val ACTION_TIMING_EVENT = "action_timing"

/**
 * 액션 하나가 거친 네 시각(ARTEL-1235). `ACTION_RESULT` 가 오면 `ORCHE_INTERNAL` `LOG` 한 줄로 남긴다.
 *
 * 2026-10-22 gyemong 런 1 에서 `press_key` 23회 중 18회가 결과 없이 끝났고, 온 결과는 30~120초 늦었다. 그때 남은
 * 것은 orchestration 의 두 시각뿐이라, 늦어진 자리가 이쪽 전송인지 SDK 의 수신인지 SDK 의 실행인지 가를 수 없었다.
 *
 * SDK 쪽 두 시각은 `ACTION_RESULT` 의 `receivedAt` 과 `completedAt` 에서 읽는다. epoch milliseconds 숫자나 ISO-8601
 * 문자열을 받는다. 그 필드를 싣지 않는 SDK 에서는 null 이다.
 *
 * @property orchestrationReceivedAt agent 의 `ACTION` 을 받은 시각(`AGENT_TO_ORCHE` 행)
 * @property sentToSdkAt SDK 로 보낸 `ACTION` 행이 쓰인 시각(`ORCHE_TO_SDK` 행). 그 트랜잭션이 시작한 시각이라 실제로 큐에
 *   넣은 때보다 수 ms 이르다
 * @property sdkReceivedAt SDK 가 `ACTION` 을 받은 시각
 * @property sdkCompletedAt SDK 가 배치를 끝내고 `ACTION_RESULT` 를 만든 시각
 * @property resultReceivedAt 이쪽이 `ACTION_RESULT` 를 받아 기록한 시각
 * @property sdkCompletedFrame `ACTION_RESULT` 의 `frame`. SDK 가 배치를 끝낸 frame 이다
 * @property sdkReceivedFrame `ACTION_RESULT` 의 `receivedFrame`
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class QaActionTiming(
    val event: String = ACTION_TIMING_EVENT,
    val requestId: String,
    val orchestrationReceivedAt: Instant?,
    val sentToSdkAt: Instant?,
    val sdkReceivedAt: Instant?,
    val sdkCompletedAt: Instant?,
    val resultReceivedAt: Instant,
    val sdkReceivedFrame: Long?,
    val sdkCompletedFrame: Long?,
    /** [orchestrationReceivedAt] 에서 [resultReceivedAt] 까지. */
    val totalMillis: Long?,
    /** [sentToSdkAt] 에서 [sdkReceivedAt] 까지. 이쪽 전송과 네트워크에서 걸린 시간이다. 두 시계가 다르면 틀어진다. */
    val deliveryMillis: Long?,
    /** [sdkReceivedAt] 에서 [sdkCompletedAt] 까지. SDK 의 큐 대기와 실행이다. 같은 SDK 시계라 정확하다. */
    val sdkMillis: Long?
) {
    companion object {
        fun of(
            requestId: String,
            orchestrationReceivedAt: Instant?,
            sentToSdkAt: Instant?,
            resultReceivedAt: Instant,
            resultPayload: JsonNode
        ): QaActionTiming {
            val sdkReceivedAt = sdkInstant(resultPayload.path("receivedAt"))
            val sdkCompletedAt = sdkInstant(resultPayload.path("completedAt"))
            return QaActionTiming(
                requestId = requestId,
                orchestrationReceivedAt = orchestrationReceivedAt,
                sentToSdkAt = sentToSdkAt,
                sdkReceivedAt = sdkReceivedAt,
                sdkCompletedAt = sdkCompletedAt,
                resultReceivedAt = resultReceivedAt,
                sdkReceivedFrame = resultPayload.path("receivedFrame").takeIf { it.isIntegralNumber }?.longValue(),
                sdkCompletedFrame = resultPayload.path("frame").takeIf { it.isIntegralNumber }?.longValue(),
                totalMillis = millisBetween(orchestrationReceivedAt, resultReceivedAt),
                deliveryMillis = millisBetween(sentToSdkAt, sdkReceivedAt),
                sdkMillis = millisBetween(sdkReceivedAt, sdkCompletedAt)
            )
        }

        private fun sdkInstant(node: JsonNode): Instant? = when {
            node.isIntegralNumber -> Instant.ofEpochMilli(node.longValue())
            node.isTextual -> try {
                Instant.parse(node.asText())
            } catch (_: DateTimeParseException) {
                null
            }
            else -> null
        }

        private fun millisBetween(from: Instant?, to: Instant?): Long? =
            if (from == null || to == null) null else Duration.between(from, to).toMillis()
    }

    /** 타임라인에 보일 한 줄. */
    fun summary(): String {
        val parts = listOfNotNull(
            totalMillis?.let { "total ${it}ms" },
            deliveryMillis?.let { "delivery ${it}ms" },
            sdkMillis?.let { "sdk ${it}ms" }
        )
        return "Action $requestId timing: ${parts.joinToString(", ").ifEmpty { "no timestamps" }}"
    }
}
