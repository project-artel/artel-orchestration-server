package kr.artel.orchestration.tracker.github

import io.netty.channel.ChannelOption
import kr.artel.orchestration.tracker.config.GitHubAppProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/** GitHub REST API 가 요구하는 media type. 없으면 옛 응답 모양이 올 수 있다. */
private const val GITHUB_ACCEPT = "application/vnd.github+json"

/** 고정해 두지 않으면 GitHub 이 응답 모양을 예고 없이 바꿀 수 있는 자리다. */
private const val GITHUB_API_VERSION = "2022-11-28"

@Configuration
class GitHubWebClientConfig {

    /**
     * GitHub 호출용 [WebClient]. **하나만 만든다** — 클래스마다 만들면 Netty connection pool 이
     * 그 수만큼 생기고, 셋 다 같은 host 로 나간다.
     *
     * 응답 timeout 을 [GitHubAppProperties.requestTimeout] 으로 묶는 것이 요점이다. QA 런 도중의
     * 자동 `sync` 가 여기서 무한정 기다리면, 그 사이 굳은 `PENDING` 을 되살리는 유예와 겹쳐 같은
     * 결함이 두 번 나갈 수 있다(`TrackerProperties.claimStaleAfter` 주석 참고).
     */
    @Bean
    fun gitHubWebClient(properties: GitHubAppProperties): WebClient = WebClient.builder()
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(properties.requestTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            )
        )
        .defaultHeader("Accept", GITHUB_ACCEPT)
        .defaultHeader("X-GitHub-Api-Version", GITHUB_API_VERSION)
        .build()
}
