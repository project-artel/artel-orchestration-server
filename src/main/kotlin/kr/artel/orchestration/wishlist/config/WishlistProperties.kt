package kr.artel.orchestration.wishlist.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 소개 사이트의 Wishlist 가입(`POST /wishlist`) 한도.
 *
 * 카운터는 프로세스 메모리에 있다. 서버를 다시 띄우면 비워지고, 인스턴스가 여럿이면 인스턴스마다 따로 센다.
 */
@ConfigurationProperties("artel.wishlist")
data class WishlistProperties(
    /**
     * 한 클라이언트 주소가 [window] 안에 보낼 수 있는 요청 수. 넘으면 429 다.
     *
     * 한 사람은 보통 한두 번 보낸다. 30 으로 둔 이유는 사무실이나 행사장처럼 여러 사람이 한 주소로 나가는 경우다.
     */
    val requestsPerWindow: Int = 30,
    val window: Duration = Duration.ofHours(1),
    /** 메모리에 들고 있는 주소 수의 상한. 넘으면 창이 끝난 주소부터 지운다. */
    val maxTrackedAddresses: Int = 100_000
) {
    init {
        require(requestsPerWindow > 0) { "artel.wishlist.requests-per-window must be positive" }
        require(!window.isZero && !window.isNegative) { "artel.wishlist.window must be positive" }
        require(maxTrackedAddresses > 0) { "artel.wishlist.max-tracked-addresses must be positive" }
    }
}

@Configuration
@EnableConfigurationProperties(WishlistProperties::class)
class WishlistConfig
