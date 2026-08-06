package kr.artel.orchestration.qa.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

data class CreateQaTryRequest(
    val testScenarioId: String,
    val gameInstanceId: String,
    val model: String? = null,
    val language: String? = null,
    val promptVersion: String? = null,
    val reasoning: QaReasoningRequest? = null,
    /**
     * The agent's structure — loop bounds, per-run allowances, vision, middleware.
     *
     * Opaque on purpose. Restating the Agent's knob schema here would be a second
     * copy to keep in sync, and the copy would be the one that goes stale: the
     * Agent already rejects an unknown or out-of-range knob with a 422, which
     * surfaces as a failed try rather than a run quietly using something else.
     */
    val arch: JsonNode? = null
)

/** 런(TR) 단위 QA 시작 요청(ARTEL-259). [testRunId]의 시나리오들을 순차 실행한다. 설정은 QaTry와 동일. */
data class CreateQaRunRequest(
    val testRunId: String,
    val gameInstanceId: String,
    val model: String? = null,
    val language: String? = null,
    val promptVersion: String? = null,
    val reasoning: QaReasoningRequest? = null,
    val arch: JsonNode? = null
)

data class QaReasoningRequest(
    val effort: String? = null,
    val maxTokens: Int? = null
)

data class QaReasoningCapability(
    val kind: String,
    val efforts: List<String>?,
    @JsonAlias("min_tokens") val minTokens: Int?,
    @JsonAlias("max_tokens") val maxTokens: Int?,
    val step: Int?
)

data class QaModelResponse(
    val id: String,
    val label: String,
    val provider: String,
    @JsonAlias("supports_strict_json") val supportsStrictJson: Boolean,
    @JsonAlias("supports_vision") val supportsVision: Boolean,
    @JsonAlias("input_modalities") val inputModalities: List<String>,
    val multimodal: Boolean,
    val reasoning: QaReasoningCapability?
)

data class QaTryResponse(
    val id: String,
    val testScenarioId: String,
    val gameInstanceId: String,
    val startedBy: String,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    /**
     * What the run was actually executed with, as the Agent resolved it — null on
     * tries that predate this being recorded, and on any whose Agent did not
     * report it. The four promoted fields are the comparison axes; [runConfig] is
     * the whole snapshot and the one to trust when they disagree.
     */
    val model: String? = null,
    val promptVersion: String? = null,
    val agentArch: String? = null,
    val agentFingerprint: String? = null,
    val runConfig: JsonNode? = null
)

/**
 * 런 단위 QA 실행 응답(ARTEL-259). 한 qa_run + 그 아래 시나리오별 qa_try들. [tries]로 FE가 각
 * 시나리오의 실행/결과에 접근한다(qa_try 기준 조회·이슈·통계 그대로).
 */
data class QaRunResponse(
    val id: String,
    val testRunId: String,
    val gameInstanceId: String,
    val startedBy: String,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val tries: List<QaTryResponse> = emptyList()
)

data class QaLogResponse(
    val id: String,
    val qaTryId: String,
    val messageId: String?,
    val correlationId: String?,
    val direction: String,
    val type: String,
    val message: String?,
    val payload: JsonNode,
    val createdAt: Instant
)

data class QaLogPageResponse(
    val items: List<QaLogResponse>,
    val nextBeforeId: String?,
    val hasMore: Boolean
)

data class QaStatusPayload(
    val status: String,
    val completedAt: Instant?
)

data class SendQaMessageRequest(val message: String)
