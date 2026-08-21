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

/**
 * 런을 지우면 무엇이 같이 없어지는지(ARTEL-487). 지우기 전에 화면이 물어보기 위한 값이다.
 *
 * 런 삭제는 조합만 끊고 시나리오는 남긴다. 그런데 커버리지는 런과 무관하게 프로젝트의 모든
 * 시나리오를 세므로, 남은 시나리오가 케이스를 계속 "담긴 것"으로 만든다 — 사용자는 런을 지웠는데
 * 숫자가 그대로인 것을 본다. 그래서 지울 때 함께 지울지 묻고, 무엇이 걸리는지 여기서 미리 센다.
 *
 * @property scenarioCount 이 런에 담긴 시나리오 수.
 * @property removableScenarioCount 그중 **다른 런에는 없는** 것 — 함께 지울 수 있는 것들.
 * @property keptForQaHistoryCount 그중 QA 실행 이력이 있어 함께 지우지 않을 것. 실행 기록은
 *   저작물보다 되돌리기 어렵다.
 */
data class RunDeletionPreview(
    val testRunId: String,
    val scenarioCount: Int,
    val removableScenarioCount: Int,
    val keptForQaHistoryCount: Int,
)

/** 런을 지운 결과. 화면이 "N개 함께 지웠다"고 말할 수 있게 실제로 지운 수를 돌려준다. */
data class RunDeletionResult(
    val deletedScenarioCount: Int,
    val keptForQaHistoryCount: Int,
)
