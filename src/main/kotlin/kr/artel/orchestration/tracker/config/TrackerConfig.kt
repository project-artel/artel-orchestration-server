package kr.artel.orchestration.tracker.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(TrackerProperties::class, GitHubAppProperties::class)
class TrackerConfig {

    /**
     * 자동 `sync` 를 태우는 scope.
     *
     * `SupervisorJob` 이라 결함 하나의 실패가 다음 결함의 `sync` 를 죽이지 않는다. **빈으로 두는
     * 이유는 서비스가 scope 를 스스로 만들면 테스트가 그것을 붙잡을 수 없기 때문**이다 — 테스트는
     * `launchAutoSync` 가 돌려주는 `Job` 을 `join` 해 결과를 확인한다.
     */
    @Bean
    fun trackerSyncScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
