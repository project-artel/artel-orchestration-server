package kr.artel.orchestration.testrun.dto

/**
 * 저작 요청 취소의 결과(ARTEL-955).
 *
 * @property cancelled 끊을 턴이 **있었나**. 없었으면 화면은 아무것도 치우지 않는다 — 이미 끝난
 *   턴의 결과가 화면에 떠 있는데 "취소했습니다"를 덧붙이면 방금 받은 답을 의심하게 된다.
 * @property saved 이 턴이 취소되기 전에 **이미 저장한** 시나리오 수. 지우지 않으므로 사용자에게
 *   말해 줄 수다 — 멈춘 것은 남은 작업이고, 끝난 작업은 그대로 남는다. 카드 검토 모드
 *   (`autoApply=false`)에서는 저장한 것이 없으므로 0 이다.
 */
data class RunChatCancellation(
    val cancelled: Boolean,
    val saved: Int = 0,
)
