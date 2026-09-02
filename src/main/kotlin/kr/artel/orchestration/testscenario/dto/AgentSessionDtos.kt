package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import kr.artel.orchestration.testcase.dto.AuthoringTestCase
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
/**
 * 걸을 수 있는 흐름 하나(ARTEL-658).
 *
 * @property caseIds 놓인 순서 그대로. 이것이 곧 실행 순서다.
 * @property opening 시작할 때 이미 참이어야 하는 것. 흐름이 스스로 만들지 못하는 요구다.
 * @property gaps 사이에 **지시할 수 없는 자리**가 몇 군데인가. 사람이 게임을 해서 지나가야 한다.
 */
data class AuthoringFlow(
    @JsonProperty("case_ids") val caseIds: List<Long>,
    val opening: List<String> = emptyList(),
    val gaps: Int = 0,
)

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
    @JsonProperty("test_case_list") val testCaseList: List<AuthoringTestCase> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val model: String? = null,
    /**
     * 생성 결과의 출력 언어. Agent 계약의 `locale`(ko|en)에 대응한다.
     * 사용자의 계정 locale에서 정하며, 미설정 사용자는 en으로 보낸다.
     */
    val locale: String,
    @JsonProperty("project_id") val projectId: Long,
    @JsonProperty("run_id") val runId: Long,
    @JsonProperty("current_scenarios") val currentScenarios: List<CurrentScenario> = emptyList(),
    /**
     * **계산이 낸, 걸을 수 있는 흐름들**(ARTEL-658).
     *
     * 무엇을 한 흐름에 묶고 어떤 순서로 놓을지는 실행 가능성을 정하는 판단인데, 모델이 42건을
     * 한 번에 들고 하기에 가장 약한 자리가 그 둘이다. 계산으로 옮기고 여기 실어 보낸다.
     *
     * **대본이 아니라 제약이다.** 순서를 바꾸거나 다른 케이스를 끼워 넣으면 걸을 수 있다는 보장이
     * 깨진다. 자르는 것은 안전하다 — 앞에서부터 자른 조각은 여전히 걸을 수 있다.
     *
     * 기본값이 빈 목록인 것은 [testCaseList] 와 같은 이유다. 비면 에이전트는 예전처럼 스스로
     * 묶고 순서를 정하므로, 되돌릴 때 양쪽을 다시 배포하지 않아도 된다.
     */
    val flows: List<AuthoringFlow> = emptyList(),
    /**
     * **게임을 켜면 열리는 화면**(ARTEL-670).
     *
     * 씬 그래프는 순환이라 구조로는 알 수 없고, 적재기가 빌드에서 읽어 적어 둔다. 여기가 지금까지
     * 계산 안에서만 쓰이고 **모델에게는 한 번도 안 갔다** — 실측(런 247)에서 프롬프트 121,712자
     * 안에 입구를 말하는 자리가 0회였다. 사람이 순서를 정할 때 가장 먼저 보는 것이 그것이다.
     *
     * 모르면 `null`. 지어내지 않고, 받는 쪽이 "안 왔다"라고 적는다.
     */
    @JsonProperty("entry_scene") val entryScene: String? = null,
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
 * @property inputs [capabilityIds]와 같은 길이의 **정규화된 조작**(`key:Return`·`click:경로`).
 *   Agent는 이 값을 스텝의 `input`에 그대로 넣는다 — 문장에서 다시 뽑아 쓰지 않게 하려는 것이다.
 * @property blockedBy `UNKNOWN`일 때 막는 것 — 씬 쌍(`A→B`) 또는 변수명. **지어내지 말라는 말보다
 *   무엇이 막는지를 주는 편이 낫다** — 사용자에게 물어볼 거리가 그 이름이다.
 */
data class ScenarioPathResultFrame(
    val type: String = "find_path_result",
    val correlationId: String?,
    val result: String,
    val capabilityIds: List<Long> = emptyList(),
    val actions: List<String> = emptyList(),
    val inputs: List<String> = emptyList(),
    /** `REVERSED` 면 **메우기 전에 순서를 보라는 뜻**이다. `CHAINED`·`NO_OPINION` 은 그대로 진행. */
    val ordering: String = "NO_OPINION",
    val blockedBy: String? = null,
    val note: String = ""
)

/**
 * Agent의 `explain_case` 프레임에 대한 응답(ARTEL-466).
 *
 * "이 케이스는 무엇으로 이루어져 있나"에 대한 답이다. 케이스 목록에는 그 케이스가 몇 번의
 * 조작인지, 그 조작을 뭐라고 부르는지가 없다 — 그래서 저작이 케이스 제목을 옮겨 적은 문장을
 * 스텝으로 쓰고 브리지를 한 줄로 뭉갠다. 지도에는 있으므로 물어보면 답한다.
 *
 * @property operations 이 케이스가 가리키는 조작들. **빈 배열이 정상적인 답이다** — 지도가 아직
 *   그 기능을 모른다는 뜻이고, 그때는 조작 이름을 지어내지 않는 것이 옳다.
 * @property observable 기대결과를 실행 중에 되읽을 수 있나. 조작을 못 찾았으면 null(모름).
 */
data class CaseFactsResultFrame(
    val type: String = "explain_case_result",
    val correlationId: String?,
    val testCaseId: Long,
    val scene: String? = null,
    val stateBefore: List<CaseGuardFrame> = emptyList(),
    val stateAfter: Map<String, String> = emptyMap(),
    val operations: List<CaseOperationFrame> = emptyList(),
    val observable: Boolean? = null,
    val note: String = "",
)

data class CaseGuardFrame(val variable: String, val operator: String, val value: String)

/**
 * @property input 스텝의 `input`에 그대로 넣는 값(`key:Return`·`click:경로`).
 * @property matchedBy `evidence`는 그 케이스가 가리키는 코드 자체, `effect`는 같은 값을 건드리는
 *   기능이라 여럿일 수 있다. 뭉뚱그리면 "정확히 이것"과 "아마 이 중 하나"가 구분되지 않는다.
 *
 * 전제를 싣는 칸은 없다. 예전에 `capability.given_text` 를 넣던 자리가 있었는데, 그 칸은 조건을
 * 사람이 읽는 한 줄로 옮긴 것이라 값의 소속과 `either` / `every` 구분이 옮기는 과정에서 사라진다
 * (ARTEL-447). 이 프레임을 받는 쪽이 조건을 알아야 하면 [CaseFactsResultFrame.stateBefore] 를
 * 읽는다 — 그쪽은 트리에서 나온다.
 */
data class CaseOperationFrame(
    val capabilityId: Long,
    val input: String,
    val label: String?,
    val summary: String,
    /** 실행 축(ARTEL-479). 관측 불가까지 섞인 `status` 대신 이것을 낸다. */
    val actionability: String,
    val matchedBy: String,
)
