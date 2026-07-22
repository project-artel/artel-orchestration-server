package kr.artel.orchestration.testscenario.dto

/**
 * TestScenario를 새로 생성할 때의 요청 DTO.
 *
 * 편집 세션(SSE/WS)은 생성된 시나리오의 id를 키로 진행되므로, 시나리오는 세션보다 먼저 생성된다.
 *
 * @property projectId 이 시나리오가 속한 프로젝트.
 */
data class CreateScenarioRequest(
    val projectId: Long
)

/**
 * TestScenario 생성 응답. FE는 이 testScenarioId로 SSE/메시지 세션을 시작한다.
 */
data class CreateScenarioResponse(
    val testScenarioId: Long
)
