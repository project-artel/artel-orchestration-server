package kr.artel.orchestration.testscenario.dto


import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import kr.artel.orchestration.testcase.dto.TestCaseFunctions

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
    /** `"ORDERED"` | `"FREE"`. FREE 면 step 하나가 TC 하나이고 순서·검증 방법은 agent 가 정한다(ARTEL-1028). */
    @param:JsonProperty("execution_mode") val executionMode: ScenarioExecutionMode,
)

/**
 * 스텝 하나 = 행위 하나. [caseId]가 있으면 그 TC의 스펙을 [case]에 리졸브해 동봉한다(없으면 null).
 * null 필드는 그대로 직렬화된다(키 존재 = Agent 계약) — 이 계약 DTO에는 NON_NULL을 걸지 않는다.
 */
data class AgentStep(
    val action: String,
    @param:JsonProperty("case_id") val caseId: Long?,
    val hint: String?,
    val input: String?,
    val case: AgentCase?,
)

/**
 * 스텝에 동봉되는 TC 스펙(CSV 미러). 현행 TestCase 필드를 계약 이름으로 투영한다: `scene`=category,
 * `test_step`=title. `basis`(근거)는 TC가 스펙으로 확정될 때 추가 예약.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(value = ["verification_procedure", "verificationProcedure"])
data class AgentCase(
    val id: Long,
    val scene: String,
    val precondition: String?,
    @param:JsonProperty("test_step") val testStep: String,
    val expected: String,
    /**
     * 이 TC 를 돌릴 때 허용할 cheat · behavior(ARTEL-1053). 정한 적 없으면 키를 싣지 않는다 — 없는 `functions` 는 agent 쪽에서
     * 둘 다 `all` 이다. 위 계약 DTO 와 달리 이 필드만 NON_NULL 인 것은 `null` 과 "정하지 않음"을 가를 이유가 없어서다.
     */
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val functions: TestCaseFunctions? = null,
    /**
     * 사람이 이 TC 의 판정을 정한 최근 기록(ARTEL-1256). 같은 실수를 피하라는 참고이고, 판정의 답이 아니다. 기록이 없으면
     * 키를 싣지 않는다 — 이 필드를 모르는 agent 와 기록이 없는 실행은 지금과 같다.
     */
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val reviews: List<AgentCaseReview>? = null,
)

/** 사람이 정한 판정 한 건: AI 가 뭐라 했고, 사람이 뭐라 정했고, 왜인지. */
data class AgentCaseReview(
    @param:JsonProperty("ai_passed") val aiPassed: Boolean?,
    @param:JsonProperty("human_passed") val humanPassed: Boolean?,
    val note: String?,
    @param:JsonProperty("game_build_version") val gameBuildVersion: String?,
    /** ISO-8601 문자열. `Instant` 로 두면 Jackson 이 숫자로 써서 agent 가 422 로 거절한다. */
    @param:JsonProperty("decided_at") val decidedAt: String?,
    @param:JsonProperty("case_snapshot") val caseSnapshot: String? = null,
    @param:JsonProperty("case_version_changed") val caseVersionChanged: Boolean? = null,
)
