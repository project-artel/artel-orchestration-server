package kr.artel.orchestration.tracker.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `provider` 와 무관한 tracker 공통 설정.
 *
 * GitHub 전용 값은 [GitHubAppProperties] 가 따로 들고 있다. 두 클래스를 가르는 기준은 "tracker 가
 * 하나 더 붙어도 그대로 쓰이는 값인가"이다.
 */
@ConfigurationProperties("artel.tracker")
data class TrackerProperties(
    /**
     * 굳은 `PENDING` 을 다시 `claim` 할 수 있게 되기까지의 유예.
     *
     * 내보내는 도중 프로세스가 죽으면 행이 `PENDING` 으로 남는다. 이 유예가 없으면 그 행은 영원히
     * 되살아나지 못해, 사람이 수동 endpoint 를 눌러도 아무 일이 일어나지 않는다.
     *
     * ⚠️ [GitHubAppProperties.requestTimeout] 보다 훨씬 커야 한다. 정상 호출이 이 유예 안에 끝나지
     * 않으면 요청 둘이 같은 결함을 동시에 내보내 외부 이슈가 둘 생긴다.
     */
    val claimStaleAfter: Duration = Duration.ofMinutes(5)
) {
    init {
        require(!claimStaleAfter.isNegative && !claimStaleAfter.isZero) {
            "artel.tracker.claim-stale-after는 0보다 커야 합니다."
        }
    }
}
