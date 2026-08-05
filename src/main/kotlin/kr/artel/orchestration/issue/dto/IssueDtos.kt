package kr.artel.orchestration.issue.dto

import com.fasterxml.jackson.databind.JsonNode
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
    val resolvedBy: String?
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
