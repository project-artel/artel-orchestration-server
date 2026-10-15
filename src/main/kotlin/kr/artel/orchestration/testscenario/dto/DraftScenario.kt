package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 자동저장을 끈 턴이 낸 시나리오 하나(ARTEL-1074). **아직 어디에도 저장되지 않았다.**
 *
 * 검수(`save=false`)는 지난 최종본이다. 사용자가 승인하면 자동저장과 같은 엔진으로 반영되고,
 * 그전까지는 화면이 이것을 왼쪽 목록에 겹쳐 "승인하면 이렇게 된다"를 보인다.
 *
 * @property draftId 이 draft 를 가리키는 이름. 기존 시나리오를 고친 것은 `edit-<scenarioId>`,
 *   새로 생길 것은 `new-<n>` 이다. 같은 대상을 다시 고치면 같은 이름의 draft 가 바뀐다.
 * @property scenarioId 고칠 기존 시나리오. null 이면 승인할 때 새로 만든다.
 * @property absorbedScenarioIds 승인하면 이 시나리오로 합쳐져 런에서 빠질 것들. 정말 빠지는지는
 *   승인할 때 [kr.artel.orchestration.testscenario.service.ScenarioAbsorbService] 가 다시 센다.
 */
data class DraftScenario(
    @JsonProperty("draft_id") val draftId: String,
    @JsonProperty("scenario_id") val scenarioId: Long?,
    val title: String,
    val description: String,
    val steps: List<ChatScenarioStep>,
    @JsonProperty("absorbed_scenario_ids") val absorbedScenarioIds: List<Long> = emptyList(),
) {
    fun toResult() = ScenarioResult(scenarioId, title, description, steps)
}

/** 사용자가 draft 본문을 고친 것(ARTEL-1074). 고칠 대상과 흡수할 것은 그대로 둔다. */
data class DraftScenarioEdit(
    val title: String = "",
    val description: String = "",
    val steps: List<ChatScenarioStep> = emptyList(),
)

/** 승인 · 버리기가 고른 draft 들(ARTEL-1074). */
data class DraftSelection(
    @JsonProperty("draft_ids") val draftIds: List<String> = emptyList(),
)

/**
 * draft 승인 결과(ARTEL-1074).
 *
 * @property applied 실제로 반영된 시나리오 수(추가 + 수정).
 * @property absorbed 합치면서 런에서 뺀 시나리오 제목.
 * @property kept 합치라 했지만 빼지 않은 것 — 제목과 이유가 한 문장이다.
 * @property refused 검수가 막아 반영하지 않고 draft 에 되돌려 둔 것 — 제목과 이유.
 * @property drafts 승인 뒤에 남은 draft.
 */
data class DraftApplyResult(
    val applied: Int,
    val absorbed: List<String>,
    val kept: List<String>,
    val refused: List<String>,
    val drafts: List<DraftScenario>,
)
