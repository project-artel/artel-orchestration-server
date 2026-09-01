package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue

/**
 * 시나리오 스텝 하나 = **행위 하나**(재설계 2026-08-07). 시나리오는 스텝의 순서 있는 집합이고,
 * 스텝이 1급 단위다.
 *
 * @property action 이 스텝에서 하는 행위(자연어). @property caseId 이 스텝이 속한 TC(검증 대상).
 *   **nullable** — 대부분의 스텝은 그냥 조작이라 검증 대상이 없다. **연속된 동일 caseId = 그 TC의 검증
 *   구간**이고, 그 구간의 마지막 스텝을 수행한 뒤 그 TC의 `expected`로 판정한다. @property hint 선택적
 *   근거(키/백도어). @property input 선택: `keyboard`|`click` 등.
 */
data class ScenarioStep(
    val action: String = "",
    @JsonProperty("case_id") val caseId: Long? = null,
    val hint: String? = null,
    val input: String? = null,
    /**
     * 이 스텝이 **통과해야 하는지 실패해야 하는지**에 대한 사람의 판단(ARTEL-301). QA 에이전트의
     * 스텝 판정은 자기채점이라, 이 라벨과 대조하지 않으면 관대한 모델이 높은 점수를 받고 "전부
     * 통과"라고 답하는 전략이 만점이 된다.
     *
     * **nullable이고 기본값이 없다. null은 "채점하지 않음"이지 "통과해야 함"이 아니다.** 기본값을
     * true로 두면 라벨을 안 단 스텝이 전부 통과 기대로 세어져 정확도가 부풀려지고, 그 오류는 조용히
     * 지나간다(V27의 `knowledge_usage.cited`와 같은 규율). 이 필드가 생기기 전에 만들어진 시나리오는
     * 전부 null이며 백필하지 않는다 — 사람이 판단해야 하는 값을 기계가 지어내면 정답지가 오염된다.
     *
     * 검증 스텝(구간의 마지막 스텝)의 라벨이 곧 그 TC의 기대 판정이다. 일반 스텝의 라벨은 부가지만
     * 둘 다 채점 대상이다.
     *
     * ⚠️ **이 값은 실행 계약으로 나가면 안 된다.** 에이전트가 답을 알고 실행하면 이 측정 전체가
     * 무의미해지고, 그 사고는 점수가 좋아 보일 뿐이라 조용하다. 에이전트가 읽는 두 경로는 타입으로
     * 갈라져 있다: QA 실행은 [AgentStep], 작성 챗봇은 [ChatScenarioStep] — 둘 다 이 필드가 없다.
     */
    @JsonProperty("expected_passed") val expectedPassed: Boolean? = null,
    /**
     * 이 스텝을 **어디서 가져왔는가**(ARTEL-467). `CASE`|`CAPABILITY`|`UNKNOWN`.
     *
     * 검증 스텝은 원래 근거가 있었다 — [caseId]가 "이 스텝은 이 케이스를 본다"고 말해 준다.
     * **브리지 스텝만 근거가 없었고**, 그래서 지어낸 스텝과 알고 쓴 스텝을 기계가 구분할 수 없었다.
     * 그 구분이 없는 것이 실행 중에 터지는 지점이다.
     *
     * **null이면 검사를 건너뛴다.** 이 필드를 보내지 않는 구버전 Agent와 함께 배포되기 위해서이고,
     * 되돌리는 스위치가 이 null 하나뿐이라 롤백이 Agent 재배포만으로 끝난다([ReviewedCases]와 같은 규율).
     */
    @JsonProperty("step_source") val stepSource: ScenarioStepSource? = null,
    /**
     * 할 일인가 알림인가(ARTEL-468). null은 [ScenarioStepKind.ACTION] — 이 필드가 없던 시절의
     * 시나리오다. [ScenarioStepKind.GAP]은 수행 대상도 판정 대상도 아니다.
     */
    @JsonProperty("step_kind") val stepKind: ScenarioStepKind? = null,
    /** [stepSource]가 `CAPABILITY`일 때 그 기능의 id. `capability.id`와 타입이 같아 검사가 조건문 하나다. */
    @JsonProperty("step_source_capability_id") val stepSourceCapabilityId: Long? = null,
    /** [stepSource]가 `UNKNOWN`일 때 **무엇이 막는지**. 사용자에게 물어볼 거리가 이 문장이다. */
    @JsonProperty("step_unknown_reason") val stepUnknownReason: String? = null,
)

/**
 * 스텝의 근거 종류(ARTEL-467).
 *
 * 한 문자열에 담지 않고 [ScenarioStep.stepSource]·[ScenarioStep.stepSourceCapabilityId]·
 * [ScenarioStep.stepUnknownReason] 셋으로 나눈 것은 프로토타입에서 한 필드(`"unknown:StagePosition을…"`)로
 * 두었다가 파싱이 필요해졌고, `case_id`가 있는데 근거는 간선이라고 적은 **계약 위반이 8건** 나왔기
 * 때문이다. 셋으로 나누면 검사가 조건문 셋으로 끝난다.
 */
enum class ScenarioStepSource {
    /** 이 스텝은 [ScenarioStep.caseId]의 케이스를 검증한다. */
    CASE,

    /** 이 스텝은 씬 명세의 그 기능을 탄다. `find_path`가 답한 것을 그대로 옮긴 자리다. */
    CAPABILITY,

    /**
     * 길을 모른다.
     *
     * **통과 사유이면서 검사 대상이다.** 무조건 통과시키면 전부 이것으로 적는 것이 가장 싼 통과
     * 방법이 되어 검사가 무의미해진다. 명세가 아는 길에 이것을 적었으면 거짓이므로 거부한다.
     */
    UNKNOWN,

    /**
     * 사람이 직접 쓴 스텝.
     *
     * 명세가 모르는 자리를 사용자가 메운 것이라 **코드가 건드리지 않는다** — 지우지도, 알림
     * 블록으로 접지도 않는다. 근거를 묻는 검사도 여기서 멈춘다: 사람이 적었다는 것이 근거다.
     *
     * 이 값이 없으면 사용자가 손으로 채운 스텝이 다음 저장 때 "확인되지 않은 브리지"로 취급돼
     * 알림 안의 인용문으로 접힌다 — 사용자가 답을 줬는데 그 답이 사라진다.
     */
    HUMAN,
}

/**
 * 이 줄이 **할 일인가, 알림인가**(ARTEL-468).
 *
 * [GAP]은 실행할 것이 아니다 — 두 검증 사이의 경로를 명세가 몰라 **비워 둔 자리**이고, 그 사실을
 * 사람에게 알리는 블록이다. 수행 대상도 판정 대상도 아니다.
 *
 * 예전에는 이것도 스텝으로 넣었다. 그러면 "명세에 없다"는 문장을 실행 에이전트가 수행하려 들고
 * 판정 대상으로도 세어진다 — **못 한 일이 실패로 기록되는 셈**이라 실측이 오염된다. 종류를 나눠
 * 두면 반대로 쓸모가 생긴다: 여기가 모르는 구간이라는 것을 실행하는 쪽이 미리 알고, 그 자리를
 * 먼저 탐색할 수 있다.
 *
 * null은 [ACTION]이다 — 이 필드가 없던 시절에 저장된 시나리오가 전부 그렇다.
 */
enum class ScenarioStepKind {
    /** 수행하는 줄. */
    ACTION,

    /** 메우지 못한 자리를 알리는 블록. 수행하지도 판정하지도 않는다. */
    GAP,

    /**
     * **여기까지 와야 시작한다**를 적는 첫 줄(ARTEL-636). 수행하지도 판정하지도 않는다.
     *
     * 시나리오는 하나가 끝날 때마다 게임을 초기화하는데 검증하는 순간은 게임 곳곳에 흩어져 있다 —
     * 엔딩을 보는 시나리오는 매번 엔딩까지 다시 가야 한다. 아무 말도 없이 시작하면 실행하는
     * 쪽에게는 "알아서 네 번 이겨라"와 같다.
     *
     * [GAP] 과 같은 이유로 스텝이 아니다. 이것을 수행하려 들면 "…인 상태에서 시작한다"가 조작으로
     * 읽히고, 판정 대상으로 세면 **아직 시작도 안 한 일이 실패로 기록된다.**
     */
    OPENING,
}

/**
 * 시나리오 초안(ScenarioDraft). test_scenario의 title/description/steps 컬럼으로 저장되고(ARTEL-291),
 * SSE 이벤트·조회 응답으로 FE에 전달된다. **시나리오 = steps의 순서 집합**(재설계). 생성 직후(빈 시나리오)를 위해 기본값을 둔다.
 */
data class ScenarioDraft(
    val title: String = "",
    val description: String = "",
    val steps: List<ScenarioStep> = emptyList()
)

/**
 * Agent 턴 결과가 참조하는 시나리오 하나(ARTEL-206 Step 5·6, 재설계 2026-08-07). Agent는 시나리오를
 * **여러 개** 돌려주며, 각 시나리오는 [steps]의 순서 집합이다. 한 응답에 **추가와 수정이 섞여** 올 수 있다.
 *
 * @property scenarioId `null`이면 **새 시나리오 추가**(INSERT + 런 append), 값이 있으면 그 **기존 시나리오
 *   수정**(본문 통째 교체). Agent 계약의 `scenario_id`에 대응한다.
 * @property steps 이 시나리오의 스텝들(순서=실행 순서). 각 스텝은 행위 + 선택적 caseId. Agent 계약의
 *   `steps`에 대응한다. 타입이 [ScenarioStep]이 아니라 [ChatScenarioStep]인 것은 의도다 — 에이전트는
 *   기대 판정 라벨을 본 적이 없고, 이 타입에는 그 필드가 아예 없어 되돌려 보낼 수도 없다(ARTEL-301).
 */
data class ScenarioResult(
    @JsonProperty("scenario_id") val scenarioId: Long? = null,
    val title: String = "",
    val description: String = "",
    val steps: List<ChatScenarioStep> = emptyList()
)

/**
 * Agent가 이번 요청에 대해 프로젝트 TestCase **전 건**을 어떻게 판정했는지(2단계).
 *
 * 포함 목록만 받지 않는 이유가 이 클래스의 존재 이유다. 포함된 것만 오면 나머지를 *검토하고 뺀 것*
 * 인지 *아예 안 본 것*인지 구분할 수단이 없어, "다 봤는가"를 검사할 대상 자체가 생기지 않는다.
 * 전 건 판정을 받으면 **판정이 빠진 id = 검토하지 않은 케이스**가 되어 기계가 뺄셈으로 센다
 * ([kr.artel.orchestration.testscenario.service.ScenarioCoverageAudit]).
 *
 * 두 배열로 나눈 것은 비용 때문이다. 실측(2026-08-13) 1000건 기준 이 모양이 3,005 tok인 반면
 * `{"82":1,…}` 맵 모양은 5,001 tok으로 40% 비싸고, 검사도 이쪽이 단순하다 — `in ∪ out`이 전량과
 * 같은지만 보면 된다.
 *
 * 제외 사유는 받지 않는다. 건당 사유를 붙이면 1000건에서 출력이 배로 뛰고, 무엇보다 그것도 다시
 * Agent의 자기 서술이라 검사할 수 없다. 포함 쪽의 근거는 스텝이 증명한다.
 */
data class ReviewedCases(
    @JsonProperty("in") val included: List<Long> = emptyList(),
    @JsonProperty("out") val excluded: List<Long> = emptyList(),
)

/**
 * 저작 한 턴이 지나는 단계(ARTEL-419). `progress` 이벤트의 유일한 내용이다.
 *
 * **누군가가 실제로 본 것만 단계가 된다.** 대부분은 오케스트레이션이 본 것이고([SENT]·도구 프레임·
 * 검사 결과), [THINKING] 하나만 Agent 가 알려 준다 — 모델이 도는 시간은 이쪽에서 보이지 않는데
 * 그 시간이 턴의 대부분이라, 없으면 "케이스 조회" 한 줄만 띄운 채 몇십 초가 지나간다.
 *
 * 순서는 [SENT] → ([THINKING] ↔ 도구) → [WRITING] → [CHECKING] → 종착([SAVED]/[REPAIRING]/[BLOCKED])이지만
 * **가운데는 없을 수 있다.** 도구를 부르지 않는 턴이 정상이기 때문이다. 그래서 화면은 고정된
 * 눈금을 그려 놓고 채우는 것이 아니라 **받은 단계만** 그린다 — 일어나지 않은 일을 지나갔다고
 * 말하지 않기 위해서다. 같은 단계가 잇달아 오면 화면이 한 줄로 접고 횟수를 센다(도는 중이라는 뜻).
 */
enum class AuthoringStage(@get:JsonValue val wire: String) {
    /** 턴을 Agent로 보냈다. 여기서부터 응답이 오기 전까지 오케스트레이션은 아무것도 보지 못한다. */
    SENT("sent"),

    /**
     * 모델이 한 턴을 시작했다(ARTEL-487). **Agent가 알려 주는 유일한 단계다** — 도구 호출은
     * 프레임으로 보이지만 그 사이의 시간은 오케에서 보이지 않아, 길어질수록 침묵과 구분되지 않았다.
     * 도구와 번갈아 나타나므로 몇 번을 돌고 있는지도 이 줄로 읽힌다.
     */
    THINKING("thinking"),

    /** Agent가 케이스를 물어봤다(`uncovered_cases`/`test_case_search`). 멎지 않았다는 첫 증거다. */
    LOOKING_UP_CASES("looking_up_cases"),

    /** Agent가 케이스 하나의 근거를 물어봤다(`explain_case`). 무엇으로 스텝을 쓸지 확인하는 중이다. */
    READING_CASE("reading_case"),

    /** Agent가 두 케이스 사이의 경로를 물어봤다(`find_path`). 씬 명세를 걷는 시간이다. */
    FINDING_PATH("finding_path"),

    /**
     * 케이스를 넘겨줬고 아직 결과가 오지 않았다.
     *
     * **이것만은 관측이 아니라 추론이다.** 넘겨준 자료로 쓰고 있는지, 도구를 한 번 더 부를지
     * 오케스트레이션은 알지 못한다. 아는 것은 자료가 건너갔다는 사실뿐이라 문구도 거기까지만 말한다.
     */
    WRITING("writing"),

    /** 결과가 도착해 전 건 판정과 대조하는 중(ARTEL-403). */
    CHECKING("checking"),

    /** 검사를 통과해 시나리오를 저장했다. */
    SAVED("saved"),

    /** 빠진 부분을 다시 쓰라고 되돌려 보냈다. 종착이 아니다 — 결과가 한 번 더 온다. */
    REPAIRING("repairing"),

    /** 검사를 통과하지 못했고 더 고칠 수 없어 **한 줄도 저장하지 않았다.** */
    BLOCKED("blocked"),
}

/**
 * SSE로 FE에 전달하는 이벤트 봉투. Agent 응답(result/error)과 서버가 만든 알림을 타입화한다.
 *
 * - `type == "result"`   → `message` + `scenarios`(0개 이상. 빈 배열은 "질문/거절/무매치" 같은 정상 턴이다)
 * - `type == "error"`    → `code` + `detail`
 * - `type == "progress"` → `stage`(ARTEL-419)
 * - `type == "notice"`   → `message`. 서버가 쓴 ASSISTANT 메시지다(재작성 통보·검사 실패·잔량 안내).
 *
 * `type`이 그대로 SSE 이벤트명이 되므로([TestScenarioStreamManager.emit]) 새 타입을 더하는 것은
 * **하위 호환이 공짜다** — 모르는 이벤트명은 `EventSource`가 리스너 없이 흘려보낸다.
 *
 * @property reviewed 전 건 판정(2단계). **null이면 검사를 건너뛴다** — 이 필드를 보내지 않는 구버전
 *   Agent와 함께 배포되기 위해서다. 검사를 끄는 스위치가 이 null 하나뿐이라 롤백이 Agent
 *   재배포만으로 끝난다.
 * @property stage `progress`의 내용(ARTEL-419).
 */
data class ScenarioStreamEvent(
    val type: String,
    val message: String? = null,
    val scenarios: List<ScenarioResult>? = null,
    val reviewed: ReviewedCases? = null,
    val code: String? = null,
    val detail: String? = null,
    val stage: AuthoringStage? = null,
    /**
     * 사용자에게 되묻는 질문(ARTEL-487). Agent 결과에 실려 오거나, 오케가 계산된 사실로 만들어
     * `question` 이벤트로 내보낸다. **저장을 막지 않는다** — 답하지 않아도 그 턴의 결과물은 남는다.
     */
    val question: ScenarioQuestion? = null,
    /**
     * 모르는 자리 **전부**(ARTEL-630). [question] 은 그중 첫 것이고, 옛 화면을 위해 남긴다.
     *
     * 하나만 보내던 때는 막힌 자리가 여럿이어도 나머지가 아무 말 없이 미상으로 남았다 —
     * 실측(런 178)에서 일곱 중 하나만 물었고, 사용자는 시나리오가 완성된 줄 안다. 한 번에
     * 보여 줘야 아는 것만 답하고 나머지는 그대로 둘 수 있다.
     */
    val questions: List<ScenarioQuestion> = emptyList()
)
