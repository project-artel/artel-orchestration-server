package kr.artel.orchestration.projectagent.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.projectagent.dto.AssistantPayload
import kr.artel.orchestration.projectagent.dto.ProjectAgentConversationResponse
import kr.artel.orchestration.projectagent.dto.ProjectAgentMessageResponse
import kr.artel.orchestration.projectagent.dto.ToolResultPayload
import kr.artel.orchestration.projectagent.entity.ProjectAgentConversationEntity
import kr.artel.orchestration.projectagent.entity.ProjectAgentMessageEntity
import kr.artel.orchestration.projectagent.entity.ProjectAgentRole
import kr.artel.orchestration.projectagent.repository.ProjectAgentConversationRepository
import kr.artel.orchestration.projectagent.repository.ProjectAgentMessageRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** 이미 step 이 돌고 있거나 tool 결과를 기다리는 대화에 입력이 왔다. */
class ConversationBusyException :
    ConflictException("대화가 진행 중입니다. 끝난 뒤에 다시 보내 주세요.", code = "conversation_busy")

/** 답할 차례가 아닌 tool call 에 결과가 왔다(이미 답했거나, 마지막 ASSISTANT 메시지의 호출이 아니다). */
class ToolCallNotPendingException :
    ConflictException("답을 기다리는 tool call 이 아닙니다.", code = "not_pending")

/**
 * 대화와 메시지를 읽고 쓰는 자리. 메시지를 저장하면 SSE 구독자에게도 바로 나가므로, 저장과 발행을 한 곳에 둔다.
 * JSONB 와 [JsonNode] 사이의 변환도 여기서만 한다.
 */
@Component
class ProjectAgentConversationStore(
    private val conversations: ProjectAgentConversationRepository,
    private val messages: ProjectAgentMessageRepository,
    private val hub: ProjectAgentStreamHub,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun now(): Instant = Instant.now(clock)

    /** 프로젝트·사용자가 모두 맞는 대화. 아니면 404. */
    suspend fun owned(projectId: Long, conversationId: Long, appUserId: Long): ProjectAgentConversationEntity =
        conversations.findByIdAndProjectIdAndAppUserId(conversationId, projectId, appUserId)
            ?: throw NotFoundException("대화를 찾을 수 없습니다.")

    suspend fun reload(conversationId: Long): ProjectAgentConversationEntity =
        conversations.findById(conversationId) ?: throw NotFoundException("대화를 찾을 수 없습니다.")

    suspend fun history(conversationId: Long): List<ProjectAgentMessageEntity> =
        messages.findByConversationIdOrderByIdAsc(conversationId).toList()

    /** 메시지를 저장하고 `message` 이벤트로 내보낸다. [payload] 는 JSON 으로 직렬화되는 객체이거나 null. */
    suspend fun append(
        conversationId: Long,
        role: String,
        content: String = "",
        payload: Any? = null,
    ): ProjectAgentMessageEntity {
        val saved = messages.save(
            ProjectAgentMessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                payload = payload?.let { Json.of(objectMapper.writeValueAsString(it)) },
                createdAt = now(),
            )
        )
        hub.publish(toResponse(saved))
        return saved
    }

    /** 대화의 현재 모습을 `conversation` 이벤트로 내보낸다. */
    suspend fun publishConversation(conversationId: Long) {
        hub.publish(toResponse(reload(conversationId)))
    }

    fun toJson(node: JsonNode): Json = Json.of(objectMapper.writeValueAsString(node))

    fun toNode(json: Json): JsonNode = objectMapper.readTree(json.asString())

    fun parseAssistant(message: ProjectAgentMessageEntity): AssistantPayload =
        message.payload?.let { objectMapper.readValue(it.asString(), AssistantPayload::class.java) } ?: AssistantPayload()

    fun parseToolResult(message: ProjectAgentMessageEntity): ToolResultPayload? =
        message.payload?.let { objectMapper.readValue(it.asString(), ToolResultPayload::class.java) }

    /** 마지막 ASSISTANT 메시지의 tool call 중 아직 TOOL_RESULT 가 없는 것의 id. 순서는 호출 순서다. */
    fun pendingCallIds(history: List<ProjectAgentMessageEntity>): List<String> {
        val assistantIndex = history.indexOfLast { it.role == ProjectAgentRole.ASSISTANT }
        if (assistantIndex < 0) return emptyList()
        val answered = history.drop(assistantIndex + 1)
            .filter { it.role == ProjectAgentRole.TOOL_RESULT }
            .mapNotNull { parseToolResult(it)?.callId }
            .toSet()
        return parseAssistant(history[assistantIndex]).toolCalls.map { it.callId }.filter { it !in answered }
    }

    fun toResponse(entity: ProjectAgentConversationEntity) = ProjectAgentConversationResponse(
        id = entity.id!!,
        projectId = entity.projectId,
        title = entity.title,
        status = entity.status,
        createdAt = entity.createdAt,
        lastMessageAt = entity.lastMessageAt,
    )

    fun toResponse(entity: ProjectAgentMessageEntity) = ProjectAgentMessageResponse(
        id = entity.id!!,
        conversationId = entity.conversationId,
        role = entity.role,
        content = entity.content,
        payload = entity.payload?.let(::toNode),
        createdAt = entity.createdAt,
    )
}
