package kr.artel.orchestration.contentmap.capture

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.qa.repository.QaLogRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * `ACTION_RESULT` 중 **우리가 청구한 화면 `capture` 의 결과만** 집어 `screen` 행에 묶는다 (ARTEL-456).
 *
 * ## 가르는 축이 번호인 이유
 *
 * `ScanResultRouter` 는 `action` 이름으로 가른다. 여기서는 그럴 수 없다 — **agent 도
 * `capture_screen` 을 보내기 때문에**, 이름으로 가르면 agent 가 시킨 `capture` 의 결과를 가로채
 * agent 의 vision 이 조용히 멎는다.
 *
 * 그래서 [PendingScreenCaptureRegistry] 에 우리가 넣어 둔 번호의 프레임만 claim 한다. 그 번호는
 * `qa_log` 시퀀스에서 뽑아 어떤 `qa_log.id` 와도 겹치지 않으므로
 * ([ScreenCaptureService.nextActionId]), agent 의 결과가 여기 걸릴 수 없다.
 *
 * [handle] 은 **우리 청구가 아닌 모든 프레임에 대해 `false` 이고 아무것도 하지 않는다.** 파싱
 * 실패조차 `false` 다 — 이 분기가 QA 쪽 동작을 한 글자도 바꾸지 않게 하려는 것이다.
 *
 * ## objectKey 를 다시 만들지 않는다
 *
 * SDK 는 이미 `QaCaptureService.issueTicket` 에서 ticket 을 받아 갔고, 그때 `SCREENSHOT` 행의
 * payload 에 `objectKey` 가 적혔다. `captureId` 로 그 행을 되짚어 읽는다 — key 규칙을 여기서
 * 다시 조립하면 그 규칙이 두 곳이 되고, 한쪽만 바뀌는 날 화면이 없는 파일을 가리킨다.
 */
@Service
class ScreenCaptureResultRouter(
    private val pending: PendingScreenCaptureRegistry,
    private val screens: ScreenRepository,
    private val qaLogs: QaLogRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(ScreenCaptureResultRouter::class.java)

    /**
     * @return 이 프레임이 우리가 청구한 `capture` 의 결과여서 여기서 처리했으면 true. 그 밖에는
     *   전부 false 이고, 호출자가 지금까지처럼 QA 브리지로 넘긴다.
     */
    suspend fun handle(gameInstanceId: Long, payloadText: String): Boolean {
        val payload = runCatching { objectMapper.readTree(payloadText) }.getOrNull() ?: return false
        val requestId = payload.path(REQUEST_ID_FIELD).takeIf { it.isIntegralNumber }?.longValue()
            ?: return false
        val capture = pending.claim(requestId, gameInstanceId) ?: return false

        attach(capture, resultOf(payload))
        return true
    }

    /**
     * 결과 한 건을 화면에 묶는다. 어느 갈래로 끝나든 `screen` 행은 그대로 남는다.
     *
     * **`capture` 실패가 화면을 지우지 않는다.** 그림 없는 화면이 화면 없는 지도보다 낫고, 그림이
     * 왜 없는지는 이 로그가 답한다.
     */
    private suspend fun attach(capture: PendingScreenCapture, result: JsonNode?) {
        if (result == null || !result.path(SUCCESS_FIELD).asBoolean(false)) {
            // 게임이 못 찍었다고 답했다. `capture_screen` 을 모르는 빌드면 여기로 온다
            // ("Unsupported method"). 화면 행은 그대로 두고 사실만 남긴다.
            logger.warn(
                "화면 capture 가 실패했다 [screenId={}]: {}",
                capture.screenId,
                result?.path(ERROR_FIELD)?.asText(null) ?: "게임이 결과를 싣지 않았다",
            )
            return
        }

        val captureId = result.path(RETURN_VALUE_FIELD).path(CAPTURE_ID_FIELD)
            .asText(null)?.takeIf { it.isNotBlank() }
        if (captureId == null) {
            logger.warn("화면 capture 결과에 captureId 가 없다 [screenId={}]", capture.screenId)
            return
        }

        val objectKey = objectKeyOf(capture.qaTryId, captureId)
        if (objectKey == null) {
            // ticket 을 받아 간 흔적이 없다. 그 행이 곧 이미지가 어디 있는지 아는 유일한 자리라,
            // 없으면 묶을 값이 없다.
            logger.warn(
                "화면 capture 의 SCREENSHOT 행을 찾지 못했다 [screenId={}, captureId={}]",
                capture.screenId, captureId,
            )
            return
        }

        val attached = screens.attachImageIfAbsent(capture.screenId, objectKey.key, objectKey.capturedAt)
        if (attached == 0L) {
            // 이미 그림이 있다. 청구는 화면을 앉힐 때 한 번뿐이지만, 서버가 둘이면 각자 한 번씩
            // 청구할 수 있다. **처음 것을 지킨다** — 화면이 무엇인지 말하는 그림은 처음 것이다.
            logger.info("이미 그림이 있는 화면이라 두지 않는다 [screenId={}]", capture.screenId)
            return
        }
        logger.info(
            "화면에 capture 를 묶었다 [screenId={}, objectKey={}]",
            capture.screenId, objectKey.key,
        )
    }

    /**
     * 프레임에서 결과 항목 하나를 고른다.
     *
     * `results[]` 를 먼저 본다. SDK 의 `ACTION_RESULT` 는 결과를 배열로 싣고, 최상위에는 `action`
     * 도 `success` 도 없다(`ScanResultRouter` 가 그 모양에 한 번 데었다). 이 프레임은 번호로 이미
     * 우리 것이므로, `capture_screen` 항목이 없으면 첫 항목을 쓴다 — `action` 이름을 되돌려주지 않는
     * SDK 에서도 답을 잃지 않는다.
     */
    private fun resultOf(payload: JsonNode): JsonNode? {
        val results = payload.path(RESULTS_FIELD)
        if (!results.isArray || results.isEmpty) {
            return payload.takeIf { it.has(SUCCESS_FIELD) }
        }
        return results.firstOrNull {
            it.path(ACTION_FIELD).asText(null) == ScreenCaptureService.CAPTURE_SCREEN
        } ?: results.first()
    }

    /**
     * `captureId` 가 가리키는 이미지의 자리와 시각.
     *
     * `SCREENSHOT` 행의 `created_at` 을 시각으로 쓴다. 그것이 SDK 가 그림을 만들어 ticket 을 받은
     * 순간이고, 결과 프레임이 서버에 닿은 시각보다 실제 촬영에 가깝다.
     */
    private suspend fun objectKeyOf(qaTryId: Long, captureId: String): CapturedImage? {
        val log = qaLogs.findByQaTryIdAndDirectionAndMessageId(qaTryId, SDK_TO_ORCHE, captureId)
            ?: return null
        val key = runCatching { objectMapper.readTree(log.payload.asString()) }.getOrNull()
            ?.path(OBJECT_KEY_FIELD)?.asText(null)?.takeIf { it.isNotBlank() }
            ?: return null
        return CapturedImage(key, log.createdAt ?: clock.instant())
    }

    private data class CapturedImage(val key: String, val capturedAt: Instant)

    private companion object {
        const val REQUEST_ID_FIELD = "requestId"
        const val RESULTS_FIELD = "results"
        const val ACTION_FIELD = "action"
        const val SUCCESS_FIELD = "success"
        const val ERROR_FIELD = "error"
        const val RETURN_VALUE_FIELD = "returnValue"
        const val CAPTURE_ID_FIELD = "captureId"
        const val OBJECT_KEY_FIELD = "objectKey"
        const val SDK_TO_ORCHE = "SDK_TO_ORCHE"
    }
}
