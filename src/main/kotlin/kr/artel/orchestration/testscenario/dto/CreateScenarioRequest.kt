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

/**
 * TestScenario 실시간 자동저장 요청 DTO. FE가 canvas 편집(드래그앤드롭/필드 수정)을 debounce로 묶어
 * 현재 draft 전체를 보내 payload를 덮어쓴다(last-write-wins). Agent를 거치지 않은 순수 FE 편집을
 * 영속화하는 유일한 경로다.
 */
data class UpdateScenarioRequest(
    val draft: ScenarioDraft
)

/**
 * TestScenario 승인(Approve) 요청 DTO. 사용자가 canvas에서 편집을 마친 최종 초안을 확정 저장한다.
 *
 * @property draft 확정할 최종 시나리오. 생략하면 마지막으로 저장된 payload를 그대로 최종본으로 둔다.
 */
data class ApproveScenarioRequest(
    val draft: ScenarioDraft? = null
)

/**
 * 기대 판정 라벨 일괄 수정 요청(ARTEL-301). 본문은 담지 않는다 — 라벨링 도구가 저작자가 방금 고친
 * 스텝을 되돌리지 못하게 하는 것이 이 분리의 요점이다.
 *
 * `expectedPassed`가 `null`이면 그 스텝을 **미지정으로 되돌린다.** 목록에서 항목을 빼는 것과 다르다 —
 * 목록에 없는 스텝은 손대지 않는다. 3상태를 그대로 실어 나르려면 둘이 구분돼야 한다.
 */
data class UpdateExpectedLabelsRequest(
    val labels: List<ExpectedLabelEntry> = emptyList()
) {
    /** 같은 스텝이 두 번 실리면 뒤엣것이 이긴다 — 요청 하나 안의 순서가 곧 사용자의 최종 입장이다. */
    fun toLabelMap(): Map<Int, Boolean?> = labels.associate { it.step to it.expectedPassed }
}

/** 스텝 번호(1부터)와 그 스텝의 기대 판정. */
data class ExpectedLabelEntry(
    val step: Int,
    @com.fasterxml.jackson.annotation.JsonProperty("expected_passed") val expectedPassed: Boolean?
)
