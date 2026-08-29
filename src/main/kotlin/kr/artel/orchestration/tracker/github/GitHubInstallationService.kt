package kr.artel.orchestration.tracker.github

import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.tracker.client.TrackerNotConfiguredException
import kr.artel.orchestration.tracker.client.TrackerRepository
import kr.artel.orchestration.tracker.config.GitHubAppProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** GitHub 이 한 번에 돌려주는 저장소 수의 상한. 더 큰 조직은 이 버전의 범위 밖이다(Non-goal). */
private const val REPOSITORY_PAGE_SIZE = 100

/**
 * GitHub App `installation` 흐름. 설치 주소를 만들고, 설치된 저장소를 읽어 온다.
 *
 * 이 둘은 [IssueTrackerClient][kr.artel.orchestration.tracker.client.IssueTrackerClient] port 에
 * 없다. 설치라는 개념 자체가 GitHub 고유라, port 에 올리면 Jira 구현체가 구현할 수 없는 메서드를
 * 두 개 물려받는다. 계약이 이 흐름만 `/tracker/github` 아래 별도 경로로 둔 이유도 같다.
 */
@Service
class GitHubInstallationService(
    private val tokenService: GitHubAppTokenService,
    private val webClient: WebClient,
    private val properties: GitHubAppProperties
) {
    private val logger = LoggerFactory.getLogger(GitHubInstallationService::class.java)

    /**
     * App 설치 화면 주소. [state] 는 서명된 값이며, 서명이 없으면 남의 프로젝트에 설치를 붙일 수 있다.
     */
    fun installUrl(state: String): String {
        if (!properties.configured) {
            throw TrackerNotConfiguredException("GitHub App이 설정되지 않았습니다.")
        }
        val encoded = URLEncoder.encode(state, StandardCharsets.UTF_8)
        return "${properties.webBaseUrl.trimEnd('/')}/apps/${properties.appSlug}" +
            "/installations/new?state=$encoded"
    }

    /**
     * 콜백이 싣고 온 `installation_id` 가 **정말 그 사람의 것인지** 확인한다.
     *
     * 서명된 `state` 는 "이 사람이 이 프로젝트의 OWNER 인가"만 증명한다. `installation_id` 는 GitHub 이
     * 쿼리로 붙여 주는 작은 순차 정수라 공격자가 고를 수 있고, App private key 는 **그 App 의 모든**
     * installation token 을 발급할 수 있다. 확인하지 않으면 아무 프로젝트 OWNER 나 남의 조직
     * installation 을 자기 프로젝트에 붙여 private 저장소 목록을 읽는다.
     *
     * 확인 경로는 GitHub 이 정해 둔 것 그대로다: 설치 중 사용자 인가로 받은 [code] 를 user-to-server
     * token 으로 바꾸고, 그 token 으로 `GET /user/installations` 를 읽어 목록에 있는지 본다.
     *
     * @return 확인되면 true. [code] 가 없거나 교환에 실패하거나 목록에 없으면 false — 이유를 구분해
     *   돌려주지 않는다(어느 조건이 틀렸는지가 단서로 새어 나가지 않게).
     */
    suspend fun verifyInstallationBelongsToCaller(code: String?, installationRef: String): Boolean {
        // App 설정이 없으면 교환이 성립할 수 없다. 여기서 끊지 않으면 client_id 가 빈 요청이 실제로
        // github.com 으로 나가고, 테스트가 네트워크에 의존하게 된다(`testing.md`: network reliance 금지).
        if (!properties.configured) return false
        if (code.isNullOrBlank()) return false
        val installationId = installationRef.toLongOrNull() ?: return false
        val userToken = exchangeUserToken(code) ?: return false
        val installations = try {
            webClient.get()
                .uri("${properties.apiBaseUrl.trimEnd('/')}/user/installations?per_page=$REPOSITORY_PAGE_SIZE")
                .header("Authorization", "Bearer $userToken")
                .retrieve()
                .awaitBody<GitHubUserInstallationsResponse>()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("GitHub user installations 조회 실패", error)
            return false
        }
        return installations.installations.any { it.id == installationId }
    }

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
                    if (response.error != null) {
                        logger.warn("GitHub user token 교환 거절: error={}", response.error)
                        null
                    } else {
                        response.accessToken?.takeIf { it.isNotBlank() }
                    }
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 로그가 없으면 "설치가 안 붙는다"는 증상만 남고 서버에 흔적이 없다.
            logger.warn("GitHub user token 교환 실패", error)
            null
        }

    /** 설치된 저장소 목록 첫 장. */
    suspend fun listRepositories(installationRef: String): List<TrackerRepository> {
        val token = tokenService.installationToken(installationRef)
        val response = try {
            webClient.get()
                .uri("${properties.apiBaseUrl.trimEnd('/')}/installation/repositories?per_page=$REPOSITORY_PAGE_SIZE")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .awaitBody<GitHubInstallationRepositoriesResponse>()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw UpstreamUnavailableException(
                "GitHub 저장소 목록 조회 실패",
                code = "tracker_upstream_unavailable",
                cause = error
            )
        }
        return response.repositories.map {
            TrackerRepository(
                workspace = it.owner.login,
                repository = it.name,
                htmlUrl = it.htmlUrl,
                private = it.private
            )
        }
    }
}
