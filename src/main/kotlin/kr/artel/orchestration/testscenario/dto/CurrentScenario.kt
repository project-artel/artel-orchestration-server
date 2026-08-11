package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 런의 현재 시나리오 한 건(Agent 컨텍스트용) — ARTEL-206 Step 6, 재설계 2026-08-07.
 *
 * 세션 오픈·턴마다 런의 현재 구성을 Agent에 함께 보내, Agent가 사용자 자연어에서 **어느 기존 시나리오를
 * 수정할지**를 [scenarioId]로 지목하고, 그 시나리오의 **기존 steps까지 보고** 편집할 수 있게 한다.
 *
 * @property scenarioId 이 시나리오의 id. Agent가 수정 결과의 `scenario_id`로 그대로 되돌린다.
 * @property steps 시나리오의 스텝들(순서=실행 순서, 각 스텝은 행위 + 선택적 caseId). Agent 결과의
 *   `steps`와 같은 형태라, Agent가 그대로 편집해 되돌릴 수 있다.
 */
data class CurrentScenario(
    @JsonProperty("scenario_id") val scenarioId: Long,
    val title: String,
    val description: String,
    val steps: List<ScenarioStep>
)
