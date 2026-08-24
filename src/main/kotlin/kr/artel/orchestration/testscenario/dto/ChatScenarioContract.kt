package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 시나리오 작성 챗봇과 주고받는 스텝(ARTEL-301). 저장 모델 [ScenarioStep]과 **일부러 다른 타입**이다.
 *
 * 두 방향 모두 이 타입을 쓴다: 에이전트에게 보내는 현재 구성([CurrentScenario])과 에이전트가
 * 돌려주는 편집 결과([ScenarioResult]).
 *
 * **왜 저장 모델을 그대로 쓰지 않나.** 저장 모델에는 `expected_passed`(각 스텝이 통과해야 하는지에
 * 대한 사람의 판단)가 있고, 그것이 에이전트에게 나가면 정답지를 들고 시험을 치는 셈이 된다. 한
 * 타입을 공유하면 새 저작 필드가 늘 때마다 누구도 의도하지 않은 채로 그 필드가 에이전트에 실린다 —
 * 그리고 그 사고는 점수가 좋아 보일 뿐이라 조용하다. 타입을 가르면 새 필드를 여기에도 **명시적으로**
 * 더하지 않는 한 나갈 수 없고, 그 추가는 리뷰에 보인다.
 *
 * QA 실행 계약이 [AgentScenario]/[AgentStep]으로 따로 선언된 것과 같은 판단이며, 여기는 그 판단을
 * 두 번째 에이전트 경로에 적용한 것이다.
 *
 * 와이어 필드명은 [ScenarioStep]과 같아야 한다 — 에이전트 계약이 그대로 유지된다.
 */
data class ChatScenarioStep(
    val action: String = "",
    @JsonProperty("case_id") val caseId: Long? = null,
    val hint: String? = null,
    val input: String? = null,
    /** 이 스텝을 어디서 가져왔는가(ARTEL-467). null이면 검사를 건너뛴다 — 구버전 Agent 호환. */
    @JsonProperty("step_source") val stepSource: ScenarioStepSource? = null,
    /** 할 일인가 알림인가(ARTEL-468). `GAP`은 오케가 붙인다 — Agent는 보내지 않는다. */
    @JsonProperty("step_kind") val stepKind: ScenarioStepKind? = null,
    @JsonProperty("step_source_capability_id") val stepSourceCapabilityId: Long? = null,
    @JsonProperty("step_unknown_reason") val stepUnknownReason: String? = null,
)

/** 저장 스텝을 작성 챗봇 계약으로 투영한다. 라벨은 여기서 떨어진다 — 그것이 이 함수의 요점이다. */
fun ScenarioStep.toChatStep() = ChatScenarioStep(
    action = action,
    caseId = caseId,
    hint = hint,
    input = input,
    stepSource = stepSource,
    stepKind = stepKind,
    stepSourceCapabilityId = stepSourceCapabilityId,
    stepUnknownReason = stepUnknownReason,
)

/**
 * 챗봇이 돌려준 스텝을 저장 모델로 되돌린다. `expectedPassed`는 **항상 null로 시작한다** —
 * 에이전트는 라벨을 본 적이 없으므로 라벨에 대해 할 말이 없다. 기존 라벨을 살리는 일은
 * [kr.artel.orchestration.testscenario.service.ScenarioReconcileService]가 저장 직전에 한다.
 */
fun ChatScenarioStep.toStoredStep() = ScenarioStep(
    action = action,
    caseId = caseId,
    hint = hint,
    input = input,
    stepSource = stepSource,
    stepKind = stepKind,
    stepSourceCapabilityId = stepSourceCapabilityId,
    stepUnknownReason = stepUnknownReason,
)
