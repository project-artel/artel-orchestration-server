package kr.artel.orchestration.projectagent.controller

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.projectagent.dto.CreateProjectAgentConversationRequest
import kr.artel.orchestration.projectagent.dto.ProjectAgentConversationList
import kr.artel.orchestration.projectagent.dto.ProjectAgentConversationResponse
import kr.artel.orchestration.projectagent.dto.ProjectAgentEventRequest
import kr.artel.orchestration.projectagent.dto.ProjectAgentMessageList
import kr.artel.orchestration.projectagent.dto.ProjectAgentMessageResponse
import kr.artel.orchestration.projectagent.dto.ProjectAgentToolResultRequest
import kr.artel.orchestration.projectagent.dto.RenameProjectAgentConversationRequest
import kr.artel.orchestration.projectagent.dto.SendProjectAgentMessageRequest
import kr.artel.orchestration.projectagent.service.ProjectAgentService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * project agent 대화 API(ARTEL-1127). browser 는 메시지와 tool 결과를 여기로 보내고, 대화의 진행은 SSE 로 받는다.
 * 규칙은 [ProjectAgentService] 에 있다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/agent/conversations")
class ProjectAgentController(private val service: ProjectAgentService) {

    @GetMapping
    suspend fun list(@PathVariable projectId: Long, @CurrentUserId appUserId: Long): ProjectAgentConversationList =
        service.list(projectId, appUserId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable projectId: Long,
        @RequestBody request: CreateProjectAgentConversationRequest,
        @CurrentUserId appUserId: Long,
    ): ProjectAgentConversationResponse = service.create(projectId, appUserId, request)

    @GetMapping("/{conversationId}")
    suspend fun get(
        @PathVariable projectId: Long,
        @PathVariable conversationId: Long,
        @CurrentUserId appUserId: Long,
    ): ProjectAgentConversationResponse = service.get(projectId, conversationId, appUserId)

    @PatchMapping("/{conversationId}")
    suspend fun rename(
        @PathVariable projectId: Long,
        @PathVariable conversationId: Long,
        @RequestBody request: RenameProjectAgentConversationRequest,
        @CurrentUserId appUserId: Long,
    ): ProjectAgentConversationResponse = service.rename(projectId, conversationId, appUserId, request)

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable projectId: Long,
        @PathVariable conversationId: Long,
        @CurrentUserId appUserId: Long,
    ) {
        service.delete(projectId, conversationId, appUserId)
    }

    @GetMapping("/{conversationId}/messages")
    suspend fun messages(
        @PathVariable projectId: Long,
        @PathVariable conversationId: Long,
        @CurrentUserId appUserId: Long,
    ): ProjectAgentMessageList = service.messages(projectId, conversationId, appUserId)

    /** `message` · `conversation` 이벤트와 15초 간격 comment 줄. 과거 기록은 다시 보내지 않는다. */
    @GetMapping("/{conversationId}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun stream(
        @PathVariable projectId: Long,
        @PathVariable conversationId: Long,
        @CurrentUserId appUserId: Long,
    ): Flow<ServerSentEvent<Any>> = service.stream(projectId, conversationId, appUserId)

    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun sendMessage(
        @PathVariable projectId: Long,
        @PathVariable conversationId: Long,
        @RequestBody request: SendProjectAgentMessageRequest,
        @CurrentUserId appUserId: Long,
    ): ProjectAgentMessageResponse = service.sendMessage(projectId, conversationId, appUserId, request)

    @PostMapping("/{conversationId}/tool-results")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun submitToolResult(
        @PathVariable projectId: Long,
        @PathVariable conversationId: Long,
        @RequestBody request: ProjectAgentToolResultRequest,
        @CurrentUserId appUserId: Long,
    ): ProjectAgentMessageResponse = service.submitToolResult(projectId, conversationId, appUserId, request)

    @PostMapping("/{conversationId}/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun sendEvent(
        @PathVariable projectId: Long,
        @PathVariable conversationId: Long,
        @RequestBody request: ProjectAgentEventRequest,
        @CurrentUserId appUserId: Long,
    ): ProjectAgentMessageResponse = service.sendEvent(projectId, conversationId, appUserId, request)

    @PostMapping("/{conversationId}/cancel")
    suspend fun cancel(
        @PathVariable projectId: Long,
        @PathVariable conversationId: Long,
        @CurrentUserId appUserId: Long,
    ): ProjectAgentConversationResponse = service.cancel(projectId, conversationId, appUserId)
}
