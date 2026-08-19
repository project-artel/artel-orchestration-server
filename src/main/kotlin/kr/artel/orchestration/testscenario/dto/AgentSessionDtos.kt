package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import kr.artel.orchestration.testcase.dto.TestCaseListItem
import kr.artel.orchestration.testcase.dto.UncoveredScene
import kr.artel.orchestration.testcase.dto.TestCaseSearchHit

/**
 * Agent 서버 `POST /sessions` 요청 본문(시나리오 생성 세션 열기).
 * 필드명은 Agent 계약(snake_case)에 맞춘다.
 *
 * game_context에는 프로젝트 최신 빌드의 씬 스캔(SDK가 등록 때 보고한 UI 구성)을 담아,
 * Agent가 어떤 화면을 대상으로 시나리오를 짜는지 참조하게 한다. 스캔을 보고한 빌드가 없으면
 * 빈 객체다. unity_context는 아직 연동 보류로 빈 객체를 보낸다.
 *
 * project_id/run_id는 작성 세션의 스코프다(ARTEL-206 Step 6). Agent는 `test_case_search` 프레임의
 * 검색을 이 프로젝트로 좁히고, 턴 결과 시나리오는 이 런에 추가·수정된다. 세션이 런 단위이므로 run_id는
 * 항상 존재한다.
 *
 * current_scenarios는 런의 현재 시나리오 구성이다 — Agent가 사용자 자연어에서 어느 기존 시나리오를
 * 수정할지 id로 지목하는 근거다(없으면 빈 배열 = 아직 빈 런).
 *
 * test_case_list는 프로젝트 TestCase 전량의 압축 목록이다(ARTEL-318). game_context와 같은 성격 —
 * 세션 오픈 때 한 번 실어 보내고 Agent가 대화 내내 들고 있는 배경 지식이며, 툴 호출이 아니다.
 */
data class AgentSessionOpenRequest(
    @JsonProperty("user_input") val userInput: String,
    @JsonProperty("unity_context") val unityContext: Map<String, Any> = emptyMap(),
    @JsonProperty("game_context") val gameContext: Map<String, Any> = emptyMap(),
    /**
     * 프로젝트 TestCase 전량(ARTEL-318). 지금까지 Agent는 `test_case_search`(벡터)로만 케이스를 알 수
     * 있어 실효 노출이 30~40건이었고, 못 찾은 것이 있다는 사실조차 알 수 없었다. 전량을 미리 실어
     * "존재를 몰라서 빠뜨리는" 실패를 없앤다.
     *
     * **기본값이 빈 목록인 것은 하위 호환 장치다.** Agent는 이 값이 비면 기존 검색 경로로 동작하므로,
     * 되돌릴 때 양쪽을 다시 배포하지 않아도 된다.
    */
    @JsonProperty("test_case_list") val testCaseList: List<TestCaseListItem> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val model: String? = null,
    /**
     * 생성 결과의 출력 언어. Agent 계약의 `locale`(ko|en)에 대응한다.
     * 사용자의 계정 locale에서 정하며, 미설정 사용자는 en으로 보낸다.
     */
    val locale: String,
    @JsonProperty("project_id") val projectId: Long,
    @JsonProperty("run_id") val runId: Long,
    @JsonProperty("current_scenarios") val currentScenarios: List<CurrentScenario> = emptyList()
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
 * @property currentScenarios 턴 시점의 런 현재 시나리오 구성. 이전 턴에서 추가·수정된 결과가 반영된 최신
 *   상태를 매 턴 다시 실어, Agent가 수정 대상을 정확한 id로 지목하게 한다.
 */
data class AgentTurnMessage(
    val type: String = "turn",
    @JsonProperty("user_input") val userInput: String,
    @JsonProperty("current_scenarios") val currentScenarios: List<CurrentScenario> = emptyList(),
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

/**
 * Agent의 `uncovered_cases` 프레임에 대한 응답(ARTEL-403).
 *
 * 세션 오픈에 실어 보내지 않고 **물어볼 때 답하는** 이유는 이 값이 저작 중에 줄어들기 때문이다.
 * 스냅샷은 둘째 턴부터 틀리고, 매 턴 다시 실으면 턴 메시지가 붓거나 system 프롬프트에 있을 경우
 * 전량 목록 캐시를 통째로 버린다.
 *
 * @property ids Agent가 케이스 본문을 자기 목록에서 찾아 인용하는 데 쓴다.
 * @property scenes 사람에게 답할 축. 번호는 화면에 내보내지 않는 값이라 씬과 건수가 있어야 말이 된다.
 */
data class UncoveredCasesResultFrame(
    val type: String = "uncovered_cases_result",
    val correlationId: String?,
    val ids: List<Long>,
    val scenes: List<UncoveredScene>
)

/**
 * Agent의 `find_path` 프레임에 대한 응답(ARTEL-466).
 *
 * **경로 계산이 Agent가 아니라 여기 있는 이유**는 검증 때문이다. 그래프를 Agent에만 두면
 * 나중에 "정말 모르는 길이었나"를 대조할 쪽이 없어지고, 그러면 전부 모른다고 적는 것이 가장
 * 싼 통과 방법이 된다. 계산하는 쪽과 검사하는 쪽이 같아야 그 구멍이 막힌다.
 *
 * @property result `KNOWN`|`NOT_REQUIRED`|`UNKNOWN`. 셋이 "사이에 스텝이 필요한가"라는 한 질문의 답이다.
 * @property capabilityIds `KNOWN`일 때 순서대로. 스텝의 근거로 그대로 옮겨 적게 한다.
 * @property actions [capabilityIds]와 같은 길이의 사람 말. Agent가 스텝 문장으로 베낀다.
 * @property blockedBy `UNKNOWN`일 때 막는 것 — 씬 쌍(`A→B`) 또는 변수명. **지어내지 말라는 말보다
 *   무엇이 막는지를 주는 편이 낫다** — 사용자에게 물어볼 거리가 그 이름이다.
 */
data class ScenarioPathResultFrame(
    val type: String = "find_path_result",
    val correlationId: String?,
    val result: String,
    val capabilityIds: List<Long> = emptyList(),
    val actions: List<String> = emptyList(),
    val blockedBy: String? = null,
    val note: String = ""
)
