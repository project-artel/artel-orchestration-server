package kr.artel.orchestration.tracker.github

import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.tracker.client.IssueTrackerClient
import kr.artel.orchestration.tracker.client.TrackerIssueDraft
import kr.artel.orchestration.tracker.client.TrackerIssueRef
import kr.artel.orchestration.tracker.client.TrackerNotInstalledException
import kr.artel.orchestration.tracker.client.TrackerRepositoryUnavailableException
import kr.artel.orchestration.tracker.client.TrackerTarget
import kr.artel.orchestration.tracker.config.GitHubAppProperties
import kr.artel.orchestration.tracker.entity.TrackerProvider
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitBodilessEntity

/**
 * [IssueTrackerClient] 의 첫 구현체.
 *
 * 자격증명은 installation access token 이고 [GitHubAppTokenService] 가 만든다. 이 클래스는 어느
 * 결함을 왜 내보내는지 모른다 — 그 판단은 `IssueTrackerSyncService` 에 있고, 여기는 네 동작을
 * GitHub 의 말로 옮기기만 한다.
 */
@Component
class GitHubIssueTrackerClient(
    private val tokenService: GitHubAppTokenService,
    private val webClient: WebClient,
    properties: GitHubAppProperties
) : IssueTrackerClient {

    override val provider = TrackerProvider.GITHUB

    private val apiBaseUrl = properties.apiBaseUrl.trimEnd('/')

    private val webBaseUrl = properties.webBaseUrl.trimEnd('/')

    override fun webUrlOf(target: TrackerTarget) =
        "$webBaseUrl/${target.workspace}/${target.repository}"

    override suspend fun verifyRepositoryAccess(target: TrackerTarget) {
        val token = token(target)
        call("저장소 접근 확인", target) {
            webClient.get()
                .uri("$apiBaseUrl/repos/${target.workspace}/${target.repository}")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .awaitBody<GitHubRepositoryResponse>()
        }
    }

    override suspend fun createIssue(target: TrackerTarget, draft: TrackerIssueDraft): TrackerIssueRef {
        val token = token(target)
        val created = call("이슈 생성", target) {
            webClient.post()
                .uri("$apiBaseUrl/repos/${target.workspace}/${target.repository}/issues")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(GitHubCreateIssueRequest(draft.title, draft.body))
                .retrieve()
                .awaitBody<GitHubIssueResponse>()
        }
        return TrackerIssueRef(externalKey = created.number.toString(), url = created.htmlUrl)
    }

    override suspend fun closeIssue(target: TrackerTarget, externalKey: String) =
        transitionIssue(target, externalKey, "closed")

    override suspend fun reopenIssue(target: TrackerTarget, externalKey: String) =
        transitionIssue(target, externalKey, "open")

    private suspend fun transitionIssue(target: TrackerTarget, externalKey: String, state: String) {
        val token = token(target)
        call("이슈 상태 반영", target) {
            webClient.patch()
                .uri("$apiBaseUrl/repos/${target.workspace}/${target.repository}/issues/$externalKey")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(GitHubIssueStateRequest(state))
                .retrieve()
                .awaitBodilessEntity()
        }
    }

    private suspend fun token(target: TrackerTarget): String {
        val installationRef = target.installationRef
            ?: throw TrackerNotInstalledException("이 프로젝트에는 GitHub App이 설치되어 있지 않습니다.")
        return tokenService.installationToken(installationRef)
    }

    /**
     * GitHub 응답을 우리 오류로 옮긴다.
     *
     * 404/403 을 4xx 로 내리는 이유는 그것이 서버 장애가 아니라 **설정 문제**이기 때문이다 — 저장소를
     * 잘못 골랐거나 App 이 그 저장소를 못 본다. 사람이 고칠 수 있는 것에 503 을 주면 재시도만 하게 된다.
     * message 에 GitHub 응답 원문을 싣지 않는 것은 `error-handling.md` 의 4xx 규약 그대로다.
     */
    private suspend fun <T> call(what: String, target: TrackerTarget, block: suspend () -> T): T =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: WebClientResponseException) {
            when (error.statusCode) {
                HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN -> throw TrackerRepositoryUnavailableException(
                    "GitHub 저장소 ${target.workspace}/${target.repository}에 접근할 수 없습니다. " +
                        "App이 그 저장소에 설치되어 있고 Issues 권한이 있는지 확인해 주세요."
                )
                else -> throw UpstreamUnavailableException(
                    "GitHub $what 실패: status=${error.statusCode}",
                    code = "tracker_upstream_unavailable",
                    cause = error
                )
            }
        } catch (error: Exception) {
            throw UpstreamUnavailableException(
                "GitHub $what 실패",
                code = "tracker_upstream_unavailable",
                cause = error
            )
        }
}
