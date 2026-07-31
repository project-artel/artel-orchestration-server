package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 시나리오의 개별 단계. Agent(`artel-agent-server`)의 ScenarioStep 계약을 미러링한다.
 * canvas에서 하나의 노드로 렌더되며, step 순서대로 다음 노드로 연결된다.
 */
data class ScenarioStep(
    val step: Int,
    val title: String,
    val state: String,
    val action: String,
    val expected: String
)

/**
 * 시나리오 초안(ScenarioDraft). Agent 계약을 미러링한다. test_scenario.payload(JSONB)에 저장되고,
 * SSE 이벤트·조회 응답으로 FE에 전달되어 canvas 렌더에 사용된다.
 *
 * 생성 직후(빈 시나리오)나 Agent 응답 전 상태를 위해 필드에 기본값을 둔다.
 */
data class ScenarioDraft(
    val title: String = "",
    val description: String = "",
    val steps: List<ScenarioStep> = emptyList()
)

/**
 * Agent 턴 결과가 참조하는 시나리오 하나(ARTEL-206 Step 5). Agent는 더 이상 step 기반 단일 시나리오를
 * 만들지 않고, 이미 존재하는 TestCase들을 [caseIds]로 엮은 시나리오를 **여러 개** 돌려준다.
 *
 * @property caseIds 이 시나리오가 담는 TestCase id들. 리스트 순서가 곧 시나리오 내 의미적 순서(position)다.
 *   Agent 계약의 `case_ids`(정수 배열)에 대응한다.
 */
data class ScenarioResult(
    val title: String = "",
    val description: String = "",
    @JsonProperty("case_ids") val caseIds: List<Long> = emptyList()
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
