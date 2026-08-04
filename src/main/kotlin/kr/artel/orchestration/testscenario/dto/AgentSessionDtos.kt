package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonProperty
import kr.artel.orchestration.testcase.dto.TestCaseSearchHit

/**
 * Agent 서버 `POST /sessions` 요청 본문(시나리오 생성 세션 열기).
 * 필드명은 Agent 계약(snake_case)에 맞춘다.
 *
 * game_context에는 프로젝트 최신 빌드의 씬 스캔(SDK가 등록 때 보고한 UI 구성)을 담아,
 * Agent가 어떤 화면을 대상으로 시나리오를 짜는지 참조하게 한다. 스캔을 보고한 빌드가 없으면
 * 빈 객체다. unity_context는 아직 연동 보류로 빈 객체를 보낸다.
 *
 * project_id/run_id는 저작 세션의 스코프다(ARTEL-206 Step 6). Agent는 `test_case_search` 프레임의
 * 검색을 이 프로젝트로 좁히고, 턴 결과 시나리오는 이 런에 추가·수정된다. 세션이 런 단위이므로 run_id는
 * 항상 존재한다.
 */
data class AgentSessionOpenRequest(
    @JsonProperty("user_input") val userInput: String,
    @JsonProperty("unity_context") val unityContext: Map<String, Any> = emptyMap(),
    @JsonProperty("game_context") val gameContext: Map<String, Any> = emptyMap(),
    val model: String,
    /**
     * 생성 결과의 출력 언어. Agent 계약의 `locale`(ko|en)에 대응한다.
     * 사용자의 계정 locale에서 정하며, 미설정 사용자는 en으로 보낸다.
     */
    val locale: String,
    @JsonProperty("project_id") val projectId: Long,
    @JsonProperty("run_id") val runId: Long
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

/**
 * Agent WS로 보내는 세션 종료 메시지. 사용자가 Approve/Delete로 편집을 마치면 이 메시지를 보내고,
 * Agent는 WS와 session_id(Redis)를 만료시킨 뒤 연결을 종료한다.
 */
data class AgentCloseMessage(
    val type: String = "close"
)

/**
 * Agent의 `test_case_search` 프레임에 대한 응답 프레임(ARTEL-206 Step 5).
 *
 * [correlationId]에 요청 messageId를 실어 Agent가 자기 도구 호출과 맞춘다. [results]의 각 항목은
 * [TestCaseSearchHit]을 그대로 쓴다 — Agent가 `verificationStatus`를 camelCase로 파싱하므로 필드명이
 * 계약 그대로 직렬화돼야 한다.
 */
data class TestCaseSearchResultFrame(
    val type: String = "test_case_search_result",
    val correlationId: String?,
    val results: List<TestCaseSearchHit>
)

/**
 * `test_case_search` 처리 실패를 Agent에 알리는 프레임(ARTEL-206 Step 5). 검색 실패가 WS/세션을 죽이지
 * 않도록, receive 콜백은 throw 대신 이 프레임으로 답한다. [correlationId]로 대기 중인 Agent 도구를 푼다.
 */
data class TestCaseSearchErrorFrame(
    val type: String = "error",
    val correlationId: String?,
    val detail: String
)
