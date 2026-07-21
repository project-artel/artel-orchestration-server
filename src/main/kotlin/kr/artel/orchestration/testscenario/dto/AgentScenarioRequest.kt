package kr.artel.orchestration.testscenario.dto

/**
 * Orchestration이 Agent 서버로 WebSocket을 통해 전송하는 TestScenario 요청 DTO.
 *
 * `clientId`는 FE가 발급한 상관관계 키로, Agent WS 커넥션과 SSE 스트림을 잇는 안정적 식별자다.
 * `agentSessionId`는 Agent가 나중에 발급하므로 첫 턴에는 null이며, 이후 턴에는 Orchestration이
 * 보관한 매핑 값을 실어 대화 맥락을 유지한다.
 */
data class AgentScenarioRequest(
    val type: String,
    val testScenarioMessage: String,
    val clientId: String,
    val agentSessionId: String? = null
)
