package kr.artel.orchestration.testscenario.dto

/**
 * React 대시보드(FE)에서 Orchestration으로 들어오는 TestScenario 챗봇 메시지 DTO.
 *
 * @property type 메시지 종류. 챗봇 특성상 일반 사용자 메시지 외에 폴백 질문에 대한 답변 등을 구분한다. (ex: "USER_MESSAGE", "ANSWER")
 * @property testScenarioMessage 사용자가 입력한 자연어 본문.
 */
data class TestScenarioMessage(
    val type: String,
    val testScenarioMessage: String
)
