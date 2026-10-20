package kr.artel.orchestration.projectagent.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.projectagent.agent.ProjectAgentStepClient
import kr.artel.orchestration.projectagent.agent.ProjectAgentStepRequest
import kr.artel.orchestration.projectagent.agent.ProjectAgentStepResponse
import kr.artel.orchestration.projectagent.agent.StepMessage
import kr.artel.orchestration.projectagent.agent.StepToolCall
import kr.artel.orchestration.projectagent.config.ProjectAgentProperties
import kr.artel.orchestration.projectagent.dto.AssistantPayload
import kr.artel.orchestration.projectagent.dto.NoticeKind
import kr.artel.orchestration.projectagent.dto.NoticePayload
import kr.artel.orchestration.projectagent.dto.ToolResultPayload
import kr.artel.orchestration.projectagent.dto.ToolCallRecord
import kr.artel.orchestration.projectagent.entity.ProjectAgentConversationEntity
import kr.artel.orchestration.projectagent.entity.ProjectAgentMessageEntity
import kr.artel.orchestration.projectagent.entity.ProjectAgentRole
import kr.artel.orchestration.projectagent.entity.ProjectAgentStatus
import kr.artel.orchestration.projectagent.repository.ProjectAgentConversationRepository
import kr.artel.orchestration.settings.service.FeatureModelService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * agent-server 의 step 호출 하나를 돌리고 결과를 대화에 저장한다(ARTEL-1127).
 *
 * [start] 는 호출한 요청이 202 를 돌려준 뒤에도 이어지도록 [ProjectAgentScope] 에서 coroutine 을 띄운다.
 * 한 대화에는 step 이 하나만 돈다 — 그 보장은 [ProjectAgentConversationRepository.claim] 과 `move` 가
 * status 를 조건부로 바꾸는 데서 나오고, 이 클래스는 호출자가 이미 `RUNNING` 으로 넘긴 대화만 받는다.
 *
 * 취소와 겹칠 수 있어 결과를 저장하기 직전에 "내가 아직 이 대화의 현재 step 인가"를 확인한다([runs]).
 * 취소된 step 이 늦게 응답해도 그 결과는 버려진다.
 */
@Component
class ProjectAgentStepRunner(
    private val scope: ProjectAgentScope,
    private val store: ProjectAgentConversationStore,
    private val conversations: ProjectAgentConversationRepository,
    private val locks: ProjectAgentLocks,
    private val client: ProjectAgentStepClient,
    private val featureModels: FeatureModelService,
    private val users: AppUserRepository,
    private val properties: ProjectAgentProperties,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(ProjectAgentStepRunner::class.java)

    /** 대화 id → 지금 도는 step 의 Job. */
    private val runs = ConcurrentHashMap<Long, Job>()

    fun start(conversationId: Long) {
        val job = scope.launch(CoroutineStart.LAZY) { run(conversationId) }
        runs[conversationId] = job
        job.invokeOnCompletion { runs.remove(conversationId, job) }
        job.start()
    }

    /** 도는 step 이 있으면 취소하고, 늦게 오는 결과가 저장되지 않게 한다. */
    fun abort(conversationId: Long) {
        runs.remove(conversationId)?.cancel()
    }

    private suspend fun run(conversationId: Long) {
        val self = currentCoroutineContext()[Job]
        val request = try {
            buildRequest(store.reload(conversationId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("project agent step 요청을 만들지 못했다 [conversation: {}]", conversationId, error)
            fail(conversationId, self)
            return
        }
        val response = try {
            client.step(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("project agent step 호출 실패 [conversation: {}]: {}", conversationId, error.message)
            fail(conversationId, self)
            return
        }
        try {
            complete(conversationId, self, request.toolsEnabled, response)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("project agent step 결과를 저장하지 못했다 [conversation: {}]", conversationId, error)
            fail(conversationId, self)
        }
    }

    private suspend fun buildRequest(conversation: ProjectAgentConversationEntity): ProjectAgentStepRequest {
        val history = store.history(conversation.id!!)
        return ProjectAgentStepRequest(
            conversationId = conversation.id,
            projectId = conversation.projectId,
            model = featureModels.resolveAuthoringModel(),
            locale = localeOf(conversation.appUserId),
            toolsEnabled = toolResultsSinceLastInput(history) < properties.toolCallLimit,
            pageCatalog = conversation.pageCatalog?.let(store::toNode) ?: emptyCatalog(),
            messages = stepMessages(history),
        )
    }

    /** 가장 최근 USER 또는 EVENT 뒤에 쌓인 TOOL_RESULT 수. 사용자 입력 한 번이 쓴 tool call 의 수다. */
    private fun toolResultsSinceLastInput(history: List<ProjectAgentMessageEntity>): Int {
        val lastInput = history.indexOfLast { it.role == ProjectAgentRole.USER || it.role == ProjectAgentRole.EVENT }
        return history.drop(lastInput + 1).count { it.role == ProjectAgentRole.TOOL_RESULT }
    }

    /**
     * agent-server 로 보낼 messages. ASSISTANT 의 tool call 에 TOOL_RESULT 가 없는데 그 뒤에 USER 나 EVENT 가
     * 이어졌다면(결과를 저장하다 실패한 대화 등) 그 호출마다 `cancelled` 결과를 끼워 넣는다. provider 는 답 없는
     * tool_use 를 거절한다.
     */
    private fun stepMessages(history: List<ProjectAgentMessageEntity>): List<StepMessage> =
        history.flatMapIndexed { index, message ->
            val converted = listOfNotNull(toStepMessage(message))
            if (message.role != ProjectAgentRole.ASSISTANT) return@flatMapIndexed converted
            val later = history.drop(index + 1)
            if (later.none { it.role == ProjectAgentRole.USER || it.role == ProjectAgentRole.EVENT }) {
                return@flatMapIndexed converted
            }
            val answered = later.filter { it.role == ProjectAgentRole.TOOL_RESULT }
                .mapNotNull { store.parseToolResult(it)?.callId }.toSet()
            val unanswered = store.parseAssistant(message).toolCalls.map { it.callId }.filter { it !in answered }
            converted + unanswered.map { callId ->
                StepMessage(
                    "tool",
                    objectMapper.writeValueAsString(ToolResultPayload(callId = callId, status = "cancelled")),
                    toolCallId = callId,
                )
            }
        }

    private fun toStepMessage(message: ProjectAgentMessageEntity): StepMessage? = when (message.role) {
        ProjectAgentRole.USER -> {
            val page = message.payload?.let(store::toNode)?.get("page")?.takeUnless { it.isNull }
            StepMessage("user", if (page == null) message.content else "${message.content}\n\n[current page]\n$page")
        }
        ProjectAgentRole.EVENT -> {
            val payload = message.payload?.let(store::toNode)
            val data = payload?.get("data")?.takeUnless { it.isNull }
            val head = "[event:${payload?.get("kind")?.asText().orEmpty()}] ${message.content}"
            StepMessage("user", if (data == null) head else "$head\n$data")
        }
        ProjectAgentRole.ASSISTANT -> {
            val calls = store.parseAssistant(message).toolCalls.map { StepToolCall(it.callId, it.tool, it.args) }
            StepMessage("assistant", message.content, toolCalls = calls.ifEmpty { null })
        }
        ProjectAgentRole.TOOL_RESULT -> store.parseToolResult(message)?.let {
            StepMessage("tool", objectMapper.writeValueAsString(it), toolCallId = it.callId)
        }
        else -> null
    }

    /** 응답을 ASSISTANT 로 저장하고 대화를 `AWAITING_TOOL`(호출이 있을 때) 또는 `IDLE` 로 넘긴다. */
    private suspend fun complete(
        conversationId: Long,
        self: Job?,
        toolsEnabled: Boolean,
        response: ProjectAgentStepResponse,
    ) {
        locks.withLock(conversationId) {
            if (!isCurrent(conversationId, self)) return@withLock
            // tools_enabled=false 로 불렀는데 호출이 오면 따르지 않는다. 따르면 한도가 의미가 없다.
            val calls = if (toolsEnabled) uniqueCallIds(response.toolCalls) else emptyList()
            val next = if (calls.isEmpty()) ProjectAgentStatus.IDLE else ProjectAgentStatus.AWAITING_TOOL
            // 상태를 먼저 바꾼다. 이미 RUNNING 이 아니면(다른 경로가 대화를 옮겼다) 이 결과는 버린다.
            val moved = conversations.move(conversationId, listOf(ProjectAgentStatus.RUNNING), next, store.now())
            if (moved == 0) return@withLock
            val locale = localeOf(store.reload(conversationId).appUserId)
            store.append(
                conversationId,
                ProjectAgentRole.ASSISTANT,
                response.content.orEmpty(),
                AssistantPayload(calls.map { ToolCallRecord(it.id, it.name, it.args) }),
            )
            if (!toolsEnabled) {
                store.append(
                    conversationId,
                    ProjectAgentRole.NOTICE,
                    ProjectAgentNotices.text(NoticeKind.STEP_LIMIT, locale),
                    NoticePayload(NoticeKind.STEP_LIMIT),
                )
            }
            store.publishConversation(conversationId)
        }
    }

    /** 한 응답 안에서 id 가 비었거나 겹치는 호출에는 새 id 를 준다. id 가 겹치면 어느 호출의 결과인지 가릴 수 없다. */
    private fun uniqueCallIds(calls: List<StepToolCall>): List<StepToolCall> {
        val seen = mutableSetOf<String>()
        return calls.map { call ->
            val id = if (call.id.isBlank() || call.id in seen) generatedCallId() else call.id
            seen += id
            call.copy(id = id)
        }
    }

    /** NOTICE `error` 를 저장하고 대화를 `ERROR` 로 넘긴다. */
    private suspend fun fail(conversationId: Long, self: Job?) {
        try {
            locks.withLock(conversationId) {
                if (!isCurrent(conversationId, self)) return@withLock
                markError(conversationId, listOf(ProjectAgentStatus.RUNNING, ProjectAgentStatus.AWAITING_TOOL))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("project agent 대화를 ERROR 로 넘기지 못했다 [conversation: {}]", conversationId, error)
        }
    }

    /** 부팅 때 `RUNNING` 으로 남은 대화를 포함해 NOTICE `error` 와 함께 `ERROR` 로 넘긴다. */
    suspend fun markError(
        conversationId: Long,
        from: List<String> = listOf(ProjectAgentStatus.RUNNING),
    ) {
        val locale = localeOf(store.reload(conversationId).appUserId)
        val moved = conversations.move(
            conversationId, from, ProjectAgentStatus.ERROR, store.now(),
        )
        if (moved == 0) return
        store.append(
            conversationId,
            ProjectAgentRole.NOTICE,
            ProjectAgentNotices.text(NoticeKind.ERROR, locale),
            NoticePayload(NoticeKind.ERROR),
        )
        store.publishConversation(conversationId)
    }

    private fun isCurrent(conversationId: Long, self: Job?): Boolean = self != null && runs[conversationId] === self

    private suspend fun localeOf(appUserId: Long): String = users.findById(appUserId)?.locale ?: DEFAULT_LOCALE

    private fun emptyCatalog() = JsonNodeFactory.instance.objectNode().apply { putArray("pages") }

    private fun generatedCallId() = "call_${UUID.randomUUID().toString().replace("-", "")}"

    companion object {
        const val DEFAULT_LOCALE = "ko"
    }
}
