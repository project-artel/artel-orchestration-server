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
 *
 *   타입이 저장 모델([ScenarioStep])이 아니라 [ChatScenarioStep]인 것은 의도다 — 저장 모델에는
 *   기대 판정 라벨(`expected_passed`)이 있고, 그것이 여기 실리면 에이전트가 답을 알고 시나리오를
 *   다루게 된다(ARTEL-301). 작성 에이전트는 실행하지 않지만, 라벨을 본 에이전트가 그것을 근거로
 *   스텝을 고치면 정답지와 시나리오가 서로를 오염시킨다.
 */
data class CurrentScenario(
    @JsonProperty("scenario_id") val scenarioId: Long,
    val title: String,
    val description: String,
    val steps: List<ChatScenarioStep>
)
