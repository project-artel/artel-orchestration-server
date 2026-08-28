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
