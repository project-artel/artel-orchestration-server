package kr.artel.orchestration.testscenario.dto

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
 * SSE로 FE에 전달하는 이벤트 봉투. Agent 응답(result/error)을 타입화한다.
 *
 * - `type == "result"` → `message` + `scenario`
 * - `type == "error"`  → `code` + `detail`
 */
data class ScenarioStreamEvent(
    val type: String,
    val message: String? = null,
    val scenario: ScenarioDraft? = null,
    val code: String? = null,
    val detail: String? = null
)
