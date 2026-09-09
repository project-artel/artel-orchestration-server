package kr.artel.orchestration.qa.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
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
    val knowledgeMode: String? = null,
    /**
     * 이 런에 content map 을 얼마나 열어 줄지: `on`(기본) / `frozen` / `off`. 잘못된 값은 400이다 —
     * [knowledgeMode] 와 같은 이유로, 조용히 기본값으로 떨어지면 지도 없이 돌린다고 믿은 arm 이
     * 사실은 지도를 읽고, 그 결과는 그럴듯해서 실험이 끝날 때까지 아무도 못 알아챈다.
     *
     * [knowledgeMode] 와 **따로** 여는 것이 요점이다. 한 스위치로 묶으면 지식이 도왔는지 지도가
     * 도왔는지를 가르는 2×2 가 성립하지 않는다.
     */
    val contentMapMode: String? = null
)

/** 런(TR) 단위 QA 시작 요청(ARTEL-259). [testRunId]의 시나리오들을 순차 실행한다. 설정은 QaTry와 동일. */
data class CreateQaRunRequest(
    val testRunId: String,
    val gameInstanceId: String,
    val model: String? = null,
    val language: String? = null,
    val promptVersion: String? = null,
    val reasoning: QaReasoningRequest? = null,
    val arch: JsonNode? = null,
    /**
     * 그 게임 인스턴스에 진행 중인 QA가 있어도 그것을 끝내고 시작한다(런 이어받기).
     *
     * 기본값은 false — 남의 런을 끊는 것은 되돌릴 수 없으므로 요청이 명시적으로 말해야 한다.
     * 첫 요청은 그냥 보내고, 409 `qa_run_active`가 오면 사용자에게 물은 뒤 이 값을 켜서 재요청하는
     * 것이 의도된 흐름이다.
     */
    val force: Boolean = false,
    /**
     * 이 run 에 지식창고를 얼마나 열어 줄지: `learning` / `frozen` / `off`. 잘못된 값은 400 이고,
     * 검증은 [CreateQaTryRequest.knowledgeMode] 와 **같은 함수**가 한다.
     *
     * 생략하면 `run_config` 에 이 키가 실리지 않고, 읽는 쪽이 `learning` 으로 읽는다 — 이 필드가
     * 생기기 전 호출자와 동작이 같다.
     */
    val knowledgeMode: String? = null,
    /**
     * 이 run 에 content map 을 얼마나 열어 줄지: `on` / `frozen` / `off`. [knowledgeMode] 와 같이
     * 생략하면 키가 실리지 않는다.
     *
     * **두 축을 함께 여는 것이 요점이다.** 벤치마크는 run(TR) 단위로 조직돼 있어, 한 축만 열리면
     * 네 arm 중 둘을 만들 방법이 없고 2×2 가 2×1 이 된다.
     */
    val contentMapMode: String? = null,
    /**
     * 이 run 이 속한 실험 묶음의 이름. 생략하면 `qa_run.label` 이 null 이고 지금과 동작이 같다.
     *
     * **arm 을 적는 자리가 아니다.** [knowledgeMode] 와 [contentMapMode] 가 이미 arm 을 말하므로,
     * 여기 다시 적으면 같은 사실이 두 군데 남는다. 자세한 논거는 `QaRunEntity.label` 의 KDoc 에
     * 있다.
     *
     * 앞뒤 공백은 지우고, 그러고도 빈 문자열이면 null 로 읽는다 — 빈 이름은 묶음이 아니다.
     * 255자를 넘으면 400 이다.
     */
    val label: String? = null
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
    val knowledgeScopeId: String? = null,
    /**
     * 이 try 가 속한 부모 `qa_run`(ARTEL-259, ARTEL-722). null이면 `qa_run` 이 생기기 전의
     * 단독 실행(하위호환) try다 — `qa_try.qa_run_id` 가 nullable 인 것과 같은 이유다. 화면이
     * try 화면에서 run 콘솔로 올라가는 링크를 만드는 재료다.
     */
    val qaRunId: String? = null
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
    /** 이 run 이 속한 실험 묶음. 요청이 안 줬으면 null 이다. */
    val label: String? = null,
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

/**
 * SDK socket 으로 나가는 QA try 진행 상태 알림(ARTEL-836).
 *
 * SDK([ARTEL-835](https://artel-asm.atlassian.net/browse/ARTEL-835))가 이 모양 그대로 읽는다 —
 * 필드를 rename 하거나 더하지 않는다. [qaRunId] 와 [testRunName] 은 이 try 가 run(TR) 단위로
 * 시작되지 않았으면(단일 시나리오 경로) null 이다. [outcome] 은 [state] 가 `FINISHED` 일 때만 싣는다.
 */
data class RunStatusMessage(
    val type: String = "RUN_STATUS",
    val state: String,
    val projectName: String,
    val testRunName: String?,
    val qaRunId: Long?,
    val qaTryId: Long,
    val label: String?,
    val outcome: String? = null,
    val at: Instant
)

data class SendQaMessageRequest(val message: String)

/**
 * QA 히스토리에서 런 하나를 펼쳤을 때 그 자리에 서는 것(ARTEL-819).
 *
 * **[QaTryResponse]에 안 싣는다.** 저 응답은 목록이 수십 행을 그릴 때마다 나가는데, 여기 실린
 * 값들은 `llm_usage`·`qa_log`·`issue`·`capability_observation` 네 표를 접어야 나온다. 안 펼친
 * 행의 값은 아무도 안 보므로 펼칠 때 그 행만 따로 부른다.
 *
 * [stepsPassed]와 [stepsTotal]은 완주하지 않은 런에서 **함께 null이다**. 0이 아니라 없는 것이고,
 * 임의의 분모를 붙이면 "10 / 17 실패"처럼 읽힌다. 취소된 런은 판정 자체가 없다.
 */
data class QaTryDetailResponse(
    val qaTryId: String,
    val status: String,
    val scenarioTitle: String,
    val model: String?,
    val promptVersion: String?,
    val reasoningEffort: String?,
    val startedAt: Instant,
    val completedAt: Instant?,
    val stepsPassed: Int?,
    val stepsTotal: Int?,
    /** 이 런이 게임에서 찾아 보고한 결함 수. `qa_log`의 `ERROR`가 아니다. */
    val issues: Long,
    /** 이 런이 `capability`에 남긴 판정 수. */
    val feedback: Long,
    val usage: QaTryDetailUsage,
    /** 많이 부른 것이 앞이다. 한 번도 안 불렀으면 빈 목록. */
    val toolCalls: List<QaTryToolCall>
)

/**
 * 이 런이 쓴 것과 든 돈.
 *
 * [cachedInputTokens]는 [inputTokens]에 **포함된** 값이고 [cacheWriteTokens]는 아니다. 셋을
 * 나란히 더하면 같은 토큰을 두 번 센다.
 *
 * [costUsd]가 null이면 "공짜"가 아니라 "아무도 단가를 말한 적 없다"이다. [pricedCalls]가
 * [calls]보다 작으면 그 금액은 하한이다. [costEstimated]는 우리가 토큰으로 계산한 값이 섞였다는
 * 뜻이다 — provider가 호출별 청구액을 안 줘서 그렇다.
 */
data class QaTryDetailUsage(
    val calls: Long,
    val pricedCalls: Long,
    val costUsd: BigDecimal?,
    val costEstimated: Boolean,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long
)

data class QaTryToolCall(
    val tool: String,
    val calls: Long
)
