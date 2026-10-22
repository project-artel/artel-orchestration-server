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

/**
 * NOTICE 의 payload. [cause], [model], [keySource] 는 `error` 에서 원인을 알 때만 채우고, 비면 JSON 에 싣지 않는다.
 * 그래서 이 칸들이 생기기 전의 NOTICE 와 모양이 같다.
 *
 * @property cause [ProjectAgentFailureCause] 의 값
 * @property model 실패한 step 이 쓴 model. agent-server 기본값으로 돌았으면 null 이다
 * @property keySource `platform` 또는 `user`
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NoticePayload(
    val kind: String,
    val cause: String? = null,
    val model: String? = null,
    val keySource: String? = null,
)

/** TOOL_RESULT 의 `status` 로 받는 값. */
val TOOL_RESULT_STATUSES = setOf("ok", "error", "rejected", "unavailable", "cancelled")

/** NOTICE 의 `kind`. */
object NoticeKind {
    const val ERROR = "error"
    const val STEP_LIMIT = "step_limit"
    const val STOPPED = "stopped"
}

/**
 * step 이 실패한 원인. agent-server 가 실패 본문의 `detail.code` 로 보내고, 이 서버는 NOTICE `error` 에 싣는다.
 * 화면은 이 값으로 key 설정 링크와 model 바꾸기를 보여 준다.
 */
object ProjectAgentFailureCause {
    /** 그 model 의 경로에 쓸 key 가 없다. 이 서버가 step 전에 사용자 key 를 찾지 못했을 때도 이 값이다. */
    const val LLM_KEY_MISSING = "llm_key_missing"

    /** provider 가 보낸 key 를 401 · 403 으로 거절했다. */
    const val LLM_AUTH_FAILED = "llm_auth_failed"

    /** provider 가 429 를 돌려줬다. */
    const val LLM_RATE_LIMITED = "llm_rate_limited"

    /** model 이 카탈로그에 없거나 꺼져 있거나, provider 가 그 model 을 모른다. */
    const val LLM_MODEL_UNAVAILABLE = "llm_model_unavailable"

    /** 그 밖의 provider 실패. 시간 초과와 읽지 못한 실패 본문도 이 값이다. */
    const val LLM_PROVIDER_ERROR = "llm_provider_error"

    val ALL = setOf(LLM_KEY_MISSING, LLM_AUTH_FAILED, LLM_RATE_LIMITED, LLM_MODEL_UNAVAILABLE, LLM_PROVIDER_ERROR)
}

/**
 * `GET/PUT /api/auth/me/project-agent-model` 응답.
 *
 * @property model 사용자가 고른 model. null 이면 고르지 않았다
 * @property keySource [model] 을 부를 key. [model] 이 null 이면 null 이다
 * @property effectiveModel 다음 step 이 쓸 model. 고른 model 이 있으면 쓸 수 없어도 그 값이다 — step 은 다른 model 로
 *   넘어가지 않고 [problem] 의 원인으로 실패한다. null 이면 agent-server 기본값이다
 * @property effectiveKeySource 다음 step 이 쓸 key
 * @property problem 고른 model 을 지금 쓸 수 없는 원인([ProjectAgentFailureCause]). 쓸 수 있거나 고르지 않았으면 null 이다
 */
data class UserProjectAgentModelResponse(
    val model: String?,
    val keySource: String?,
    val effectiveModel: String?,
    val effectiveKeySource: String?,
    val problem: String?,
)

/** `PUT /api/auth/me/project-agent-model` 본문. [model] 이 null 이면 선택을 지운다. [keySource] 가 null 이면 `platform` 이다. */
data class UpdateUserProjectAgentModelRequest(
    val model: String? = null,
    val keySource: String? = null,
)

/**
 * `GET/PUT /api/projects/{projectId}/project-agent-model` 응답.
 *
 * @property model 프로젝트에 저장된 model. null 이면 정하지 않았다
 * @property effectiveModel 자기 선택이 없는 멤버의 step 이 쓸 model. null 이면 agent-server 기본값이다
 * @property problem 저장된 model 을 지금 쓸 수 없는 원인([ProjectAgentFailureCause]). 그때 step 은 관리자 설정으로 넘어간다
 */
data class ProjectAgentModelOfProjectResponse(
    val model: String?,
    val effectiveModel: String?,
    val problem: String?,
)

/** `PUT /api/projects/{projectId}/project-agent-model` 본문. null 이면 프로젝트 설정을 지운다. */
data class UpdateProjectAgentModelOfProjectRequest(val model: String? = null)
