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
import kr.artel.orchestration.issue.category.IssueCategoryRepository
import kr.artel.orchestration.issue.category.IssueCategoryService
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
    private val categoryService: IssueCategoryService,
    private val categoryRepository: IssueCategoryRepository,
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
        categoryId: Long? = null,
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
                DiscussionFilter(state, mine, subscribed, issueId, search, categoryId),
                parsed,
                size + 1,
            )
        val page = rows.take(size).map { it.summary }
        val tags = categoryRepository.tagsOf(IssueCategoryRepository.Target.DISCUSSION, page.map { it.id.toLong() })
        val items = page.map { it.copy(categories = tags[it.id.toLong()].orEmpty()) }
        return DiscussionPage(
            items,
            if (rows.size > size) items.last().let { "${if(it.pinned) 1 else 0}:${it.id}" }
            else null,
            rows.size > size,
        )
    }

    /**
     * 쪽 번호로 나눈 글 목록. [state] 가 OPEN 이면 Draft 도 함께 보인다 — "논의 중" 탭이 아직 공개 전인 글까지
     * 모아 보기 때문이다. [page] 는 1 부터, [size] 는 1..100.
     */
    suspend fun page(
        projectId: Long,
        userId: Long,
        state: String?,
        mine: Boolean,
        subscribed: Boolean,
        query: String?,
        categoryId: Long?,
        page: Int,
        size: Int,
        gameBuildId: Long? = null,
    ): DiscussionNumberedPage {
        access.requireMember(projectId, userId)
        pageSize(size)
        if (page < 1) invalid("page")
        if (state != null && state !in DISCUSSION_STATES) invalid("state")
        val search = query.orEmpty().trim()
        if (search.length > 200) invalid("query")
        val filter = DiscussionFilter(state, mine, subscribed, null, search, categoryId, includeDrafts = state == "OPEN", gameBuildId = gameBuildId)
        val (rows, total) = repository.page(projectId, userId, filter, (page - 1) * size, size)
        val summaries = rows.map { it.summary }
        val tags = categoryRepository.tagsOf(IssueCategoryRepository.Target.DISCUSSION, summaries.map { it.id.toLong() })
        return DiscussionNumberedPage(summaries.map { it.copy(categories = tags[it.id.toLong()].orEmpty()) }, page, size, total)
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
        val reviewers = repository.reviewers(discussionId)
        val decision =
            row.decisionId
                ?.let { repository.comment(discussionId, it, userId) }
                ?.let { comments(listOf(it), userId).single() }
        return DiscussionDetail(
            summary.id,
            summary.number,
            summary.gameBuildId,
            summary.buildVersion,
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
            summary.author.id == userId.toString(),
            owner,
            categoryRepository.tagsOf(IssueCategoryRepository.Target.DISCUSSION, listOf(discussionId))[discussionId].orEmpty(),
            reviewers,
            repository.approvalsRequired(projectId),
            summary.purpose == "QUESTION" && summary.state == "OPEN" && summary.author.id != userId.toString(),
            owner || summary.author.id == userId.toString(),
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
                    if (request.draft) "DRAFT" else "OPEN",
                )
            if (savedHash != fingerprint) throw ConflictException("같은 요청 ID에 다른 내용이 사용되었습니다.")
            if (inserted) {
                repository.replaceLinks(discussionId, content.issueIds)
                repository.subscribe(discussionId, userId, true)
                repository.replaceReviewers(discussionId, memberIds(projectId, request.reviewerIds, "reviewerIds"))
                categoryService.assignToDiscussion(projectId, discussionId, request.categoryIds)
                repository.setBuild(discussionId, buildOf(projectId, request.gameBuildId))
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
            requireAuthor(accessible(projectId, discussionId, userId), userId)
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
            repository.replaceReviewers(discussionId, memberIds(projectId, request.reviewerIds, "reviewerIds"))
            categoryService.assignToDiscussion(projectId, discussionId, request.categoryIds)
            repository.setBuild(discussionId, buildOf(projectId, request.gameBuildId))
            detail(projectId, discussionId, userId)
        }

    suspend fun state(
        projectId: Long,
        discussionId: Long,
        userId: Long,
        state: String,
    ): DiscussionDetail =
        transactions.executeAndAwait {
            val row = accessible(projectId, discussionId, userId)
            requireEditor(projectId, row, userId)
            if (state !in DISCUSSION_STATES) invalid("state")
            // Draft 는 공개만 할 수 있고, 공개한 글을 Draft 로 되돌리지는 않는다.
            if (state == "DRAFT" && row.summary.state != "DRAFT") invalid("state")
            // 확인 요청은 승인이 모여야만 해결된다. 손으로 닫지 못한다.
            if (state == "CLOSED" && row.summary.purpose == "QUESTION" && row.summary.state != "CLOSED")
                throw ConflictException("확인 요청은 승인이 정해진 수만큼 모여야 해결됩니다.")
            if (state == "OPEN" && row.summary.state == "CLOSED" && row.summary.purpose == "QUESTION")
                repository.clearApprovals(discussionId)
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

    /**
     * 검토자가 승인하거나 승인을 거둔다. 확인 요청 글의 승인이 프로젝트 설정 수 이상이 되면 글이 해결(CLOSED)된다.
     * 검토자가 아니면 403, 공개 중(OPEN)인 확인 요청이 아니면 409 다.
     */
    suspend fun approve(projectId: Long, discussionId: Long, userId: Long, approved: Boolean): DiscussionDetail =
        transactions.executeAndAwait {
            val row = accessible(projectId, discussionId, userId)
            if (row.summary.purpose != "QUESTION" || row.summary.state != "OPEN")
                throw ConflictException("공개 중인 확인 요청에만 승인할 수 있습니다.")
            // 자기 글은 자기가 승인하지 못한다.
            if (row.summary.author.id == userId.toString()) throw ForbiddenException()
            repository.setApproval(discussionId, userId, approved)
            if (approved && repository.approvalCount(discussionId) >= repository.approvalsRequired(projectId))
                repository.setState(discussionId, "CLOSED")
            detail(projectId, discussionId, userId)
        }

    suspend fun settings(projectId: Long, userId: Long): DiscussionSettings {
        access.requireMember(projectId, userId)
        return DiscussionSettings(repository.approvalsRequired(projectId))
    }

    /** 확인 요청이 해결되는 승인 수. 프로젝트 OWNER 만 바꾼다. */
    suspend fun updateSettings(projectId: Long, userId: Long, request: DiscussionSettings): DiscussionSettings {
        access.requireMember(projectId, userId)
        if (!isOwner(projectId, userId)) throw ForbiddenException()
        if (request.approvalsRequired !in 1..20) invalid("approvalsRequired")
        repository.setApprovalsRequired(projectId, request.approvalsRequired)
        return DiscussionSettings(request.approvalsRequired)
    }

    suspend fun addComment(
        projectId: Long,
        discussionId: Long,
        userId: Long,
        request: CommentDraft,
    ): DiscussionComment =
        transactions.executeAndAwait {
            accessible(projectId, discussionId, userId)
            // 대댓글은 최상위 댓글 아래 한 단계만이다.
            val parentId = request.parentId?.takeIf { it.isNotBlank() }?.let(::id)
            if (parentId != null) {
                val parent = repository.comment(discussionId, parentId, userId) ?: throw NotFoundException()
                if (parent.parentId != null) invalid("parentId")
            }
            val content =
                CommentContent(
                    text(request.body, 10000, "body"),
                    mentionIds(projectId, request.mentionedUserIds),
                    parentId,
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

    /** 댓글을 지운다. 쓴 사람이나 프로젝트 OWNER 만. 답글은 함께 지워진다. */
    suspend fun deleteComment(projectId: Long, discussionId: Long, commentId: Long, userId: Long) {
        transactions.executeAndAwait {
            accessible(projectId, discussionId, userId)
            val comment = repository.comment(discussionId, commentId, userId) ?: throw NotFoundException()
            if (comment.author.id != userId.toString() && !isOwner(projectId, userId)) throw ForbiddenException()
            repository.deleteComment(discussionId, commentId)
            repository.touch(discussionId)
        }
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

    /** 글 내용은 쓴 사람만 고친다. */
    private fun requireAuthor(row: DiscussionRow, userId: Long) {
        if (row.summary.author.id != userId.toString()) throw ForbiddenException()
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

    /** 글의 빌드 버전. 이 프로젝트의 빌드가 아니면 404 다. */
    private suspend fun buildOf(projectId: Long, raw: String?): Long? {
        val gameBuildId = raw?.takeIf { it.isNotBlank() }?.let(::id) ?: return null
        if (!repository.buildInProject(projectId, gameBuildId)) throw NotFoundException()
        return gameBuildId
    }

    private suspend fun mentionIds(projectId: Long, values: List<String>): List<Long> =
        memberIds(projectId, values, "mentionedUserIds")

    /** [values] 가 모두 이 프로젝트 멤버여야 한다. 아니면 404. 멘션 · 검토자가 같이 쓴다. */
    private suspend fun memberIds(projectId: Long, values: List<String>, field: String): List<Long> {
        if (values.size > 50) invalid(field)
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
        // 지우기는 쓴 사람과 프로젝트 OWNER 가 한다. 댓글들은 한 글의 것이라 프로젝트도 하나다.
        val owner = rows.isNotEmpty() && repository.projectOfComment(rows.first().id)?.let { isOwner(it, userId) } == true
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
                row.parentId?.toString(),
                row.author.id == userId.toString() || owner,
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
