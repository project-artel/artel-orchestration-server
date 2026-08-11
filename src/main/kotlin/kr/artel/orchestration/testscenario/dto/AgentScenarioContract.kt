package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * QA 실행 시 Agent로 넘기는 시나리오 실행 계약(재설계 2026-08-07). 예전엔 [ScenarioCompositionService]가
 * `JsonNode`를 손으로 조립해 넘겼으나, 직렬화 계약을 **타입 있는 DTO**로 드러내 필드명·구조 변경을 컴파일이
 * 잡도록 한다(팀 리뷰 피드백). 와이어 필드명은 `@JsonProperty`로 선언한다(예: caseId→`case_id`).
 *
 * 계약 형태: `{ title, description, steps: [ { action, case_id, hint, input, case: AgentCase|null } ] }`.
 * **연속된 동일 `case_id` = 한 TC의 검증 구간**이며, 구간 끝에서 `case.expected`로 판정한다.
 */
data class AgentScenario(
    val title: String,
    val description: String,
    val steps: List<AgentStep>,
)

/**
 * 스텝 하나 = 행위 하나. [caseId]가 있으면 그 TC의 스펙을 [case]에 리졸브해 동봉한다(없으면 null).
 * null 필드는 그대로 직렬화된다(키 존재 = Agent 계약) — 이 계약 DTO에는 NON_NULL을 걸지 않는다.
 */
data class AgentStep(
    val action: String,
    @JsonProperty("case_id") val caseId: Long?,
    val hint: String?,
    val input: String?,
    val case: AgentCase?,
)

/**
 * 스텝에 동봉되는 TC 스펙(CSV 미러). 현행 TestCase 필드를 계약 이름으로 투영한다: `scene`=category,
 * `test_step`=title. `basis`(근거)는 TC가 스펙으로 확정될 때 추가 예약.
 */
data class AgentCase(
    val id: Long,
    val scene: String,
    val precondition: String?,
    @JsonProperty("test_step") val testStep: String,
    val expected: String,
)
