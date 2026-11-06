package kr.artel.orchestration.contentmap.capture

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections

/**
 * 아직 답이 오지 않은 화면 `screen capture` 요청 하나 (ARTEL-456).
 *
 * @property requestId SDK 로 내보낸 `action` 의 바깥 id. `ACTION_RESULT` 의 `requestId` 가 이것을
 *   되돌려주고, 그 값이 곧 이 요청을 우리 것으로 알아보는 유일한 표다
 * @property screenId 이 그림을 묶을 화면. 이 값이 요청 시점에 정해지는 것이 요점이다 —
 *   결과가 왔을 때 화면을 다시 고르면 그 사이에 화면이 바뀌어 다른 화면의 그림이 된다
 * @property qaTryId `objectKey` 를 적어 둔 `SCREENSHOT` 행이 딸린 try. 결과 시점에 다시 찾지
 *   않는다 — 그때는 try 가 이미 끝나 있을 수 있고, 그러면 올라간 그림을 두고도 못 묶는다
 * @property gameInstanceId 어느 게임이 답해야 하는가. 다른 인스턴스가 같은 번호로 답하면 그것은
 *   이 요청이 아니다
 * @property requestedAt 만료 판정의 기준
 */
data class PendingScreenCapture(
    val requestId: Long,
    val screenId: Long,
    val qaTryId: Long,
    val gameInstanceId: Long,
    val requestedAt: Instant,
)

/**
 * 답을 기다리는 화면 `screen capture` 요청들 (ARTEL-456).
 *
 * 두 곳이 쓴다 — [ScreenCaptureService] 가 보내면서 넣고, [ScreenCaptureResultRouter] 가
 * `ACTION_RESULT` 를 받아 꺼낸다. 그 둘이 이 클래스의 존재 이유 전부다.
 *
 * ## 왜 DB 가 아닌가
 *
 * 이 표의 수명은 `action` 하나의 왕복이고, 서버가 죽으면 그 왕복도 함께 죽는다 — 답이 돌아올 socket
 * 자체가 그 프로세스의 것이기 때문이다. 재시작 뒤에도 남는 자리에 두면 아무도 답하지 않을 요청이
 * 영원히 쌓인다. `ScanStatusRegistry` 와 같은 판단이다.
 *
 * ## 런이 끝나거나 socket 이 닫히면
 *
 * **아무 일도 일어나지 않는다.** 요청은 claim 되지 않은 채 [TIME_TO_LIVE] 뒤에 버려지고,
 * `screen` 행은 그림 없이 그대로 남는다 — 그림 없는 화면이 화면 없는 지도보다 낫다. 런이 끝난
 * 뒤라면 SDK 가 ticket 을 받지 못해(`QaCaptureService.issueTicket` 이 409) 올라간 이미지도 없다.
 */
@Component
class PendingScreenCaptureRegistry(private val clock: Clock) {

    /**
     * 삽입 순서로 늙은 것부터 버린다.
     *
     * 상한과 만료를 둘 다 두는 이유가 다르다. 상한은 **메모리**를 막고, 만료는 **뜻**을 지킨다 —
     * 한참 뒤에 도착한 답을 묶으면 "화면을 처음 만난 순간의 그림" 이라는 이 칸의 뜻이 깨진다.
     *
     * `LinkedHashMap` 은 스스로 동기화하지 않으므로 감싼다. 넣는 쪽과 꺼내는 쪽이 같은 socket 의
     * 프레임 처리이긴 하나, 인스턴스가 여럿이면 서로 다른 스레드다.
     */
    private val pending: MutableMap<Long, PendingScreenCapture> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, PendingScreenCapture>(INITIAL_CAPACITY, LOAD_FACTOR, false) {
            override fun removeEldestEntry(eldest: Map.Entry<Long, PendingScreenCapture>): Boolean =
                size > MAX_PENDING
        }
    )

    fun put(capture: PendingScreenCapture) {
        pruneExpired()
        pending[capture.requestId] = capture
    }

    /**
     * 이 결과가 우리 요청이면 꺼내 준다. 아니면 null 이고, 그때 호출자는 프레임을 건드리지 않는다.
     *
     * **[gameInstanceId] 까지 보는 것이 요점이다.** 안 보면 다른 게임이 우연히 같은 번호로 답했을
     * 때 남의 그림이 이 화면에 묶인다.
     *
     * 꺼내는 것을 `remove(key, value)` 로 하는 이유: 두 프레임이 같은 번호를 물고 동시에 들어와도
     * 한쪽만 성공한다. 둘 다 성공하면 같은 요청이 두 번 처리된다.
     */
    fun claim(requestId: Long, gameInstanceId: Long): PendingScreenCapture? {
        val found = pending[requestId] ?: return null
        if (found.gameInstanceId != gameInstanceId) return null
        if (!pending.remove(requestId, found)) return null
        if (isExpired(found)) return null
        return found
    }

    private fun pruneExpired() {
        synchronized(pending) {
            pending.values.removeIf(::isExpired)
        }
    }

    private fun isExpired(capture: PendingScreenCapture): Boolean =
        capture.requestedAt.plus(TIME_TO_LIVE).isBefore(clock.instant())

    private companion object {
        /**
         * 요청 하나를 기다려 주는 시간.
         *
         * 왕복에 든 것은 게임의 캡처, ticket 발급, S3 업로드 셋이다. 실측 캡처가 50KB 안팎이라
         * 초 단위로 끝나고, 2 분은 느린 회선까지 덮으면서도 "처음 만난 순간" 이라 부를 수 있는
         * 범위를 벗어나지 않는 값이다.
         */
        val TIME_TO_LIVE: Duration = Duration.ofMinutes(2)

        /**
         * 동시에 답을 기다릴 수 있는 요청 수.
         *
         * 새 화면 하나에 요청 하나이므로, 여기 닿는다는 것은 한 서버가 수백 개의 화면을 동시에
         * 처음 만나고 있다는 뜻이다. 그때는 이 맵보다 먼저 볼 것이 있다.
         */
        const val MAX_PENDING = 256
        const val INITIAL_CAPACITY = 32
        const val LOAD_FACTOR = 0.75f
    }
}
