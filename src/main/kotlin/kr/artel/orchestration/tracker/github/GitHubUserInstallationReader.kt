package kr.artel.orchestration.tracker.github

import kr.artel.orchestration.tracker.dto.TrackerAccountType

/** 사용자 인가로 읽은 installation 한 건. Redis 에 그대로 JSON 으로 저장되므로 user token 은 담지 않는다. */
data class GitHubUserInstallation(
    val id: Long,
    val account: String,
    val accountType: TrackerAccountType
)

/**
 * GitHub App 사용자 인가의 `code` 로 그 사람이 접근할 수 있는 installation 을 읽는다.
 *
 * interface 로 둔 이유는 테스트다. 구현은 실제 github.com 에 token 교환을 보내므로, 설치 복귀와
 * installation 선택의 성공 경로는 fake 를 끼워야 검증할 수 있다.
 */
interface GitHubUserInstallationReader {
    /** code 를 user token 으로 바꿔 그 사람의 installation 을 읽는다. 어느 단계든 실패하면 null. */
    suspend fun installationsOf(code: String): List<GitHubUserInstallation>?
}
