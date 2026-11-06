package kr.artel.orchestration.auth.service

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.LoginRateLimitProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 이메일 한도(3)와 주소 한도(5)를 따로 확인한다. 둘을 다른 값으로 두어 어느 한도가 걸렸는지 가른다. */
class LoginAttemptLimiterTest {

    /** 테스트가 시간을 직접 옮기는 시계. */
    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MovableClock(Instant.parse("2026-10-06T00:00:00Z"))
    private val limiter = LoginAttemptLimiter(
        LoginRateLimitProperties(maxFailures = 3, addressMaxFailures = 5, window = Duration.ofMinutes(15)),
        clock
    )

    private fun attempt(email: String, address: String, correct: Boolean): Any =
        runBlocking {
            runCatching {
                limiter.guard(email, address) { if (correct) "signed-in" else throw InvalidCredentialsException() }
            }.fold(onSuccess = { it }, onFailure = { it })
        }

    @Test
    fun `the email limit blocks even the right password and reports the time left`() {
        repeat(3) { attempt("Victim@Example.com", "10.0.0.$it", correct = false) }
        clock.now = clock.now.plusSeconds(60)

        val blocked = attempt("victim@example.com", "10.0.0.99", correct = true)

        assertThat(blocked).isInstanceOf(TooManyAttemptsException::class.java)
        assertThat((blocked as TooManyAttemptsException).responseHeaders).containsEntry("Retry-After", (14 * 60).toString())
    }

    @Test
    fun `the address limit turns wrong passwords into 429 but lets the right password in`() {
        repeat(5) { attempt("user$it@example.com", "172.17.0.1", correct = false) }

        assertThat(attempt("other@example.com", "172.17.0.1", correct = false))
            .isInstanceOf(TooManyAttemptsException::class.java)
        assertThat(attempt("owner@example.com", "172.17.0.1", correct = true)).isEqualTo("signed-in")
    }

    @Test
    fun `below the address limit a wrong password is a plain 401`() {
        repeat(4) { attempt("user$it@example.com", "172.17.0.2", correct = false) }

        assertThat(attempt("user9@example.com", "172.17.0.2", correct = false))
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    fun `the email limit does not depend on the address`() {
        repeat(3) { attempt("target@example.com", "10.1.0.$it", correct = false) }

        assertThat(attempt("target@example.com", "10.1.0.200", correct = false))
            .isInstanceOf(TooManyAttemptsException::class.java)
        assertThat(attempt("someone@example.com", "10.1.0.200", correct = false))
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    fun `a successful login clears the email counter`() {
        repeat(2) { attempt("user@example.com", "10.2.0.$it", correct = false) }
        attempt("user@example.com", "10.2.0.9", correct = true)
        repeat(2) { attempt("user@example.com", "10.2.0.$it", correct = false) }

        assertThat(attempt("user@example.com", "10.2.0.50", correct = true)).isEqualTo("signed-in")
    }

    /** 주소를 모르는 요청도 한도를 건너뛰지 않는다. 한 묶음으로 센다. */
    @Test
    fun `requests without an address share one counter`() {
        repeat(5) { attempt("user$it@example.com", UNKNOWN_CLIENT_ADDRESS, correct = false) }

        assertThat(attempt("next@example.com", UNKNOWN_CLIENT_ADDRESS, correct = false))
            .isInstanceOf(TooManyAttemptsException::class.java)
    }

    @Test
    fun `the block ends when the window ends`() {
        repeat(3) { attempt("user@example.com", "10.3.0.1", correct = false) }
        clock.now = clock.now.plus(Duration.ofMinutes(15))

        assertThat(attempt("user@example.com", "10.3.0.1", correct = true)).isEqualTo("signed-in")
    }
}
