package kr.artel.orchestration.telemetry.service

import kr.artel.orchestration.common.error.TooManyRequestsException
import kr.artel.orchestration.telemetry.config.TelemetryProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 세기 시작한 시각과 그 뒤의 요청 수. 창이 끝나면 새로 센다. */
private data class RequestWindow(val startedAt: Instant, val requests: Int)

/**
 * 공개 receiver(`/api/telemetry/reports`)의 요청을 클라이언트 주소마다 센다. 모양은 `LoginAttemptLimiter` 를 따른다.
 *
 * 성공과 실패를 가리지 않고 센다. 정상인 설치는 하루 한 번, 밀린 날이 있어도 한 tick 에 7번까지 보내므로 기본 한도
 * 30번에 닿지 않는다.
 *
 * 카운터는 프로세스 메모리에 있다. 서버를 다시 띄우면 비워지고, 인스턴스가 여럿이면 인스턴스마다 따로 센다. 주소는
 * `X-Forwarded-For` 의 맨 왼쪽 값이라 속일 수 있다(`LoginAttemptLimiter` 의 설명과 같다). 이 한도는 남용을 늦출 뿐이고,
 * 저장할 수 있는 양은 본문 상한과 `(install_id, report_date)` unique 제약이 묶는다.
 */
@Component
class TelemetryReceiverLimiter(
    private val properties: TelemetryProperties,
    private val clock: Clock
) {
    private val windows = ConcurrentHashMap<String, RequestWindow>()

    /** 요청 하나를 센다. 한도를 넘으면 429 다. */
    fun acquire(clientAddress: String) {
        val receiver = properties.receiver
        val now = Instant.now(clock)
        if (windows.size >= receiver.maxTrackedAddresses) evictExpired(now)
        val window = windows.compute(clientAddress) { _, current ->
            if (current == null || !isLive(current, now)) RequestWindow(now, 1) else current.copy(requests = current.requests + 1)
        }!!
        if (window.requests > receiver.requestsPerWindow) {
            val remaining = Duration.between(now, window.startedAt.plus(receiver.window))
            throw TelemetryRateLimitedException(maxOf(1, remaining.seconds + if (remaining.nano > 0) 1 else 0))
        }
    }

    private fun isLive(window: RequestWindow, now: Instant) =
        now.isBefore(window.startedAt.plus(properties.receiver.window))

    private fun evictExpired(now: Instant) {
        windows.entries.removeIf { !isLive(it.value, now) }
    }
}

class TelemetryRateLimitedException(retryAfterSeconds: Long) :
    TooManyRequestsException(
        "telemetry 요청이 너무 많습니다. 잠시 뒤에 다시 보내세요.",
        retryAfterSeconds = retryAfterSeconds,
        code = "too_many_requests"
    )
