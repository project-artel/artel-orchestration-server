package kr.artel.orchestration.testscenario.dto

/**
 * React 대시보드(FE)에서 Orchestration으로 들어오는 TestScenario 챗봇 메시지 DTO.
 *
 * projectId는 경로 변수로, userId는 JWT에서 얻으므로 본문에는 담지 않는다.
 *
 * @property type 메시지 종류. (ex: "USER_MESSAGE", "ANSWER")
 * @property testScenarioMessage 사용자가 입력한 자연어 본문.
 * @property draft 사용자가 canvas에서 편집한 현재 시나리오. 있으면 Agent가 이걸 기준으로 수정하고,
 *   없으면(null) 대화 맥락 기준으로 진행한다. 첫 입력(세션 오픈)에는 적용되지 않는다.
 */
data class TestScenarioMessage(
    val type: String,
    val testScenarioMessage: String,
    val draft: ScenarioDraft? = null
)
