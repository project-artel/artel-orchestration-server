package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.databind.JsonNode

/**
 * Agent 서버가 WebSocket으로 되돌려주는 응답 메시지 DTO (step 분리 결과 또는 폴백 질문).
 *
 * 1:1 커넥션(1 유저 = 1 sessionId = 1 WS)에서는 커넥션 자체가 세션 컨텍스트이므로 clientId는
 * 커넥션 문맥에서 결정되며, 메시지 본문에는 담지 않는다.
 *
 * @property type 이벤트 종류. FE가 step 결과/폴백 질문 등을 구분하는 데 사용된다.
 * @property agentSessionId Agent가 발급한 세션 식별자. 최초 응답에 실려 오며, Orchestration이
 *   clientId와 매핑해 이후 턴의 요청에 실어 대화 맥락을 유지한다.
 * @property payload step 데이터 등 실제 본문. 스키마 미확정·향후 확장 대비로 JsonNode로 통과시킨다.
 */
data class AgentScenarioResponse(
    val type: String,
    val agentSessionId: String? = null,
    val payload: JsonNode
)
