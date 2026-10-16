package kr.artel.orchestration.issue.discussion

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.ForbiddenException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

@Service
class DiscussionService(
    private val repository: DiscussionRepository,
    private val access: ProjectAccessService,
    private val members: ProjectMemberRepository,
    private val users: AppUserRepository,
    private val transactions: TransactionalOperator,
    private val objectMapper: ObjectMapper,
) {
    suspend fun list(
        projectId: Long,
        userId: Long,
        state: String?,
        mine: Boolean,
        subscribed: Boolean,
        issueId: Long?,
        query: String?,
        cursor: String?,
        size: Int,
    ): DiscussionPage<DiscussionSummary> {
        access.requireMember(projectId, userId)
        pageSize(size)
        if (state != null && state !in DISCUSSION_STATES) invalid("state")
        val search = query.orEmpty().trim()
        if (search.length > 200) invalid("query")
        val parsed =
            cursor?.let {
                val parts = it.split(':')
                if (parts.size != 2 || parts[0] !in setOf("0", "1")) invalid("cursor")
                (parts[0] == "1") to id(parts[1])
            }
        val rows =
            repository.list(
                projectId,
                userId,
                DiscussionFilter(state, mine, subscribed, issueId, search),
                parsed,
                size + 1,
            )
        val items = rows.take(size).map { it.summary }
        return DiscussionPage(
            items,
            if (rows.size > size) items.last().let { "${if(it.pinned) 1 else 0}:${it.id}" }
            else null,
            rows.size > size,
        )
    }

    suspend fun issueOptions(
        projectId: Long,
        userId: Long,
        query: String?,
        cursor: String?,
        size: Int,
    ): DiscussionPage<DiscussionIssue> {
        access.requireMember(projectId, userId)
        pageSize(size)
        val search = query.orEmpty().trim()
        if (search.length > 200) invalid("query")
        val items =
            repository.issueOptions(
                projectId,
                search,
                cursor?.let(::id) ?: Long.MAX_VALUE,
                size + 1,
            )
        return DiscussionPage(
            items.take(size),
            if (items.size > size) items[size - 1].id else null,
            items.size > size,
        )
    }

    suspend fun detail(projectId: Long, discussionId: Long, userId: Long): DiscussionDetail {
        val row = accessible(projectId, discussionId, userId)
        val owner = isOwner(projectId, userId)
        val summary = row.summary
        val decision =
            row.decisionId
                ?.let { repository.comment(discussionId, it, userId) }
                ?.let { comments(listOf(it), userId).single() }
        return DiscussionDetail(
            summary.id,
            summary.title,
            summary.excerpt,
            summary.purpose,
            summary.state,
            summary.pinned,
            summary.author,
            summary.issueCount,
            summary.commentCount,
            summary.createdAt,
            summary.updatedAt,
            summary.subscribed,
            summary.version,
            row.body,
            repository.issues(discussionId),
            people(row.mentionIds).values.toList(),
            decision,
            owner || summary.author.id == userId.toString(),
            owner,
        )
    }

    suspend fun create(projectId: Long, userId: Long, request: DiscussionDraft): DiscussionDetail =
        transactions.executeAndAwait {
            access.requireMember(projectId, userId)
            val content =
                content(
                    projectId,
                    request.title,
                    request.body,
                    request.purpose,
                    request.issueIds,
                    request.mentionedUserIds,
                )
            val fingerprint = hash(content)
            val (discussionId, savedHash, inserted) =
                repository.create(
                    projectId,
                    userId,
                    content,
                    requestId(request.clientRequestId),
                    fingerprint,
                )
            if (savedHash != fingerprint) throw ConflictException("같은 요청 ID에 다른 내용이 사용되었습니다.")
            if (inserted) {
                repository.replaceLinks(discussionId, content.issueIds)
                repository.subscribe(discussionId, userId, true)
            }
            detail(projectId, discussionId, userId)
        }

    suspend fun edit(
        projectId: Long,
        discussionId: Long,
        userId: Long,
        request: DiscussionEdit,
    ): DiscussionDetail =
        transactions.executeAndAwait {
            requireEditor(projectId, accessible(projectId, discussionId, userId), userId)
            val content =
                content(
                    projectId,
                    request.title,
                    request.body,
                    request.purpose,
                    request.issueIds,
                    request.mentionedUserIds,
                )
            if (!repository.edit(discussionId, content, request.version)) conflict()
            repository.replaceLinks(discussionId, content.issueIds)
            detail(projectId, discussionId, userId)
        }

    suspend fun state(
        projectId: Long,
        discussionId: Long,
        userId: Long,
        state: String,
    ): DiscussionDetail =
        transactions.executeAndAwait {
            requireEditor(projectId, accessible(projectId, discussionId, userId), userId)
            if (state !in DISCUSSION_STATES) invalid("state")
            repository.setState(discussionId, state)
            detail(projectId, discussionId, userId)
        }

    suspend fun pin(
        projectId: Long,
        discussionId: Long,
        userId: Long,
        enabled: Boolean,
    ): DiscussionDetail =
        transactions.executeAndAwait {
            accessible(projectId, discussionId, userId)
            if (!isOwner(projectId, userId)) throw ForbiddenException()
            repository.setPin(discussionId, enabled)
            detail(projectId, discussionId, userId)
        }

    suspend fun decision(
        projectId: Long,
        discussionId: Long,
        userId: Long,
        commentId: String?,
    ): DiscussionDetail =
        transactions.executeAndAwait {
            requireEditor(projectId, accessible(projectId, discussionId, userId), userId)
            val parsed = commentId?.let(::id)
            if (parsed != null)
                repository.comment(discussionId, parsed, userId) ?: throw NotFoundException()
            repository.setDecision(discussionId, parsed)
            detail(projectId, discussionId, userId)
        }

    suspend fun subscribe(
        projectId: Long,
        discussionId: Long,
        userId: Long,
        enabled: Boolean,
    ): DiscussionDetail =
        transactions.executeAndAwait {
            accessible(projectId, discussionId, userId)
            repository.subscribe(discussionId, userId, enabled)
            detail(projectId, discussionId, userId)
        }

    suspend fun listComments(
        projectId: Long,
        discussionId: Long,
        userId: Long,
        cursor: String?,
        size: Int,
    ): DiscussionPage<DiscussionComment> {
        accessible(projectId, discussionId, userId)
        pageSize(size)
        val rows = repository.comments(discussionId, userId, cursor?.let(::id) ?: 0, size + 1)
        val items = comments(rows.take(size), userId)
        return DiscussionPage(
            items,
            if (rows.size > size) items.last().id else null,
            rows.size > size,
        )
    }

    suspend fun addComment(
        projectId: Long,
        discussionId: Long,
        userId: Long,
        request: CommentDraft,
    ): DiscussionComment =
        transactions.executeAndAwait {
            accessible(projectId, discussionId, userId)
            val content =
                CommentContent(
                    text(request.body, 10000, "body"),
                    mentionIds(projectId, request.mentionedUserIds),
                )
            val fingerprint = hash(content)
            val (commentId, savedHash) =
                repository.createComment(
                    discussionId,
                    userId,
                    content,
                    requestId(request.clientRequestId),
                    fingerprint,
                )
            if (savedHash != fingerprint) throw ConflictException("같은 요청 ID에 다른 내용이 사용되었습니다.")
            comments(listOf(repository.comment(discussionId, commentId, userId)!!), userId).single()
        }

    suspend fun editComment(
        projectId: Long,
        discussionId: Long,
        commentId: Long,
        userId: Long,
        request: CommentEdit,
    ): DiscussionComment =
        transactions.executeAndAwait {
            accessible(projectId, discussionId, userId)
            val comment =
                repository.comment(discussionId, commentId, userId) ?: throw NotFoundException()
            if (comment.author.id != userId.toString()) throw ForbiddenException()
            val content =
                CommentContent(
                    text(request.body, 10000, "body"),
                    mentionIds(projectId, request.mentionedUserIds),
                )
            if (!repository.editComment(commentId, content, request.version)) conflict()
            repository.touch(discussionId)
            comments(listOf(repository.comment(discussionId, commentId, userId)!!), userId).single()
        }

    suspend fun react(
        projectId: Long,
        discussionId: Long,
        commentId: Long,
        userId: Long,
        enabled: Boolean,
    ): DiscussionComment =
        transactions.executeAndAwait {
            accessible(projectId, discussionId, userId)
            repository.comment(discussionId, commentId, userId) ?: throw NotFoundException()
            repository.react(commentId, userId, enabled)
            comments(listOf(repository.comment(discussionId, commentId, userId)!!), userId).single()
        }

    private suspend fun accessible(projectId: Long, id: Long, userId: Long): DiscussionRow {
        access.requireMember(projectId, userId)
        return repository.find(projectId, id, userId) ?: throw NotFoundException()
    }

    private suspend fun requireEditor(projectId: Long, row: DiscussionRow, userId: Long) {
        if (row.summary.author.id != userId.toString() && !isOwner(projectId, userId))
            throw ForbiddenException()
    }

    private suspend fun isOwner(projectId: Long, userId: Long) =
        access.member(projectId, userId)?.role == "OWNER"

    private suspend fun content(
        projectId: Long,
        title: String,
        body: String,
        purpose: String,
        issues: List<String>,
        mentions: List<String>,
    ): DiscussionContent {
        if (purpose !in DISCUSSION_PURPOSES) invalid("purpose")
        if (issues.size > 50) invalid("issueIds")
        val ids = issues.map(::id).distinct().sorted()
        if (repository.validIssueIds(projectId, ids).toSet() != ids.toSet())
            throw NotFoundException()
        return DiscussionContent(
            text(title, 200, "title"),
            text(body, 20000, "body"),
            purpose,
            ids,
            mentionIds(projectId, mentions),
        )
    }

    private suspend fun mentionIds(projectId: Long, values: List<String>): List<Long> {
        if (values.size > 50) invalid("mentionedUserIds")
        val ids = values.map(::id).distinct().sorted()
        if (ids.isEmpty()) return ids
        val allowed = members.findByProjectId(projectId).toList().map { it.appUserId }.toSet()
        if (ids.any { it !in allowed }) throw NotFoundException()
        return ids
    }

    private suspend fun people(ids: List<Long>): Map<Long, DiscussionPerson> =
        if (ids.isEmpty()) emptyMap()
        else
            users.findAllById(ids.distinct()).toList().associate {
                it.id!! to DiscussionPerson(it.id.toString(), it.nickname, it.userTag)
            }

    private suspend fun comments(rows: List<CommentRow>, userId: Long): List<DiscussionComment> {
        val mentions = people(rows.flatMap { it.mentionIds })
        return rows.map { row ->
            DiscussionComment(
                row.id.toString(),
                row.body,
                row.author,
                row.mentionIds.mapNotNull { mentions[it] },
                row.createdAt,
                row.updatedAt,
                row.version,
                row.reactionCount,
                row.reacted,
                row.author.id == userId.toString(),
            )
        }
    }

    private fun hash(value: Any): String =
        MessageDigest.getInstance("SHA-256")
            .digest(objectMapper.writeValueAsBytes(value))
            .joinToString("") { "%02x".format(it) }

    private fun requestId(value: String): UUID =
        try {
            UUID.fromString(value).also {
                if (it.toString() != value.lowercase()) invalid("clientRequestId")
            }
        } catch (_: IllegalArgumentException) {
            invalid("clientRequestId")
        }

    private fun text(value: String, max: Int, field: String): String =
        value.trim().also { if (it.isEmpty() || it.length > max) invalid(field) }

    private fun id(value: String): Long = value.toLongOrNull()?.takeIf { it > 0 } ?: invalid("id")

    private fun pageSize(size: Int) {
        if (size !in 1..100) invalid("size")
    }

    private fun invalid(field: String): Nothing = throw BadRequestException("유효하지 않은 값: $field")

    private fun conflict(): Nothing = throw ConflictException("다른 변경이 저장되었습니다. 새로고침 후 다시 시도하세요.")
}
