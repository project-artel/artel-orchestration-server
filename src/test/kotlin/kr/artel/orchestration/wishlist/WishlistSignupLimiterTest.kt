package kr.artel.orchestration.wishlist

import kr.artel.orchestration.wishlist.config.WishlistProperties
import kr.artel.orchestration.wishlist.service.WishlistRateLimitedException
import kr.artel.orchestration.wishlist.service.WishlistSignupLimiter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class WishlistSignupLimiterTest {
    @Test
    fun `주소가 가득 차면 새 주소를 거절하고 만료된 자리를 다시 쓴다`() {
        val clock = MutableClock()
        val limiter = WishlistSignupLimiter(
            WishlistProperties(window = Duration.ofSeconds(10), requestsPerWindow = 2, maxTrackedAddresses = 1),
            clock
        )

        limiter.acquire("first")
        assertThrows<WishlistRateLimitedException> { limiter.acquire("second") }
        limiter.acquire("first")
        assertThrows<WishlistRateLimitedException> { limiter.acquire("first") }

        clock.now = clock.now.plusSeconds(10)
        limiter.acquire("second")
        assertThrows<WishlistRateLimitedException> { limiter.acquire("first") }
    }

    private class MutableClock(var now: Instant = Instant.EPOCH) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
}
