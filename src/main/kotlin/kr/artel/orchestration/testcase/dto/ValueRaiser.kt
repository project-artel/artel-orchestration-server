package kr.artel.orchestration.testcase.dto

/**
 * 값 하나가 **어느 화면에서 움직이나**(ARTEL-635).
 *
 * 저작이 전제를 볼 때 `position == 0` 과 `StagePosition >= 1` 은 한 줄로 구별되지 않는다.
 * 앞엣것은 방향키 한 번이고 뒤엣것은 전투를 이겨야 오른다 — 그 차이를 여기서 말한다.
 */
data class ValueRaiser(
    val target: String,
    val scene: String,
)

/**
 * 값 하나를 바꾸는 자리 하나(ARTEL-646).
 *
 * [ValueRaiser] 가 화면 이름만 답하던 자리를 넓힌 것이다 — 저작이 `position == 0`(방향키 한 번)과
 * `StagePosition >= 1`(전투를 이겨야 함)을 가르려면 넷이 다 필요하다.
 *
 * @property target 지도가 부르는 값 이름. `MapMove.StagePosition` 처럼 소유자가 붙어 있다.
 * @property scene 그 일이 일어나는 화면.
 * @property detail 얼마씩 바뀌나. `+1` · `0` · 기호 값(`PlayerPrefs.GetInt(…)`).
 * @property actionability 조작으로 시킬 수 있나. `not-a-step` 이면 사람이 조건을 만들어야 한다.
 * @property operation 누를 것. 키가 먼저고 경로·라벨이 그다음이다.
 * @property conditionTree 그 일이 일어나는 조건. 구조 그대로 — 읽는 쪽이 문장으로 편다.
 */
data class ValueMoveRow(
    val target: String,
    val scene: String,
    val detail: String?,
    val actionability: String?,
    val operation: String?,
    val interaction: String?,
    val conditionTree: io.r2dbc.postgresql.codec.Json?,
)
