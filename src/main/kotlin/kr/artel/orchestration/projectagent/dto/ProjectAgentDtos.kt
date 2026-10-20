package kr.artel.orchestration.projectagent.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/*
 * browser 와 주고받는 모양(camelCase). `pageCatalog` · `page` · `result` · `data` 는 browser 가 만든 값을
 * 서버가 읽지 않고 보관·전달만 하므로 JsonNode 로 둔다.
 */

data class ProjectAgentConversationResponse(
    val id: Long,
    val projectId: Long,
    val title: String?,
    val status: String,
    val createdAt: Instant,
    val lastMessageAt: Instant,
)

data class ProjectAgentConversationList(val items: List<ProjectAgentConversationResponse>)

data class ProjectAgentMessageResponse(
    val id: Long,
    val conversationId: Long,
    val role: String,
    val content: String,
    val payload: JsonNode?,
    val createdAt: Instant,
)

data class ProjectAgentMessageList(val items: List<ProjectAgentMessageResponse>)

data class CreateProjectAgentConversationRequest(val pageCatalog: JsonNode? = null)

data class RenameProjectAgentConversationRequest(val title: String)

data class SendProjectAgentMessageRequest(
    val text: String,
    val pageCatalog: JsonNode? = null,
    val page: JsonNode? = null,
)

data class ProjectAgentToolResultRequest(
    val callId: String,
    val status: String,
    val result: JsonNode? = null,
    val error: String? = null,
    val page: JsonNode? = null,
)

data class ProjectAgentEventRequest(
    val kind: String,
    val summary: String,
    val data: JsonNode? = null,
)

/*
 * 메시지 payload 의 role 별 모양. DB 에는 JSONB 로 들어가고, 서버가 읽는 것은 ASSISTANT 의 toolCalls 와
 * TOOL_RESULT 의 callId 뿐이다.
 */

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UserPayload(val page: JsonNode? = null)

data class ToolCallRecord(val callId: String, val tool: String, val args: JsonNode)

data class AssistantPayload(val toolCalls: List<ToolCallRecord> = emptyList())

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolResultPayload(
    val callId: String,
    val status: String,
    val result: JsonNode? = null,
    val error: String? = null,
    val page: JsonNode? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EventPayload(val kind: String, val data: JsonNode? = null)

data class NoticePayload(val kind: String)

/** TOOL_RESULT 의 `status` 로 받는 값. */
val TOOL_RESULT_STATUSES = setOf("ok", "error", "rejected", "unavailable", "cancelled")

/** NOTICE 의 `kind`. */
object NoticeKind {
    const val ERROR = "error"
    const val STEP_LIMIT = "step_limit"
    const val STOPPED = "stopped"
}
