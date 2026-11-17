package kr.artel.orchestration.wishlist.service

import kr.artel.orchestration.common.error.TooManyRequestsException
import kr.artel.orchestration.wishlist.config.WishlistProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 세기 시작한 시각과 그 뒤의 요청 수. 창이 끝나면 새로 센다. */
private data class RequestWindow(val startedAt: Instant, val requests: Int)

/**
 * `POST /wishlist` 의 요청을 클라이언트 주소마다 센다. 모양은 `TelemetryReceiverLimiter` 를 따른다.
 *
 * 성공과 실패를 가리지 않고 센다. 이미 있는 주소를 다시 보낸 요청도 센다 — 그 요청만 빼 주면 응답 시간이나 한도로
 * 가입 여부가 드러난다.
 *
 * 주소는 `X-Forwarded-For` 의 맨 왼쪽 값이라 속일 수 있다(`LoginAttemptLimiter` 의 설명과 같다). 이 한도는 한 곳에서
 * 주소를 쏟아붓는 것을 늦출 뿐이고, 저장되는 양은 이메일 unique 제약이 묶는다.
 */
@Component
class WishlistSignupLimiter(
    private val properties: WishlistProperties,
    private val clock: Clock
) {
    private val windows = ConcurrentHashMap<String, RequestWindow>()

    /** 요청 하나를 센다. 한도를 넘으면 429 다. */
    fun acquire(clientAddress: String) {
        val now = Instant.now(clock)
        if (windows.size >= properties.maxTrackedAddresses) evictExpired(now)
        val window = windows.compute(clientAddress) { _, current ->
            if (current == null || !isLive(current, now)) RequestWindow(now, 1) else current.copy(requests = current.requests + 1)
        }!!
        if (window.requests > properties.requestsPerWindow) {
            val remaining = Duration.between(now, window.startedAt.plus(properties.window))
            throw WishlistRateLimitedException(maxOf(1, remaining.seconds + if (remaining.nano > 0) 1 else 0))
        }
    }

    private fun isLive(window: RequestWindow, now: Instant) = now.isBefore(window.startedAt.plus(properties.window))

    private fun evictExpired(now: Instant) {
        windows.entries.removeIf { !isLive(it.value, now) }
    }
}

class WishlistRateLimitedException(retryAfterSeconds: Long) :
    TooManyRequestsException(
        "요청이 너무 많습니다. 잠시 뒤에 다시 시도하세요.",
        retryAfterSeconds = retryAfterSeconds,
        code = "too_many_requests"
    )
