package kr.artel.orchestration.tracker.github

import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.tracker.config.GitHubAppProperties
import kr.artel.orchestration.tracker.dto.TrackerAccountType
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

/** `GET /user/installations` 한 번에 받는 항목 수. 100개를 넘는 사람은 이 버전의 범위 밖이다(Non-goal). */
private const val USER_INSTALLATION_PAGE_SIZE = 100

/**
 * [GitHubUserInstallationReader] 의 GitHub 구현. user-to-server token 을 다루는 경로는 이 클래스 하나다.
 *
 * 교환한 user token 은 이 호출 안에서만 쓰고 버린다. 저장하지 않는다.
 */
@Component
class GitHubUserInstallationClient(
    private val webClient: WebClient,
    private val properties: GitHubAppProperties
) : GitHubUserInstallationReader {
    private val logger = LoggerFactory.getLogger(GitHubUserInstallationClient::class.java)

    override suspend fun installationsOf(code: String): List<GitHubUserInstallation>? {
        // App 설정이 없으면 교환이 성립할 수 없다. 여기서 끊지 않으면 client_id 가 빈 요청이 실제로
        // github.com 으로 나가고, 테스트가 네트워크에 의존하게 된다(`testing.md`: network reliance 금지).
        if (!properties.configured) return null
        if (code.isBlank()) return null
        val userToken = exchangeUserToken(code) ?: return null
        val response = try {
            webClient.get()
                .uri(
                    "${properties.apiBaseUrl.trimEnd('/')}/user/installations" +
                        "?per_page=$USER_INSTALLATION_PAGE_SIZE"
                )
                .header("Authorization", "Bearer $userToken")
                .retrieve()
                .awaitBody<GitHubUserInstallationsResponse>()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("GitHub user installations 조회 실패", error)
            return null
        }
        if (response.totalCount > response.installations.size) {
            logger.warn(
                "GitHub user installations 목록이 잘렸다: total_count={}, received={}",
                response.totalCount,
                response.installations.size
            )
        }
        return response.installations.mapNotNull { installation ->
            val account = installation.account ?: return@mapNotNull null
            GitHubUserInstallation(
                id = installation.id,
                account = account.login,
                accountType = accountTypeOf(account.type)
            )
        }
    }

    /**
     * GitHub `account.type` 은 `User`, `Organization`, `Enterprise` 중 하나다. `Enterprise` 도 사람
     * 한 명이 아니라 여러 사람이 쓰는 계정이므로 `ORGANIZATION` 으로 읽는다.
     */
    private fun accountTypeOf(type: String?): TrackerAccountType =
        if (type == "User") TrackerAccountType.USER else TrackerAccountType.ORGANIZATION

    private suspend fun exchangeUserToken(code: String): String? =
        try {
            webClient.post()
                .uri("${properties.webBaseUrl.trimEnd('/')}/login/oauth/access_token")
                // ⚠️ client_secret 을 query 에 싣지 않는다. 쿼리 문자열은 리버스 프록시 access log 와
                // WebClientResponseException 의 메시지에 그대로 남아, 비밀이 로그로 새는 경로가 된다.
                .body(
                    BodyInserters.fromFormData("client_id", properties.clientId)
                        .with("client_secret", properties.clientSecret)
                        .with("code", code)
                )
                // ⚠️ 공용 client 의 기본값(`application/vnd.github+json`)을 반드시 덮어쓴다.
                // 이 endpoint 는 `application/json` 을 명시할 때만 JSON 을 주고, 아니면
                // `access_token=...&token_type=bearer` 형태의 form 을 준다. 그러면 파싱이 실패해
                // 정상 설치까지 전부 거절된다 — 조용히 깨지는 자리라 헤더를 여기서 못박는다.
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .awaitBody<GitHubUserTokenResponse>()
                .let { response ->
                    val accessToken = response.accessToken?.takeIf { it.isNotBlank() }
                    when {
                        response.error != null -> {
                            logger.warn("GitHub user token 교환 거절: error={}", response.error)
                            null
                        }
                        accessToken == null -> {
                            logger.warn("GitHub user token 교환 응답에 access_token 이 없다")
                            null
                        }
                        else -> accessToken
                    }
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 로그가 없으면 "설치가 안 붙는다"는 증상만 남고 서버에 흔적이 없다.
            logger.warn("GitHub user token 교환 실패", error)
            null
        }
}
