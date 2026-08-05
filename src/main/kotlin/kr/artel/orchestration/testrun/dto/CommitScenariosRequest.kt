package kr.artel.orchestration.testrun.dto

import kr.artel.orchestration.testscenario.dto.ScenarioResult

/**
 * 카드 검토 모드에서 사용자가 커밋하는 시나리오 묶음(런 스코프) — ARTEL-206 Step 6.
 *
 * 각 항목은 Agent 결과와 같은 [ScenarioResult] 형태다: `scenario_id`가 있으면 수정, 없으면 추가.
 * 자동저장과 동일한 reconcile 엔진으로 반영된다.
 *
 * @property scenarios 사용자가 카드에서 적용하기로 한(필요하면 편집한) 시나리오들. 빈 배열은 무동작.
 */
data class CommitScenariosRequest(
    val scenarios: List<ScenarioResult> = emptyList()
)
