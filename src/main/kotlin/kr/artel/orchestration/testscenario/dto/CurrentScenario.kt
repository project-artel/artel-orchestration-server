package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 런의 현재 시나리오 한 건(Agent 컨텍스트용) — ARTEL-206 Step 6.
 *
 * 세션 오픈·턴마다 런의 현재 구성을 Agent에 함께 보내, Agent가 사용자 자연어에서 **어느 기존 시나리오를
 * 수정할지**를 [scenarioId]로 지목할 수 있게 한다(없는 걸 새로 만들 땐 결과의 scenario_id를 null로 낸다).
 *
 * @property scenarioId 이 시나리오의 id. Agent가 수정 결과의 `scenario_id`로 그대로 되돌린다.
 * @property caseIds 시나리오가 담은 TestCase id들(순서=position).
 */
data class CurrentScenario(
    @JsonProperty("scenario_id") val scenarioId: Long,
    val title: String,
    val description: String,
    @JsonProperty("case_ids") val caseIds: List<Long>
)
