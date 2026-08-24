package kr.artel.orchestration.testcase.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 저작 Agent가 받는 케이스 한 줄. [TestCaseListItem]에 **정규화된 상태**를 얹은 것이다(ARTEL-466).
 *
 * 사전조건은 사람이 읽는 한 줄이다 — `Map_scene 화면인 상태 / (MapMove.StagePosition >= 1 그리고
 * MapMove.position == 0)`. 지금까지 Agent는 이 문장을 스스로 해석해 "이 케이스가 어디서 시작하나"를
 * 판단했고, 그 해석이 곧 케이스 순서를 정하는 근거였다. 같은 문장을 오케도 파싱하는데
 * ([ScenarioStateReader]), **두 해석이 어긋나면 조용하다** — Agent가 짠 순서를 코드가 다른 상태로
 * 계산해 메우게 된다.
 *
 * 그래서 오케가 읽은 값을 그대로 실어 보낸다. Agent는 문자열을 파싱하지 않고, 두 쪽이 같은 상태를 본다.
 * 원문(`precondition`)도 함께 둔다 — 정규화가 놓치는 서술(“대화가 진행 중인 상태”)이 있고, 그건
 * 사람 말로만 있다.
 *
 * @property stateBefore 이 케이스가 성립하려면 참이어야 하는 비교들. `>=`·`!=` 도 그대로 온다.
 * @property stateAfter 이 케이스를 실행한 뒤 **확정되는** 값. 다음 케이스의 출발 상태가 이것이다.
 */
data class AuthoringTestCase(
    val id: Long,
    val scene: String,
    val step: String,
    val precondition: String?,
    @JsonProperty("expected_value") val expectedValue: String,
    @JsonProperty("verification_status") val verificationStatus: String,
    @JsonProperty("state_before") val stateBefore: List<CaseGuard> = emptyList(),
    @JsonProperty("state_after") val stateAfter: Map<String, String> = emptyMap(),
)

/**
 * 비교 하나를 정규화한 것. 변수명은 **마지막 마디**로 통일한다 — 같은 값을 명세와 사전조건이
 * `StagePosition` · `MapMove.StagePosition` · `StageDataSingleton.stagePosition` 세 이름으로 부른다.
 */
data class CaseGuard(
    val variable: String,
    val operator: String,
    val value: String,
)
