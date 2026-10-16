package kr.artel.orchestration.issue.discussion

import kr.artel.orchestration.auth.web.CurrentUserId
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/projects/{projectId}/issue-discussions")
class DiscussionController(private val service: DiscussionService) {
    @GetMapping
    suspend fun listIssueDiscussions(
        @PathVariable projectId: Long,
        @CurrentUserId userId: Long,
        @RequestParam(required = false) state: String?,
        @RequestParam(defaultValue = "false") mine: Boolean,
        @RequestParam(defaultValue = "false") subscribed: Boolean,
        @RequestParam(required = false) issueId: Long?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "30") size: Int,
    ) = service.list(projectId, userId, state, mine, subscribed, issueId, query, cursor, size)

    @GetMapping("/issue-options")
    suspend fun listDiscussionIssueOptions(
        @PathVariable projectId: Long,
        @CurrentUserId userId: Long,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "30") size: Int,
    ) = service.issueOptions(projectId, userId, query, cursor, size)

    @PostMapping
    suspend fun createIssueDiscussion(
        @PathVariable projectId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: DiscussionDraft,
    ) = service.create(projectId, userId, request)

    @GetMapping("/{discussionId}")
    suspend fun getIssueDiscussion(
        @PathVariable projectId: Long,
        @PathVariable discussionId: Long,
        @CurrentUserId userId: Long,
    ) = service.detail(projectId, discussionId, userId)

    @PatchMapping("/{discussionId}")
    suspend fun editIssueDiscussion(
        @PathVariable projectId: Long,
        @PathVariable discussionId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: DiscussionEdit,
    ) = service.edit(projectId, discussionId, userId, request)

    @PutMapping("/{discussionId}/state")
    suspend fun setIssueDiscussionState(
        @PathVariable projectId: Long,
        @PathVariable discussionId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: DiscussionStateRequest,
    ) = service.state(projectId, discussionId, userId, request.state)

    @PutMapping("/{discussionId}/pin")
    suspend fun pinIssueDiscussion(
        @PathVariable projectId: Long,
        @PathVariable discussionId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: EnabledRequest,
    ) = service.pin(projectId, discussionId, userId, request.enabled)

    @PutMapping("/{discussionId}/decision")
    suspend fun setIssueDiscussionDecision(
        @PathVariable projectId: Long,
        @PathVariable discussionId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: DecisionRequest,
    ) = service.decision(projectId, discussionId, userId, request.commentId)

    @PutMapping("/{discussionId}/subscription")
    suspend fun subscribeIssueDiscussion(
        @PathVariable projectId: Long,
        @PathVariable discussionId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: EnabledRequest,
    ) = service.subscribe(projectId, discussionId, userId, request.enabled)

    @GetMapping("/{discussionId}/comments")
    suspend fun listIssueDiscussionComments(
        @PathVariable projectId: Long,
        @PathVariable discussionId: Long,
        @CurrentUserId userId: Long,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "30") size: Int,
    ) = service.listComments(projectId, discussionId, userId, cursor, size)

    @PostMapping("/{discussionId}/comments")
    suspend fun createIssueDiscussionComment(
        @PathVariable projectId: Long,
        @PathVariable discussionId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: CommentDraft,
    ) = service.addComment(projectId, discussionId, userId, request)

    @PatchMapping("/{discussionId}/comments/{commentId}")
    suspend fun editIssueDiscussionComment(
        @PathVariable projectId: Long,
        @PathVariable discussionId: Long,
        @PathVariable commentId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: CommentEdit,
    ) = service.editComment(projectId, discussionId, commentId, userId, request)

    @PutMapping("/{discussionId}/comments/{commentId}/reaction")
    suspend fun reactToIssueDiscussionComment(
        @PathVariable projectId: Long,
        @PathVariable discussionId: Long,
        @PathVariable commentId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: EnabledRequest,
    ) = service.react(projectId, discussionId, commentId, userId, request.enabled)
}
