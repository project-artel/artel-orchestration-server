package kr.artel.orchestration.issue.discussion

import java.time.Instant

internal val DISCUSSION_PURPOSES = setOf("SHARE", "QUESTION", "DECISION")
internal val DISCUSSION_STATES = setOf("OPEN", "CLOSED")

data class DiscussionDraft(
    val title: String,
    val body: String,
    val purpose: String,
    val issueIds: List<String> = emptyList(),
    val mentionedUserIds: List<String> = emptyList(),
    val clientRequestId: String,
)

data class DiscussionEdit(
    val title: String,
    val body: String,
    val purpose: String,
    val issueIds: List<String> = emptyList(),
    val mentionedUserIds: List<String> = emptyList(),
    val version: Int,
)

data class CommentDraft(
    val body: String,
    val mentionedUserIds: List<String> = emptyList(),
    val clientRequestId: String,
)

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
)

data class DiscussionDetail(
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
    val body: String,
    val issues: List<DiscussionIssue>,
    val mentions: List<DiscussionPerson>,
    val decision: DiscussionComment?,
    val canEdit: Boolean,
    val canPin: Boolean,
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
)

data class DiscussionPage<T>(val items: List<T>, val nextCursor: String?, val hasMore: Boolean)

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
)

internal data class DiscussionContent(
    val title: String,
    val body: String,
    val purpose: String,
    val issueIds: List<Long>,
    val mentionIds: List<Long>,
)

internal data class CommentContent(val body: String, val mentionIds: List<Long>)

internal data class DiscussionFilter(
    val state: String?,
    val mine: Boolean,
    val subscribed: Boolean,
    val issueId: Long?,
    val query: String,
)
