package kr.artel.orchestration.testscenario.dto

import java.time.Instant

/** 승인 대기 시나리오 하나. 사람이 팝업에서 읽고 승인하는 데 필요한 것을 모두 싣는다. */
data class ScenarioReviewItem(
    val testScenarioId: String,
    val title: String,
    val description: String,
    val executionMode: ScenarioExecutionMode?,
    val runs: List<ScenarioReviewRun>,
    val steps: List<ScenarioReviewStep>,
    val updatedAt: Instant,
    /** 승인된 시나리오를 고친 것이면 false. 이전 본문이 남지 않아 거절(삭제)할 수 없다. */
    val rejectable: Boolean,
)

data class ScenarioReviewRun(
    val id: String,
    val name: String,
)

/** 스텝이 Test Case 를 쓰면 그 Test Case 의 내용도 함께 싣는다. */
data class ScenarioReviewStep(
    val action: String,
    val testCaseId: String?,
    val scene: String?,
    val precondition: String?,
    val expectedValue: String?,
)
