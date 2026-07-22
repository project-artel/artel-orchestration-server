package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Agent 서버 `POST /sessions` 요청 본문(시나리오 생성 세션 열기).
 * 필드명은 Agent 계약(snake_case)에 맞춘다.
 *
 * unity_context/game_context는 SDK/게임 상태에서 오는 값이나 현재는 SDK 연동 보류로 빈 객체를 보낸다.
 */
data class AgentSessionOpenRequest(
    @JsonProperty("user_input") val userInput: String,
    @JsonProperty("unity_context") val unityContext: Map<String, Any> = emptyMap(),
    @JsonProperty("game_context") val gameContext: Map<String, Any> = emptyMap(),
    val model: String
)

/**
 * Agent 서버 `POST /sessions` 응답 본문. 이 session_id로 WS `/sessions/{session_id}`를 연다.
 */
data class AgentSessionOpenResponse(
    @JsonProperty("session_id") val sessionId: String
)

/**
 * Agent WS로 보내는 턴 메시지. 세션 오픈 이후의 후속 사용자 입력에 사용한다(첫 입력은 세션 오픈에 실린다).
 *
 * @property draft 사용자가 수정한 현재 초안(있으면 이걸 기준으로 진행). 현재는 미사용(null).
 */
data class AgentTurnMessage(
    val type: String = "turn",
    @JsonProperty("user_input") val userInput: String,
    val draft: ScenarioDraft? = null,
    val model: String? = null
)
