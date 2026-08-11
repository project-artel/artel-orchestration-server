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
    val arch: JsonNode? = null,
    /**
     * 이 런이 읽고 쓸 지식 스코프(ARTEL-256). 생략하면 운영 런이라 운영 지식창고를 그대로 쓴다.
     *
     * 실험 엔티티(qa_experiment / qa_experiment_arm)가 아직 없어 지금은 이 필드가 스코프를 정하는
     * 유일한 입구다. 값의 출처를 서버가 검증하지 않는 것은 그래서다 — 지금 형식을 못박으면 실험
     * 엔티티가 그 형식을 따라가야 하는 순서가 된다. 그때가 오면 이 필드는 그 엔티티가 채운다.
     */
    val knowledgeScopeId: String? = null,
    /**
     * 이 런에 지식창고를 얼마나 열어 줄지: `learning`(기본) / `frozen` / `off`. 잘못된 값은 400이다 —
     * 조용히 기본값으로 떨어지면 대조군으로 돌린 arm이 사실은 학습을 하고, 그 결과는 그럴듯해서
     * 실험이 끝날 때까지 아무도 못 알아챈다.
     */
    val knowledgeMode: String? = null
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
    val runConfig: JsonNode? = null,
    /**
     * 이 런이 쓴 지식 스코프(ARTEL-256). null이면 운영 런이다. `knowledge_mode`는 별도 필드가
     * 아니라 [runConfig] 안에 있다 — 비교 축은 전부 그 스냅샷에 모여야 집계가 한 곳만 읽는다.
     */
    val knowledgeScopeId: String? = null
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
