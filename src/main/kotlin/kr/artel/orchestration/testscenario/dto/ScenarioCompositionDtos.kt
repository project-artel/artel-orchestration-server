package kr.artel.orchestration.testscenario.dto

import kr.artel.orchestration.testcase.dto.TestCaseResponse

/**
 * 저작 Step 하나(그 자리의 사전조건 도달·실행 가이드) — ARTEL-254.
 *
 * advisory다: 실행 시 Agent가 씬과 다르면 무시하고 자기 판단으로 진행할 수 있다.
 * @property kind `setup`(사전조건 도달, assert=false·fast-forward) / `guide`(TC 실행) / `verify`(검증).
 * @property assert 판정 여부. setup은 false(판정 안 함).
 * @property intent 자연어 의도(코드 식별자가 아니라). @property hint 선택적 근거(키/백도어).
 * @property input 선택: `keyboard`|`click`(interactable 유무로 추론). @property observe verify가 볼 대상.
 */
data class ScenarioStepDto(
    // 저작 입력에서는 생략할 수 있다(빈 문자열 기본) — 클라이언트가 자리 핸들로 쓰는 값이라
    // 서버는 요구하지 않는다. 조회 응답에는 저장된 값이 그대로 실린다.
    val id: String = "",
    val kind: String,
    val assert: Boolean = true,
    val intent: String,
    val hint: String? = null,
    val input: String? = null,
    val observe: String? = null,
)

/** 시나리오 조합 한 칸 = 순서(position) + 그 자리에 참조된 케이스(내용까지 리졸브) + 그 자리의 저작 Step. */
data class ScenarioCaseItem(
    val position: Int,
    val case: TestCaseResponse,
    val steps: List<ScenarioStepDto> = emptyList(),
)

/** 시나리오의 케이스 조합 조회 응답(순서대로). */
data class ScenarioCasesResponse(
    val testScenarioId: String,
    val items: List<ScenarioCaseItem>,
)

/** 저작 쓰기 입력 한 칸(ARTEL-269): 그 자리의 케이스 + 그 자리의 저작 Step. */
data class ScenarioCaseInput(
    val caseId: String,
    val steps: List<ScenarioStepDto> = emptyList(),
)

/**
 * 시나리오 조합 전체 교체. 순서가 곧 position. id는 문자열.
 *
 * 두 형태를 받는다. [items]가 있으면 자리별 저작 Step까지 함께 저장하는 **쓰기 경로**(ARTEL-269)로,
 * 입력 steps가 권위다(빈 배열이면 그 자리 steps를 비운다). 없으면 [caseIds] 순서만 받는 기존
 * 경로(FE 드래그앤드롭)로, 자리가 유지되는 steps는 캐리포워드한다.
 */
data class SetScenarioCasesRequest(
    val caseIds: List<String> = emptyList(),
    val items: List<ScenarioCaseInput>? = null,
)
