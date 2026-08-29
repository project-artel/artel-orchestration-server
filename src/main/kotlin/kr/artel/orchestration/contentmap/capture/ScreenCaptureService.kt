package kr.artel.orchestration.contentmap.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 화면을 처음 앉힌 자리에서 **그 화면의 그림을 요청한다** (ARTEL-456).
 *
 * ```
 * screen 을 새로 insert → capture_screen action → SDK
 *                          SDK 가 ticket 을 받아(QaCaptureService) S3 에 직접 올리고
 *                       ← ACTION_RESULT { requestId, returnValue.captureId }
 *                          → ScreenCaptureResultRouter 가 screen 행에 묶는다
 * ```
 *
 * ## 왜 orchestration 이 직접 보내는가
 *
 * SDK socket 을 잡고 있는 것도([SessionManager]), `action` 을 내보내는 것도, `screen capture` 를 저장하는
 * 것도(`QaCaptureService`) orchestration 이다. 한때 agent 를 거치는 설계였고 구현까지 됐으나
 * (ARTEL-595), 그 왕복 사이에 agent 의 판단이 하나도 없었다 — "찍어라" 가 전부다. frame 두 개와
 * 왕복 한 번, 그리고 agent 가 죽으면 그림도 못 찍는 의존성만 남아서 닫았다.
 *
 * agent 의 `screen capture` 예산도 여기서 사라진다. 그 예산은 agent 가 **자기 도구 호출**을 세는 것이고
 * orchestration 은 그 장부에 없다.
 *
 * ## pulse 를 막지 않는다
 *
 * `SessionManager.send` 는 "보냈다" 가 아니라 "보낼 줄에 세웠다" 이다(그 KDoc 그대로). 그래서
 * 이 함수는 왕복을 기다리지 않고, 시간이 걸리는 쪽인 **결과**는 나중에 별도 `ACTION_RESULT`
 * frame 으로 와서 [ScreenCaptureResultRouter] 가 받는다. 요청과 결과가 이미 다른 frame 이라
 * background task 도 queue 도 필요 없다.
 *
 * 새 화면 하나당 늘어나는 동기 작업은 질의 두 번(활성 try 조회, 번호 발급)이다. **새 화면일 때만**
 * 부르므로 pulse 마다 드는 비용이 아니다.
 *
 * ## 실패를 삼킨다
 *
 * `screen capture` 를 못 찍었다고 화면 관측이 끊기면 안 된다. **그림 없는 화면이 화면 없는 지도보다 낫다** —
 * `ScreenObservationService` 가 `pulse` 적재 실패를 삼키는 것과 같은 판단이다.
 */
@Service
class ScreenCaptureService(
    private val qaTries: QaTryRepository,
    private val sessionManager: SessionManager,
    private val pending: PendingScreenCaptureRegistry,
    private val databaseClient: DatabaseClient,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(ScreenCaptureService::class.java)

    /**
     * 이 화면의 그림을 요청한다. **화면을 새로 앉혔을 때만 부른다.**
     *
     * 다시 본 화면에는 부르지 않는다 — 처음 만나 화면이라고 판정한 순간의 그림이 그 화면이
     * 무엇인지 말하는 그림이고, 재방문마다 찍으면 그 뜻이 사라진다. 그 판정은
     * `ScreenRepository.observe` 의 `inserted` 가 한다.
     */
    suspend fun request(gameInstanceId: Long, screenId: Long) {
        try {
            dispatch(gameInstanceId, screenId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // 삼키되 조용하지는 않게. 화면에 그림이 안 붙을 때 사람이 볼 유일한 자리다.
            logger.warn(
                "화면 capture 를 요청하지 못했다 [gameInstanceId={}, screenId={}]: {}",
                gameInstanceId, screenId, failure.message, failure,
            )
        }
    }

    private suspend fun dispatch(gameInstanceId: Long, screenId: Long) {
        // 붙어 있지 않으면 보낼 곳이 없다. `SessionManager.sendRaw` 가 던지는 것을 잡아 로그로
        // 남기는 것보다, 붙었는지를 먼저 묻는 편이 그 상황을 오류로 부르지 않는다.
        if (!sessionManager.hasSession(gameInstanceId.toString())) return

        // 활성 try 가 없으면 SDK 가 ticket 을 못 받는다(`QaCaptureService.issueTicket` 이 409).
        // 같은 조회를 먼저 해 두면 답이 정해진 왕복을 만들지 않고, 결과를 묶을 때 쓸 try 도
        // 이 시점에 못 박힌다.
        val qaTryId = qaTries.findActiveByGameInstanceId(gameInstanceId)?.id ?: return

        val requestId = nextActionId()
        // 보내기 **전에** 넣는다. 뒤에 두면 답이 먼저 도착한 프레임을 우리 것으로 알아보지 못한다.
        pending.put(
            PendingScreenCapture(
                requestId = requestId,
                screenId = screenId,
                qaTryId = qaTryId,
                gameInstanceId = gameInstanceId,
                requestedAt = Instant.now(clock),
            )
        )
        sessionManager.sendAction(
            gameInstanceId.toString(),
            ActionResponseDto(
                id = requestId,
                actions = listOf(ActionItemDto(id = requestId, method = CAPTURE_SCREEN, params = emptyList())),
            ),
        )
        logger.info(
            "화면 capture 요청 [gameInstanceId={}, screenId={}, requestId={}]",
            gameInstanceId, screenId, requestId,
        )
    }

    /**
     * 이 요청의 바깥 `action` 번호를 **`qa_log` 의 시퀀스에서** 뽑는다. 행은 만들지 않는다.
     *
     * ## 왜 프로세스 안의 카운터가 아닌가
     *
     * 결과를 가르는 축이 이 번호이기 때문이다. `ContentMapScanService` 는 `action` 이름
     * (`scan_evidence`)으로 갈라도 됐지만 여기는 그럴 수 없다 — **agent 도 `capture_screen` 을
     * 보낸다.** 이름으로 가르면 agent 가 시킨 `screen capture` 의 결과를 가로채 agent 의 vision 이 멎는다.
     *
     * 그래서 번호로 가르는데, agent 가 보낸 `action` 의 바깥 번호는 `qa_log.id` 다
     * (`QaActionDispatchService.insertOutbound`). 프로세스 카운터를 따로 두면 그 값과 겹칠 수 있고,
     * 겹치는 순간 남의 결과를 우리 요청로 읽는다. 같은 발급기에서 뽑으면 겹칠 수가 없다.
     *
     * 행을 만들지 않아 번호에 구멍이 생기는 것은 그대로 값이다. 그 구멍이 곧 "이 번호는 우리 것"
     * 이라는 표시다.
     */
    private suspend fun nextActionId(): Long =
        databaseClient.sql("SELECT nextval(pg_get_serial_sequence('qa_log', 'id')) AS id")
            .map { row -> requireNotNull(row.get("id", java.lang.Long::class.java)).toLong() }
            .one()
            .awaitSingle()

    companion object {
        /** SDK 와 맞춘 action 이름. 파라미터가 비면 전체 화면이다. */
        const val CAPTURE_SCREEN = "capture_screen"
    }
}
