package kr.artel.orchestration.tracker.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * GitHub 응답은 필드가 수십 개라 전부 받지 않는다. 쓰는 것만 선언하고 나머지는 무시하되,
 * **선언된 타입으로 받는 것**은 지킨다(`coding-style.md` Data Shapes) — 키 오타가 세 층 아래의
 * null 이 아니라 파싱 경계에서 드러난다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubIssueResponse(
    val number: Long,
    @param:JsonProperty("html_url") val htmlUrl: String,
    val state: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubRepositoryResponse(
    val name: String,
    val owner: GitHubOwnerResponse,
    @param:JsonProperty("html_url") val htmlUrl: String,
    val private: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubOwnerResponse(val login: String)

/** `GET /installation/repositories` 의 봉투. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubInstallationRepositoriesResponse(
    @param:JsonProperty("total_count") val totalCount: Int = 0,
    val repositories: List<GitHubRepositoryResponse> = emptyList()
)

/** 이슈 생성 요청 본문. */
data class GitHubCreateIssueRequest(val title: String, val body: String)

/** 이슈 상태 전이 요청 본문. `closed` 또는 `open`. */
data class GitHubIssueStateRequest(val state: String)

/** user-to-server token 교환 응답. `code` 가 만료·재사용이면 200 에 error 필드만 실려 온다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubUserTokenResponse(
    @param:JsonProperty("access_token") val accessToken: String? = null,
    val error: String? = null
)

/** `GET /user/installations` 의 봉투. 그 사람이 접근할 수 있는 installation 만 들어 있다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubUserInstallationsResponse(
    @param:JsonProperty("total_count") val totalCount: Int = 0,
    val installations: List<GitHubInstallationRef> = emptyList()
)

/** installation 한 건. [account] 가 없는 항목은 화면에 보일 이름이 없어 목록에서 뺀다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubInstallationRef(val id: Long, val account: GitHubAccount? = null)

/** installation 이 설치된 계정. [type] 은 `User`, `Organization`, `Enterprise` 중 하나다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubAccount(val login: String, val type: String? = null)
