package kr.artel.orchestration.testrun.dto

import kr.artel.orchestration.testscenario.dto.ScenarioQuestionAnswer

/**
 * 작성 챗봇으로 들어오는 사용자 메시지(런 스코프) — ARTEL-206 Step 6.
 *
 * runId는 경로 변수로, userId는 JWT에서 얻으므로 본문에는 담지 않는다. 결과 시나리오는 이 런에 추가·수정된다.
 *
 * @property message 사용자가 입력한 자연어 본문.
 * @property answer 되묻는 질문에 대한 답(ARTEL-487). 있으면 [message]와 함께 실려 나간다 —
 *   보기를 고르고 한 줄 덧붙이는 것이 가장 흔한 모양이라 둘을 나누지 않는다.
 * @property autoApply Agent 결과를 서버가 즉시 반영할지. `true`(기본)면 자동저장, `false`면 저장하지 않고
 *   제안으로만 두어 사용자가 카드로 검토·커밋한다(카드 검토 모드). FE의 사용자 토글에서 정한다.
 */
data class RunChatMessage(
    val message: String,
    val answer: ScenarioQuestionAnswer? = null,
    val autoApply: Boolean = true
)
