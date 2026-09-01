package kr.artel.orchestration.testcase.dto

/**
 * 케이스 하나가 **바꾸는 값** 하나(ARTEL-581).
 *
 * 사전조건이 요구하는 값을 앞 스텝이 바꿔 놓으면, 둘은 모순이 아니라 순서다. 그 판단을 하려면
 * "이 케이스가 무엇을 바꾸나"가 있어야 하는데 케이스 표에는 없다 — 지도의 `capability_effect` 에
 * 있고, `capability_key` 가 그 자리로 가는 길이다.
 *
 * @property target 지도가 부르는 이름(`MapMove.position`). 사전조건은 같은 값을 `position` 으로
 *   적기도 해서, 맞추는 쪽이 꼬리로 견준다.
 * @property detail 어떻게 바꾸나 — `+1` 같은 증감이거나 `1` 같은 확정값이다(ARTEL-606). 얼마가
 *   되는지 모르는 증감도 그대로 싣는다. **어느 값이 어느 방향으로 움직이는지는 알기 때문**이고,
 *   저작이 브리지를 고를 때 필요한 것이 그것이다.
 */
data class CaseWrite(
    val caseId: Long,
    val target: String,
    val detail: String?,
)
