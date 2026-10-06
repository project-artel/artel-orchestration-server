package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.config.LoginRateLimitProperties
import kr.artel.orchestration.common.error.TooManyRequestsException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 주소를 알 수 없는 요청이 함께 쓰는 주소 키. 한도를 건너뛰지 않고 한 묶음으로 센다. */
const val UNKNOWN_CLIENT_ADDRESS = "unknown"

/** 실패를 세기 시작한 시각과 그 뒤의 실패 수. 창이 끝나면 새로 센다. */
private data class FailureWindow(val startedAt: Instant, val failures: Int)

/**
 * 로그인 실패를 이메일과 클라이언트 주소 두 키로 센다. 두 한도는 하는 일이 다르다.
 *
 * - **이메일 한도**(기본 10)는 한 계정에 비밀번호를 쏟아붓는 것을 막는다. 닿으면 비밀번호를 대조하지
 *   않고 429 다. 맞는 비밀번호도 429 라, 막힌 동안에는 맞췄는지를 응답으로 알 수 없다.
 * - **주소 한도**(기본 100)는 한 곳에서 여러 계정을 훑는 것을 늦춘다. 닿아도 비밀번호는 대조하고, 맞으면
 *   로그인이 된다. 틀리면 401 대신 429 다. 주소가 모두 같은 설치(Docker userland proxy 뒤)에서 누구
 *   하나의 실패가 모두를 내쫓지 않게 하려는 것이다. 그 대가로 이 한도는 맞는 비밀번호를 찾는 것 자체는
 *   막지 못하고, 그 일은 이메일 한도가 한다.
 *
 * **주소는 속일 수 있다.** `server.forward-headers-strategy: framework` 라 요청 주소는 `X-Forwarded-For`
 * 의 맨 왼쪽 값이고, 클라이언트가 적을 수 있다. 리버스 프록시가 그 헤더를 자기가 본 주소로 덮어써야 주소
 * 한도가 사람마다 걸린다. 이메일 한도는 그것과 무관하게 걸린다.
 *
 * 성공한 로그인은 그 이메일의 카운터를 지운다. 주소 카운터는 지우지 않는다 — 한 계정으로 성공해 두고
 * 다른 계정들을 훑는 것을 막는다.
 */
@Component
class LoginAttemptLimiter(
    private val properties: LoginRateLimitProperties,
    private val clock: Clock
) {
    private val windows = ConcurrentHashMap<String, FailureWindow>()

    /**
     * 한도를 적용해 [attempt] 를 부른다. [attempt] 가 [InvalidCredentialsException] 을 던지면 실패로 센다.
     * 다른 예외(막힌 계정의 403 등)는 비밀번호가 맞은 것이라 세지 않고 그대로 올린다.
     */
    suspend fun <T> guard(email: String, clientAddress: String, attempt: suspend () -> T): T {
        val now = Instant.now(clock)
        retryAfter(emailKey(email), properties.maxFailures, now)?.let { throw TooManyAttemptsException(it) }
        val addressRetryAfter = retryAfter(addressKey(clientAddress), properties.addressMaxFailures, now)

        val result = try {
            attempt()
        } catch (failure: InvalidCredentialsException) {
            recordFailure(email, clientAddress)
            if (addressRetryAfter != null) throw TooManyAttemptsException(addressRetryAfter)
            throw failure
        }
        windows.remove(emailKey(email))
        return result
    }

    private fun recordFailure(email: String, clientAddress: String) {
        val now = Instant.now(clock)
        if (windows.size >= properties.maxTrackedKeys) evictExpired(now)
        for (key in listOf(emailKey(email), addressKey(clientAddress))) {
            windows.compute(key) { _, current ->
                if (current == null || !isLive(current, now)) FailureWindow(now, 1) else current.copy(failures = current.failures + 1)
            }
        }
    }

    /** 한도에 닿았으면 창이 끝날 때까지 남은 초(최소 1), 아니면 null. */
    private fun retryAfter(key: String, limit: Int, now: Instant): Long? {
        val window = windows[key]?.takeIf { isLive(it, now) && it.failures >= limit } ?: return null
        return maxOf(1, ceilSeconds(Duration.between(now, window.startedAt.plus(properties.window))))
    }

    private fun emailKey(email: String) = "email:${email.trim().lowercase()}"

    private fun addressKey(clientAddress: String) = "address:$clientAddress"

    private fun isLive(window: FailureWindow, now: Instant) = now.isBefore(window.startedAt.plus(properties.window))

    private fun evictExpired(now: Instant) {
        windows.entries.removeIf { !isLive(it.value, now) }
    }

    private fun ceilSeconds(duration: Duration): Long =
        duration.seconds + if (duration.nano > 0) 1 else 0
}

class TooManyAttemptsException(retryAfterSeconds: Long) :
    TooManyRequestsException(
        "로그인 시도가 너무 많습니다. 잠시 뒤에 다시 시도하세요.",
        retryAfterSeconds = retryAfterSeconds,
        code = "too_many_attempts"
    )
