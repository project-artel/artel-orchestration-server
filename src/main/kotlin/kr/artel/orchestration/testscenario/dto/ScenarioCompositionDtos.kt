package kr.artel.orchestration.testscenario.dto

import kr.artel.orchestration.testcase.dto.TestCaseResponse

/** 시나리오 조합 한 칸 = 순서(position) + 그 자리에 참조된 케이스(내용까지 리졸브). */
data class ScenarioCaseItem(
    val position: Int,
    val case: TestCaseResponse,
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
