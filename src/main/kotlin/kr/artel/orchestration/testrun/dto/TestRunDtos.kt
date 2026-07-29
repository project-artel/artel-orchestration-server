package kr.artel.orchestration.testrun.dto

import java.time.Instant

data class TestRunCreateRequest(
    val name: String? = null,
    val description: String? = null,
)

data class TestRunUpdateRequest(
    val name: String? = null,
    val description: String? = null,
)

/** id 계열은 FE 64비트 정밀도 손실 방지로 문자열. */
data class TestRunResponse(
    val id: String,
    val projectId: String,
    val name: String,
    val description: String?,
    val createdAt: Instant,
)

data class TestRunListResponse(val items: List<TestRunResponse>)

/** 런의 시나리오 조합 한 칸 = 순서 + 시나리오 id(내용은 FE가 시나리오 API로 별도 조회). */
data class RunScenarioItem(
    val position: Int,
    val testScenarioId: String,
)

data class RunScenariosResponse(
    val testRunId: String,
    val items: List<RunScenarioItem>,
)

/** 런의 시나리오 조합 전체 교체. scenarioIds 순서 = position. */
data class SetRunScenariosRequest(
    val scenarioIds: List<String> = emptyList(),
)
