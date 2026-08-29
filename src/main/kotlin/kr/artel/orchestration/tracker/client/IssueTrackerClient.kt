package kr.artel.orchestration.tracker.client

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.tracker.entity.TrackerProvider

/**
 * 어느 tracker 의 어느 저장소로 내보내는가. `provider` 별 구현체는 이 값만 보고 움직인다.
 *
 * GitHub 에서 [workspace] 는 owner, [installationRef] 는 App 의 installation id 다. Jira 를 붙이면
 * [workspace] 가 site 가 되고 [installationRef] 는 쓰이지 않는다 — 이름을 중립으로 둔 이유다.
 */
data class TrackerTarget(
    val workspace: String,
    val repository: String,
    val installationRef: String?
)

/** 저쪽에 만들 이슈의 내용. 어떤 문단이 들어가는지는 `TrackerIssueBodyWriter` 가 정한다. */
data class TrackerIssueDraft(val title: String, val body: String)

/** 만들어진 저쪽 이슈. [externalKey] 는 GitHub 이면 이슈 번호다. */
data class TrackerIssueRef(val externalKey: String, val url: String)

/** 설치된 저장소 한 건. 연결 화면이 고르는 후보다. */
data class TrackerRepository(
    val workspace: String,
    val repository: String,
    val htmlUrl: String,
    val private: Boolean
)

/**
 * 결함을 외부 tracker 로 내보내는 port.
 *
 * 이 인터페이스가 있는 이유는 registry 때문이 아니라
 * [kr.artel.orchestration.tracker.service.IssueTrackerSyncService] 가 `provider` 를 몰라야 하기
 * 때문이다. severity 판정 · 멱등 `claim` · 실패 기록은 어느 tracker 든 같고, 다른 것은 이 네 동작뿐이다.
 *
 * `installation` 흐름(설치 주소, 저장소 목록)은 여기 없다. 그것은 GitHub 고유라 `provider` 마다
 * 모양이 다르고, 계약도 `/tracker/github` 아래의 별도 경로로 정해 두었다.
 */
interface IssueTrackerClient {
    val provider: TrackerProvider

    /**
     * 사람이 저장소를 열어 볼 주소. 순수 함수이며 네트워크에 나가지 않는다.
     *
     * 조회 응답의 `htmlUrl` 을 서비스가 직접 조립하면 `https://github.com/...` 이 provider 를 모르는
     * 코드에 박히고, GitHub Enterprise 처럼 host 가 다른 설치에서 틀린 주소가 나간다.
     */
    fun webUrlOf(target: TrackerTarget): String

    /**
     * 저장소에 닿을 수 있고 이슈를 쓸 수 있는지. 연결을 저장하기 전에 확인한다.
     *
     * `installation` 처럼 provider 마다 다른 전제도 여기서 판정한다 — 호출부가 그것을 알면
     * `installation` 개념이 없는 다음 provider 를 붙일 때 호출부를 고쳐야 한다.
     */
    suspend fun verifyRepositoryAccess(target: TrackerTarget)

    suspend fun createIssue(target: TrackerTarget, draft: TrackerIssueDraft): TrackerIssueRef

    suspend fun closeIssue(target: TrackerTarget, externalKey: String)

    suspend fun reopenIssue(target: TrackerTarget, externalKey: String)
}

/** 지원하지 않는 `provider` 를 요청이 실어 왔을 때. */
class UnsupportedTrackerException(provider: String) :
    BadRequestException("지원하지 않는 이슈 트래커입니다: $provider", code = "unsupported_tracker")

/** 서버에 App 설정이 없거나 자격증명을 읽을 수 없을 때. 기존 QA 경로는 이 오류의 영향을 받지 않는다. */
class TrackerNotConfiguredException(message: String, cause: Throwable? = null) :
    UpstreamUnavailableException(message, code = "tracker_not_configured", cause = cause)

/** 프로젝트에 `installation` 이 아직 붙지 않았을 때. 사람이 App 을 설치하면 풀린다. */
class TrackerNotInstalledException(message: String) :
    BadRequestException(message, code = "tracker_not_installed")

/** 저장소가 없거나 App 이 그 저장소에 접근할 수 없을 때. */
class TrackerRepositoryUnavailableException(message: String) :
    BadRequestException(message, code = "tracker_repository_unavailable")
