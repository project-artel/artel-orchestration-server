package kr.artel.orchestration.projectagent.service

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.projectagent.dto.CreateProjectAgentConversationRequest
import kr.artel.orchestration.projectagent.dto.EventPayload
import kr.artel.orchestration.projectagent.dto.NoticeKind
import kr.artel.orchestration.projectagent.dto.NoticePayload
import kr.artel.orchestration.projectagent.dto.ProjectAgentConversationList
import kr.artel.orchestration.projectagent.dto.ProjectAgentConversationResponse
import kr.artel.orchestration.projectagent.dto.ProjectAgentEventRequest
import kr.artel.orchestration.projectagent.dto.ProjectAgentMessageList
import kr.artel.orchestration.projectagent.dto.ProjectAgentMessageResponse
import kr.artel.orchestration.projectagent.dto.ProjectAgentToolResultRequest
import kr.artel.orchestration.projectagent.dto.RenameProjectAgentConversationRequest
import kr.artel.orchestration.projectagent.dto.SendProjectAgentMessageRequest
import kr.artel.orchestration.projectagent.dto.TOOL_RESULT_STATUSES
import kr.artel.orchestration.projectagent.dto.ToolResultPayload
import kr.artel.orchestration.projectagent.dto.UserPayload
import kr.artel.orchestration.projectagent.entity.ProjectAgentConversationEntity
import kr.artel.orchestration.projectagent.entity.ProjectAgentMessageEntity
import kr.artel.orchestration.projectagent.entity.ProjectAgentRole
import kr.artel.orchestration.projectagent.entity.ProjectAgentStatus
import kr.artel.orchestration.projectagent.repository.ProjectAgentConversationRepository
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service

/**
 * project agent 대화의 API 쪽 규칙(ARTEL-1127). 접근은 프로젝트 참여와 대화 소유자로 인가하고, 어느 쪽이든
 * 아니면 대화가 없는 것처럼 404 를 준다.
 *
 * 입력(메시지 · 이벤트 · 마지막 tool 결과)이 오면 대화를 `RUNNING` 으로 넘기고 [ProjectAgentStepRunner] 에
 * step 을 맡긴 뒤 바로 돌아온다. 같은 대화를 건드리는 저장은 [ProjectAgentLocks] 로 한 번에 하나씩 한다.
 * 동시 입력을 최종으로 막는 것은 status 를 조건부로 바꾸는 UPDATE 이고, lock 은 한 프로세스 안의 쓰기 순서를 맞춘다.
 *
 * 상태를 바꾸는 구간(claim → 저장 → step 시작)은 [NonCancellable] 로 감싼다. browser 가 연결을 끊으면 WebFlux 가
 * 요청 coroutine 을 취소하는데, 구간 중간에서 끊기면 대화가 `RUNNING` 이나 답 없는 `AWAITING_TOOL` 에 갇힌다.
 */
@Service
class ProjectAgentService(
    private val projectAccess: ProjectAccessService,
    private val conversations: ProjectAgentConversationRepository,
    private val store: ProjectAgentConversationStore,
    private val runner: ProjectAgentStepRunner,
    private val locks: ProjectAgentLocks,
    private val hub: ProjectAgentStreamHub,
    private val users: AppUserRepository,
) {

    suspend fun list(projectId: Long, appUserId: Long): ProjectAgentConversationList {
        projectAccess.requireMember(projectId, appUserId)
        val items = conversations.findByProjectIdAndAppUserIdOrderByLastMessageAtDesc(projectId, appUserId).toList()
        return ProjectAgentConversationList(items.map(store::toResponse))
    }

    suspend fun create(
        projectId: Long,
        appUserId: Long,
        request: CreateProjectAgentConversationRequest,
    ): ProjectAgentConversationResponse {
        projectAccess.requireMember(projectId, appUserId)
        val now = store.now()
        val saved = conversations.save(
            ProjectAgentConversationEntity(
                projectId = projectId,
                appUserId = appUserId,
                pageCatalog = catalogOrNull(request.pageCatalog)?.let(store::toJson),
                createdAt = now,
                lastMessageAt = now,
            )
        )
        return store.toResponse(saved)
    }

    suspend fun get(projectId: Long, conversationId: Long, appUserId: Long): ProjectAgentConversationResponse =
        store.toResponse(access(projectId, conversationId, appUserId))

    suspend fun rename(
        projectId: Long,
        conversationId: Long,
        appUserId: Long,
        request: RenameProjectAgentConversationRequest,
    ): ProjectAgentConversationResponse {
        val title = request.title.trim()
        if (title.isEmpty() || title.length > MAX_TITLE_LENGTH) {
            throw BadRequestException("제목은 1자 이상 ${MAX_TITLE_LENGTH}자 이하로 적어 주세요.")
        }
        access(projectId, conversationId, appUserId)
        conversations.updateTitle(conversationId, title)
        val saved = store.toResponse(store.reload(conversationId))
        hub.publish(saved)
        return saved
    }

    suspend fun delete(projectId: Long, conversationId: Long, appUserId: Long) {
        access(projectId, conversationId, appUserId)
        withContext(NonCancellable) {
            locks.withLock(conversationId) {
                runner.abort(conversationId)
                conversations.deleteById(conversationId)
                // 지워진 대화의 stream 이 heartbeat 만 보내며 남지 않도록 끝낸다.
                hub.close(conversationId)
            }
        }
    }

    suspend fun messages(projectId: Long, conversationId: Long, appUserId: Long): ProjectAgentMessageList {
        access(projectId, conversationId, appUserId)
        return ProjectAgentMessageList(store.history(conversationId).map(store::toResponse))
    }

    suspend fun stream(projectId: Long, conversationId: Long, appUserId: Long): Flow<ServerSentEvent<Any>> {
        access(projectId, conversationId, appUserId)
        return hub.subscribe(conversationId)
    }

    /** 사용자 입력. `IDLE` 또는 `ERROR` 일 때만 받는다. 저장한 USER 메시지를 돌려준다. */
    suspend fun sendMessage(
        projectId: Long,
        conversationId: Long,
        appUserId: Long,
        request: SendProjectAgentMessageRequest,
    ): ProjectAgentMessageResponse {
        if (request.text.isBlank()) throw BadRequestException("메시지를 입력해 주세요.")
        val pageCatalog = catalogOrNull(request.pageCatalog)
        access(projectId, conversationId, appUserId)
        return guarded(conversationId) {
            claim(
                conversationId,
                title = request.text.trim().take(MAX_TITLE_LENGTH_FROM_TEXT),
                pageCatalog = pageCatalog,
            )
            val stored = appendOrRelease(
                conversationId,
                ProjectAgentRole.USER,
                request.text,
                request.page?.takeUnless { it.isNull }?.let { UserPayload(it) },
            )
            afterInput(conversationId)
            store.toResponse(stored)
        }
    }

    /** browser 가 알리는 사건(job 완료 등). 규칙은 [sendMessage] 와 같다. */
    suspend fun sendEvent(
        projectId: Long,
        conversationId: Long,
        appUserId: Long,
        request: ProjectAgentEventRequest,
    ): ProjectAgentMessageResponse {
        if (request.kind.isBlank() || request.kind.length > MAX_KIND_LENGTH) {
            throw BadRequestException("kind 는 1자 이상 ${MAX_KIND_LENGTH}자 이하여야 합니다.")
        }
        if (request.summary.length > MAX_TEXT_LENGTH) {
            throw BadRequestException("summary 는 ${MAX_TEXT_LENGTH}자 이하여야 합니다.")
        }
        access(projectId, conversationId, appUserId)
        return guarded(conversationId) {
            claim(conversationId, title = null, pageCatalog = null)
            val stored = appendOrRelease(
                conversationId,
                ProjectAgentRole.EVENT,
                request.summary,
                EventPayload(request.kind, request.data?.takeUnless { it.isNull }),
            )
            afterInput(conversationId)
            store.toResponse(stored)
        }
    }

    /**
     * tool call 하나의 결과. 마지막 ASSISTANT 메시지에서 답을 기다리는 호출만 받는다. 그 메시지의 마지막
     * 호출에 답하면 다음 step 이 시작된다.
     */
    suspend fun submitToolResult(
        projectId: Long,
        conversationId: Long,
        appUserId: Long,
        request: ProjectAgentToolResultRequest,
    ): ProjectAgentMessageResponse {
        if (request.status !in TOOL_RESULT_STATUSES) {
            throw BadRequestException("status 는 ${TOOL_RESULT_STATUSES.joinToString()} 중 하나여야 합니다.")
        }
        if (request.callId.isBlank()) throw BadRequestException("callId 가 필요합니다.")
        if ((request.error?.length ?: 0) > MAX_TEXT_LENGTH) {
            throw BadRequestException("error 는 ${MAX_TEXT_LENGTH}자 이하여야 합니다.")
        }
        access(projectId, conversationId, appUserId)
        return guarded(conversationId) {
            val conversation = store.reload(conversationId)
            val pending = store.pendingCallIds(store.history(conversationId))
            if (conversation.status != ProjectAgentStatus.AWAITING_TOOL || request.callId !in pending) {
                throw ToolCallNotPendingException()
            }
            val stored = store.append(
                conversationId,
                ProjectAgentRole.TOOL_RESULT,
                payload = ToolResultPayload(
                    callId = request.callId,
                    status = request.status,
                    result = request.result?.takeUnless { it.isNull },
                    error = request.error,
                    page = request.page?.takeUnless { it.isNull },
                ),
            )
            // 방금 저장한 결과까지 센 뒤에도 답 없는 호출이 남았는지로 마지막인지 정한다.
            val isLast = store.pendingCallIds(store.history(conversationId)).isEmpty()
            if (isLast) {
                conversations.claim(conversationId, listOf(ProjectAgentStatus.AWAITING_TOOL), store.now(), null, null)
                runner.start(conversationId)
            } else {
                conversations.move(
                    conversationId, listOf(ProjectAgentStatus.AWAITING_TOOL), ProjectAgentStatus.AWAITING_TOOL, store.now(),
                )
            }
            if (isLast) store.publishConversation(conversationId)
            store.toResponse(stored)
        }
    }

    /**
     * 대화를 멈춘다. 도는 step 이 있으면 끊고, 답 없는 tool call 에는 `cancelled` 결과를 채운 뒤 NOTICE
     * `stopped` 를 남기고 `IDLE` 로 돌린다. model 은 부르지 않는다.
     */
    suspend fun cancel(projectId: Long, conversationId: Long, appUserId: Long): ProjectAgentConversationResponse {
        access(projectId, conversationId, appUserId)
        return guarded(conversationId) {
            runner.abort(conversationId)
            for (callId in store.pendingCallIds(store.history(conversationId))) {
                store.append(
                    conversationId,
                    ProjectAgentRole.TOOL_RESULT,
                    payload = ToolResultPayload(callId = callId, status = "cancelled"),
                )
            }
            store.append(
                conversationId,
                ProjectAgentRole.NOTICE,
                ProjectAgentNotices.text(NoticeKind.STOPPED, localeOf(appUserId)),
                NoticePayload(NoticeKind.STOPPED),
            )
            conversations.move(conversationId, ProjectAgentStatus.ALL, ProjectAgentStatus.IDLE, store.now())
            store.publishConversation(conversationId)
            store.toResponse(store.reload(conversationId))
        }
    }

    /** 기동 때 `RUNNING` 으로 남은 대화(step 호출이 프로세스와 함께 죽었다)를 `ERROR` 로 돌린다. */
    suspend fun recoverInterrupted(): Int {
        val interrupted = conversations.findByStatus(ProjectAgentStatus.RUNNING).toList()
        interrupted.forEach { runner.markError(it.id!!) }
        return interrupted.size
    }

    /** 취소되지 않는 구간에서 대화 lock 을 잡고 [block] 을 실행한다. */
    private suspend fun <T> guarded(conversationId: Long, block: suspend () -> T): T =
        withContext(NonCancellable) { locks.withLock(conversationId, block) }

    /** page catalog 는 `pages` 배열을 가진 object 여야 한다. 없거나 JSON null 이면 없는 것으로 본다. */
    private fun catalogOrNull(catalog: JsonNode?): JsonNode? {
        if (catalog == null || catalog.isNull) return null
        if (!catalog.isObject || !catalog.get("pages").let { it != null && it.isArray }) {
            throw BadRequestException("pageCatalog 는 pages 배열을 가진 object 여야 합니다.")
        }
        return catalog
    }

    private suspend fun access(projectId: Long, conversationId: Long, appUserId: Long): ProjectAgentConversationEntity {
        projectAccess.requireMember(projectId, appUserId)
        return store.owned(projectId, conversationId, appUserId)
    }

    /** `IDLE` · `ERROR` 에서 `RUNNING` 으로 넘긴다. 이미 다른 입력이 잡고 있으면 409. */
    private suspend fun claim(conversationId: Long, title: String?, pageCatalog: JsonNode?) {
        val claimed = conversations.claim(
            conversationId,
            listOf(ProjectAgentStatus.IDLE, ProjectAgentStatus.ERROR),
            store.now(),
            title,
            pageCatalog?.let { store.toJson(it).asString() },
        )
        if (claimed == 0) throw ConversationBusyException()
    }

    /** 입력 저장이 실패하면 `RUNNING` 에 갇히지 않도록 `ERROR` 로 돌려 놓고 다시 던진다. */
    private suspend fun appendOrRelease(
        conversationId: Long,
        role: String,
        content: String,
        payload: Any?,
    ): ProjectAgentMessageEntity =
        try {
            store.append(conversationId, role, content, payload)
        } catch (error: Throwable) {
            // 정리하는 동안에도 취소가 끼어들지 못하게 하고, 원래 예외(취소 포함)는 삼키지 않고 다시 던진다.
            withContext(NonCancellable) {
                conversations.move(conversationId, listOf(ProjectAgentStatus.RUNNING), ProjectAgentStatus.ERROR, store.now())
            }
            throw error
        }

    private suspend fun afterInput(conversationId: Long) {
        store.publishConversation(conversationId)
        runner.start(conversationId)
    }

    private suspend fun localeOf(appUserId: Long): String =
        users.findById(appUserId)?.locale ?: ProjectAgentStepRunner.DEFAULT_LOCALE

    companion object {
        /** 대화 제목의 상한. 첫 USER 메시지를 이 길이로 자른다. */
        private const val MAX_TITLE_LENGTH_FROM_TEXT = 60
        private const val MAX_TITLE_LENGTH = 200
        private const val MAX_TEXT_LENGTH = 2000
        private const val MAX_KIND_LENGTH = 64
    }
}
