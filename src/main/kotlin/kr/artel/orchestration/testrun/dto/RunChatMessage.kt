package kr.artel.orchestration.testrun.dto

/**
 * 저작 챗봇으로 들어오는 사용자 메시지(런 스코프) — ARTEL-206 Step 6.
 *
 * runId는 경로 변수로, userId는 JWT에서 얻으므로 본문에는 담지 않는다. 결과 시나리오는 이 런에 추가·수정된다.
 *
 * @property message 사용자가 입력한 자연어 본문.
 */
data class RunChatMessage(
    val message: String
)
