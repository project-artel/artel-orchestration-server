package kr.artel.orchestration.issue.discussion

import java.time.Instant
import kr.artel.orchestration.issue.category.CategoryTag

/** RESOLUTION 이슈 해결 요청 · QUESTION 확인 요청(검토자 승인) · DECISION 결정 사항. */
internal val DISCUSSION_PURPOSES = setOf("RESOLUTION", "QUESTION", "DECISION")
internal val DISCUSSION_STATES = setOf("DRAFT", "OPEN", "CLOSED")

data class DiscussionDraft(
    val title: String,
    val body: String,
    val purpose: String,
    val issueIds: List<String> = emptyList(),
    val mentionedUserIds: List<String> = emptyList(),
    val clientRequestId: String,
    /** true 면 Draft 로 저장한다. 공개는 state 를 OPEN 으로 바꾼다. */
    val draft: Boolean = false,
    val reviewerIds: List<String> = emptyList(),
    val categoryIds: List<String> = emptyList(),
    /** 글이 다루는 빌드 버전. 없으면 정하지 않은 글이다. */
    val gameBuildId: String? = null,
)

data class DiscussionEdit(
    val title: String,
    val body: String,
    val purpose: String,
    val issueIds: List<String> = emptyList(),
    val mentionedUserIds: List<String> = emptyList(),
    val version: Int,
    val reviewerIds: List<String> = emptyList(),
    val categoryIds: List<String> = emptyList(),
    val gameBuildId: String? = null,
)

data class CommentDraft(
    val body: String,
    val mentionedUserIds: List<String> = emptyList(),
    val clientRequestId: String,
    /** 대댓글이면 최상위 댓글 id. 대댓글의 대댓글은 400 이다. */
    val parentId: String? = null,
)

data class ApprovalRequest(val approved: Boolean)

data class DiscussionSettings(val approvalsRequired: Int)

data class DiscussionReviewer(val person: DiscussionPerson, val approvedAt: Instant?)

data class CommentEdit(
    val body: String,
    val mentionedUserIds: List<String> = emptyList(),
    val version: Int,
)

data class EnabledRequest(val enabled: Boolean)

data class DiscussionStateRequest(val state: String)

data class DecisionRequest(val commentId: String?)

data class DiscussionPerson(val id: String, val displayName: String, val userTag: String)

data class DiscussionIssue(
    val id: String,
    val title: String,
    val severity: String,
    val status: String,
    val qaTryId: String,
    val qaRunId: String?,
    /** 프로젝트 안 이슈 번호(V120). 화면의 `#` 뒤 값이다. */
    val number: String? = null,
)

data class DiscussionSummary(
    val id: String,
    val title: String,
    val excerpt: String,
    val purpose: String,
    val state: String,
    val pinned: Boolean,
    val author: DiscussionPerson,
    val issueCount: Long,
    val commentCount: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val subscribed: Boolean,
    val version: Int,
    val categories: List<CategoryTag> = emptyList(),
    val reviewerCount: Long = 0,
    val approvalCount: Long = 0,
    /** 프로젝트 안 글 번호(V121). 화면의 `#` 뒤 값이다. */
    val number: String? = null,
    val gameBuildId: String? = null,
    val buildVersion: String? = null,
)

data class DiscussionDetail(
    val id: String,
    val number: String?,
    val gameBuildId: String?,
    val buildVersion: String?,
    val title: String,
    val excerpt: String,
    val purpose: String,
    val state: String,
    val pinned: Boolean,
    val author: DiscussionPerson,
    val issueCount: Long,
    val commentCount: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val subscribed: Boolean,
    val version: Int,
    val body: String,
    val issues: List<DiscussionIssue>,
    val mentions: List<DiscussionPerson>,
    val decision: DiscussionComment?,
    /** 글 내용을 고칠 수 있다. 쓴 사람만이다 — 프로젝트 OWNER 도 남의 글은 고치지 못한다. */
    val canEdit: Boolean,
    val canPin: Boolean,
    val categories: List<CategoryTag>,
    val reviewers: List<DiscussionReviewer>,
    /** 확인 요청이 해결되는 승인 수(프로젝트 설정). */
    val approvalsRequired: Int,
    /** 지금 사용자가 이 글의 검토자다. */
    val canApprove: Boolean,
    /** 상태(공개 · 해결 · 다시 열기)와 결정 댓글을 다룰 수 있다. 쓴 사람과 프로젝트 OWNER. */
    val canManage: Boolean,
)

data class DiscussionComment(
    val id: String,
    val body: String,
    val author: DiscussionPerson,
    val mentions: List<DiscussionPerson>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Int,
    val reactionCount: Long,
    val reacted: Boolean,
    val canEdit: Boolean,
    val parentId: String? = null,
    /** 지울 수 있다(쓴 사람이나 프로젝트 OWNER). */
    val canDelete: Boolean = false,
)

data class DiscussionPage<T>(val items: List<T>, val nextCursor: String?, val hasMore: Boolean)

/** 쪽 번호로 나눈 글 목록. [page] 는 1 부터다. */
data class DiscussionNumberedPage(val items: List<DiscussionSummary>, val page: Int, val size: Int, val total: Long)

internal data class DiscussionRow(
    val summary: DiscussionSummary,
    val body: String,
    val mentionIds: List<Long>,
    val decisionId: Long?,
    val requestHash: String,
)

internal data class CommentRow(
    val id: Long,
    val body: String,
    val author: DiscussionPerson,
    val mentionIds: List<Long>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Int,
    val reactionCount: Long,
    val reacted: Boolean,
    val requestHash: String,
    val parentId: Long? = null,
)

internal data class DiscussionContent(
    val title: String,
    val body: String,
    val purpose: String,
    val issueIds: List<Long>,
    val mentionIds: List<Long>,
)

internal data class CommentContent(val body: String, val mentionIds: List<Long>, val parentId: Long? = null)

internal data class DiscussionFilter(
    val state: String?,
    val mine: Boolean,
    val subscribed: Boolean,
    val issueId: Long?,
    val query: String,
    val categoryId: Long? = null,
    /** "논의 중" 은 Draft 를 함께 보인다. */
    val includeDrafts: Boolean = false,
    val gameBuildId: Long? = null,
)
