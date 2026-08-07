package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 시나리오 스텝 하나 = **행위 하나**(재설계 2026-08-07). 시나리오는 스텝의 순서 있는 집합이고,
 * 스텝이 1급 단위다.
 *
 * @property action 이 스텝에서 하는 행위(자연어). @property caseId 이 스텝이 속한 TC(검증 대상).
 *   **nullable** — 대부분의 스텝은 그냥 조작이라 검증 대상이 없다. **연속된 동일 caseId = 그 TC의 검증
 *   구간**이고, 그 구간의 마지막 스텝을 수행한 뒤 그 TC의 `expected`로 판정한다. @property hint 선택적
 *   근거(키/백도어). @property input 선택: `keyboard`|`click` 등.
 */
data class ScenarioStep(
    val action: String = "",
    @JsonProperty("case_id") val caseId: Long? = null,
    val hint: String? = null,
    val input: String? = null,
)

/**
 * 시나리오 초안(ScenarioDraft). test_scenario.payload(JSONB)에 저장되고, SSE 이벤트·조회 응답으로 FE에
 * 전달된다. **시나리오 = steps의 순서 집합**(재설계). 생성 직후(빈 시나리오)를 위해 기본값을 둔다.
 */
data class ScenarioDraft(
    val title: String = "",
    val description: String = "",
    val steps: List<ScenarioStep> = emptyList()
)

/**
 * Agent 턴 결과가 참조하는 시나리오 하나(ARTEL-206 Step 5·6, 재설계 2026-08-07). Agent는 시나리오를
 * **여러 개** 돌려주며, 각 시나리오는 [steps]의 순서 집합이다. 한 응답에 **추가와 수정이 섞여** 올 수 있다.
 *
 * @property scenarioId `null`이면 **새 시나리오 추가**(INSERT + 런 append), 값이 있으면 그 **기존 시나리오
 *   수정**(payload 통째 교체). Agent 계약의 `scenario_id`에 대응한다.
 * @property steps 이 시나리오의 스텝들(순서=실행 순서). 각 스텝은 행위 + 선택적 caseId. Agent 계약의
 *   `steps`에 대응한다.
 */
data class ScenarioResult(
    @JsonProperty("scenario_id") val scenarioId: Long? = null,
    val title: String = "",
    val description: String = "",
    val steps: List<ScenarioStep> = emptyList()
)

/**
 * SSE로 FE에 전달하는 이벤트 봉투. Agent 응답(result/error)을 타입화한다.
 *
 * - `type == "result"` → `message` + `scenarios`(0개 이상. 빈 배열은 "질문/거절/무매치" 같은 정상 턴이다)
 * - `type == "error"`  → `code` + `detail`
 */
data class ScenarioStreamEvent(
    val type: String,
    val message: String? = null,
    val scenarios: List<ScenarioResult>? = null,
    val code: String? = null,
    val detail: String? = null
)
