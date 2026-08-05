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
    val id: String,
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

/** 시나리오 조합 전체 교체(FE 드래그앤드롭 저장). caseIds 순서가 곧 position. id는 문자열. */
data class SetScenarioCasesRequest(
    val caseIds: List<String> = emptyList(),
)
