package kr.artel.orchestration.issue.dto

import com.fasterxml.jackson.databind.JsonNode
import kr.artel.orchestration.issue.category.CategoryTag
import kr.artel.orchestration.tracker.dto.IssueTrackerResponse
import java.time.Instant

/**
 * 이슈 한 건, 화면이 읽는 모양.
 *
 * id는 문자열이다(`QaTryResponse`와 같은 이유 — 64비트 값이 JSON 숫자로 나가면 정밀도를 잃는다).
 *
 * [detail]은 Agent가 보낸 payload 전체다. 서버는 구조를 강제하지 않으므로 화면은 아는 키만 골라
 * 그리고 나머지는 원본으로 보여준다.
 *
 * [reportedAt]과 [createdAt]이 갈리는 이유는 `V12__create_issue.sql`에 적힌 그대로다. 타임라인에
 * 쓸 시각은 [reportedAt]이다.
 *
 * [tracker]는 이 결함이 외부 이슈 tracker 로 나갔는지다(ARTEL-671). 프로젝트에 `link` 가 없거나
 * 아직 내보내지 않았으면 null 이다 — 이 목록과 실행 단위 목록이 같은 조립 경로를 지나므로 양쪽에
 * 함께 실린다.
 *
 * [qaRunId]는 이 이슈를 남긴 try 가 속한 부모 `qa_run`(ARTEL-722). `IssueEntity` 는 [qaTryId] 만
 * 들고 있어 매 페이지 배치 조회로 얻는다 — [tracker] 와 같은 자리, 같은 이유다. `qa_run` 이 생기기
 * 전의 단독 실행(하위호환) try 가 남긴 이슈는 null 이다.
 */
data class IssueResponse(
    val id: String,
    val qaTryId: String,
    val severity: String,
    val title: String,
    val detail: JsonNode,
    val status: String,
    val reportedAt: Instant,
    val createdAt: Instant?,
    val resolvedAt: Instant?,
    val resolvedBy: String?,
    val tracker: IssueTrackerResponse? = null,
    val qaRunId: String? = null,
    val origin: IssueOrigin? = null,
    /** 프로젝트 안 번호. 화면이 `#` 뒤에 보이는 값이다. [id] 는 API 가 가리키는 값으로 그대로 둔다. */
    val number: String? = null,
    val archivedAt: Instant? = null,
    val categories: List<CategoryTag> = emptyList()
)

/**
 * 이슈가 나온 자리 — 어느 빌드 버전의 어느 TR 에서, 어느 시나리오를 돌리다 나왔나. 목록 한 줄만 보고도 출처를
 * 알 수 있게 싣는다. run 없이 돈 예전 실행은 TR · 빌드가 비어 있다.
 */
data class IssueOrigin(
    val testScenarioId: String,
    val testScenarioTitle: String,
    val testRunId: String?,
    val testRunName: String?,
    val gameBuildId: String?,
    val buildVersion: String?,
    val buildLabel: String?,
    val model: String?,
    val qaRunStartedAt: Instant?
)

/** 프로젝트 이슈를 빌드 · TR · 심각도로 센 것. 목록 옆 거르기 칸이 개수를 같이 보이려고 쓴다. */
data class IssueFacetsResponse(
    val total: Long,
    /** 보관한 이슈 수. 위의 개수에는 들지 않는다. */
    val archived: Long,
    val builds: List<IssueBuildFacet>,
    val testRuns: List<IssueTestRunFacet>,
    val severities: Map<String, Long>
)

data class IssueBuildFacet(
    val gameBuildId: String?,
    val version: String?,
    val label: String?,
    val count: Long,
    val openCount: Long
)

data class IssueTestRunFacet(
    val testRunId: String?,
    val name: String?,
    val gameBuildId: String?,
    val count: Long,
    val openCount: Long
)

/**
 * 커서 페이지. 필드 이름은 `QaLogPageResponse`와 같지만 **정렬은 다르다** — 로그는 오름차순으로
 * 뒤집어 주고 이슈는 최신순 그대로 나간다. 페이지 넘기는 코드는 재사용해도 정렬은 재사용하면 안 된다.
 */
data class IssuePageResponse(
    val items: List<IssueResponse>,
    val nextBeforeId: String?,
    val hasMore: Boolean
)
