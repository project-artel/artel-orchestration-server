package kr.artel.orchestration.issue.dto

import com.fasterxml.jackson.databind.JsonNode
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
    val qaRunId: String? = null
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
