package kr.artel.orchestration.qa.state

import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * `STATE_SET` 이 `seq` 의 빈자리를 보면 SDK 에 whole 을 청한다(ARTEL-1300).
 *
 * 차이만 나르는 `pulse` 는 놓친 하나를 아무도 다시 말해 주지 않는다. SDK 의 `resync_readings` 는 다음 판독을 whole 로
 * 만든다. 실패는 삼킨다 — 청하지 못해도 상태는 `AWAITING_WHOLE` 로 남아 아무도 틀린 상태로 행동하지 않고, 씬이 바뀌거나
 * 감시가 다시 켜지면 whole 이 온다.
 */
@Component
class StateResyncRequester(private val sessionManager: SessionManager) {

    private val logger = LoggerFactory.getLogger(StateResyncRequester::class.java)

    suspend fun request(gameInstanceId: Long) {
        val requestId = REQUEST_IDS.incrementAndGet()
        try {
            sessionManager.sendAction(
                gameInstanceId.toString(),
                ActionResponseDto(
                    id = requestId,
                    actions = listOf(ActionItemDto(id = requestId, method = RESYNC_READINGS, params = emptyList())),
                ),
            )
            logger.info("STATE_SET 누락으로 resync 요청 [gameInstanceId={}, requestId={}]", gameInstanceId, requestId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn("resync 요청을 보내지 못했다 [gameInstanceId={}]: {}", gameInstanceId, failure.message, failure)
        }
    }

    private companion object {
        const val RESYNC_READINGS = "resync_readings"

        /** 프로세스 안에서만 뜻이 있는 번호. `QaReadingsService` 와 같은 이유로 짝을 맞추는 데 쓰지 않는다. */
        val REQUEST_IDS = AtomicLong(2_000_000_000L)
    }
}
