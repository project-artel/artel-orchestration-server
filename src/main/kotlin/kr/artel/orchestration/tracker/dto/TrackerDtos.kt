package kr.artel.orchestration.tracker.dto

import jakarta.validation.constraints.NotBlank
import java.time.Instant

/**
 * 프로젝트의 tracker `link` 한 건, 화면이 읽는 모양(ARTEL-671, ARTEL-672 와의 계약).
 *
 * [installed] 와 [workspace] 가 갈리는 이유: App 을 설치하는 것과 저장소를 고르는 것이 두 단계다.
 * 설치만 끝난 상태에서는 [installed] 가 true 이고 [workspace] · [repository] 가 null 이다.
 */
data class TrackerLinkResponse(
    val provider: String,
    val installed: Boolean,
    val workspace: String?,
    val repository: String?,
    val htmlUrl: String?,
    val autoSyncSeverities: List<String>,
    val updatedAt: Instant?
)

/** `link` 가 없으면 `null` 을 실어 보낸다 — 빈 객체와 구분되어야 화면이 "연결 안 됨"을 그린다. */
data class TrackerLinkEnvelope(val link: TrackerLinkResponse?)

/**
 * `PUT /api/projects/{projectId}/tracker-link` 의 본문.
 *
 * `provider` 가 경로가 아니라 값으로 들어오는 것이 이 계약의 요점이다.
 */
data class TrackerLinkUpsertRequest(
    @field:NotBlank val provider: String = "",
    @field:NotBlank val workspace: String = "",
    @field:NotBlank val repository: String = "",
    /** 비우면 자동 `sync` 를 끈 것이다. 생략하면 기본값(BLOCKER, CRITICAL)이 유지된다. */
    val autoSyncSeverities: List<String>? = null
)

data class TrackerInstallUrlResponse(val url: String)

/** 설치된 저장소 후보 한 건. */
data class TrackerRepositoryResponse(
    val workspace: String,
    val repository: String,
    val htmlUrl: String,
    val private: Boolean
)

data class TrackerRepositoryPageResponse(val items: List<TrackerRepositoryResponse>)

/**
 * 결함 하나의 외부 tracker 상태. `IssueResponse.tracker` 와
 * `POST /api/issues/{issueId}/tracker-sync` 응답이 같은 모양을 쓴다.
 *
 * [externalKey] 는 GitHub 이면 이슈 번호다. [syncState] 가 `PENDING` 인 동안에는 아직 null 이다.
 */
data class IssueTrackerResponse(
    val provider: String,
    val externalKey: String?,
    val url: String?,
    val syncState: String,
    val syncError: String?,
    val syncedAt: Instant?
)

/**
 * `POST /api/issues/{issueId}/tracker-sync` 의 응답 봉투.
 *
 * `tracker` 는 **non-null 이다.** 연결이 없으면 400 이고, 실패했으면 `syncState="FAILED"` 를 실은
 * 상태가 온다 — 어느 갈래로 끝나든 실을 상태가 있다. nullable 로 두면 OpenAPI 가 optional 로
 * 나가고 artel-home 이 없는 분기를 하나 더 만든다.
 */
data class IssueTrackerEnvelope(val tracker: IssueTrackerResponse)
