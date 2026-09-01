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
 */
data class CaseWrite(
    val caseId: Long,
    val target: String,
)
