package kr.artel.orchestration.qa.service

import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicLong

/**
 * 런이 판독을 켜고 끈다 (ARTEL-507).
 *
 * 판독 사슬은 세 레포에 다 들어와 있었지만(ARTEL-399 · 414 · 401) 한 줄도 흐르지 않았다.
 * `start_readings` 를 보내는 쪽이 orchestration · agent · home 어디에도 없었기 때문이다.
 * **이것이 그 보내는 쪽이다.**
 *
 * **연결이 아니라 런이 켜는 이유.** ARTEL-417 이 정했고 실측이 근거다 — 연결 시점은 전체 씬
 * 순회와 겹치고, 순회는 아무도 걸어간 적 없는 화면을 방문한다.
 *
 * ```
 * 순회 중 시작   8초에 125,548 바이트, 가 본 적 없는 세 화면
 * 순회 뒤 시작   전량 한 줄 4,369 바이트, 그 뒤 14초 침묵
 * ```
 *
 * **실패를 삼킨다.** 판독은 관측 채널이지 런의 전제가 아니다. 켜지 못했다고 런을 되돌리면,
 * 출시 빌드처럼 판독을 애초에 낼 수 없는 게임에서는 QA 자체가 시작되지 않는다.
 */
@Service
class QaReadingsService(
    private val sessionManager: SessionManager,
    private val tryRepository: QaTryRepository,
    private val runRepository: QaRunRepository,
) {
    private val logger = LoggerFactory.getLogger(QaReadingsService::class.java)

    /** 런이 시작했다. 이 인스턴스의 판독을 켠다. */
    suspend fun start(gameInstanceId: Long) {
        send(gameInstanceId, START_READINGS)
    }

    /**
     * 이 인스턴스에 살아 있는 시도도 런도 없을 때만 판독을 끈다.
     *
     * **판단을 한 곳에 모은 이유.** 시도가 종단되는 자리가 넷이다 — 정상 종단, 실패 두 경로,
     * 취소. 각자 "이제 꺼도 되나" 를 판단하면 넷이 어긋나고, 특히 시나리오가 여럿인 런에서는
     * 첫 시나리오가 끝나자마자 꺼져 나머지가 판독 없이 돈다. 그래서 호출부는 넷 다 이 한 줄이고,
     * 조건은 여기에만 있다.
     *
     * 멱등이다. 이미 꺼져 있어도 `stop_readings` 는 무해하므로, 종단 경로를 빠뜨려 판독이 새는
     * 것보다 의심스러운 자리에 넣는 편이 낫다.
     *
     * **트랜잭션 밖에서 불러야 한다.** 안에서 부르면 두 가지가 깨진다 — 소켓 전송이 도는 동안
     * DB 커넥션을 쥐고, 그리고 커밋 전이라 아래 조회가 방금 끝낸 시도를 아직 살아 있다고 읽어
     * **영영 끄지 못한다.**
     */
    suspend fun stopIfIdle(gameInstanceId: Long) {
        if (tryRepository.findActiveByGameInstanceId(gameInstanceId) != null) return
        if (runRepository.findActiveByGameInstanceId(gameInstanceId) != null) return
        send(gameInstanceId, STOP_READINGS)
    }

    private suspend fun send(gameInstanceId: Long, method: String) {
        val requestId = REQUEST_IDS.incrementAndGet()
        try {
            sessionManager.sendAction(
                gameInstanceId.toString(),
                ActionResponseDto(
                    id = requestId,
                    actions = listOf(ActionItemDto(id = requestId, method = method, params = emptyList())),
                ),
            )
            logger.info("판독 {} 요청 [gameInstanceId={}, requestId={}]", method, gameInstanceId, requestId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // 삼키되 조용하지는 않게. 판독이 안 나올 때 사람이 볼 유일한 자리다 — 켜는 요청이
            // 나갔는지조차 모르면 SDK 를 의심할지 여기를 의심할지 가릴 수 없다.
            logger.warn(
                "판독 {} 요청을 보내지 못했다 [gameInstanceId={}]: {}",
                method, gameInstanceId, failure.message, failure,
            )
        }
    }

    private companion object {
        const val START_READINGS = "start_readings"
        const val STOP_READINGS = "stop_readings"

        /**
         * 프로세스 안에서만 뜻이 있는 번호.
         *
         * 짝을 맞추는 데 쓰지 않는다 — SDK 는 `ACTION_RESULT` 를 액션 이름으로 돌려주고, 판독은
         * 애초에 답을 기다리는 요청이 아니다. `qa_log` 가 발급하는 id 와 값이 겹쳐도 무해하고,
         * 쓰임은 로그에서 한 번의 켜고 끔을 따라가는 것뿐이다.
         */
        val REQUEST_IDS = AtomicLong(0)
    }
}
