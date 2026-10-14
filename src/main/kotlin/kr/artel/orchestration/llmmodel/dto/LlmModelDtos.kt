package kr.artel.orchestration.llmmodel.dto

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** `GET /internal/llm-models` 의 한 항목. 꺼진 행도 싣는다. */
data class InternalLlmModelResponse(
    val id: String,
    val label: String,
    val provider: String,
    val supportsStrictJson: Boolean,
    val maxInputTokens: Int,
    val inputModalities: List<String>,
    /** `reasoning_kind` 가 null 이면 null 이다. */
    val reasoning: LlmModelReasoning?,
    /** 열 값이 null 이면 null 이다. 안의 `longContext*` 키는 null 일 수 있다. */
    val pricing: JsonNode?,
    val enabled: Boolean,
    val sortOrder: Int
)

data class LlmModelReasoning(
    val kind: String,
    val efforts: List<String>?,
    val minTokens: Int?,
    val maxTokens: Int?,
    val step: Int?,
    /**
     * 요청이 추론을 고르지 않았을 때 agent-server 가 켤 token 예산. provider 기본값이 없는 model(Bedrock 의
     * Anthropic `budget_tokens` 경로)에만 있고, 나머지는 null 이다. `/api/qa-models` 에는 싣지 않는다.
     */
    val defaultTokens: Int?
)

/** `GET /api/admin/llm-models` 와 `PATCH /api/admin/llm-models` 의 한 항목. */
data class AdminLlmModelResponse(
    val id: String,
    val label: String,
    val provider: String,
    /** `bedrock` 또는 `openrouter`. */
    val route: String,
    val enabled: Boolean,
    val supportsVision: Boolean,
    val maxInputTokens: Int,
    val updatedAt: Instant
)

/** `PATCH /api/admin/llm-models` 요청 본문. id 에 `/` 와 `:` 가 들어 있어 경로가 아니라 본문에 싣는다. */
data class UpdateLlmModelRequest(
    val id: String,
    val enabled: Boolean
)
