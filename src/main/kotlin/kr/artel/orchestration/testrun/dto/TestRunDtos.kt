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
 * **이 런의 시나리오들이 무엇을 담았는가**(ARTEL-903). 한 줄 = 시나리오 하나.
 *
 * @property steps 저장된 스텝 수. 코드가 메운 `bridge` 까지 센 최종본이다.
 * @property cases 그 스텝들이 검증하는 서로 다른 케이스 수. `bridge` 는 아무것도 검증하지 않으므로
 *   [steps] 보다 작고, 그 차이가 곧 "옮겨 가기만 하는 스텝" 의 수다.
 */
data class RunCoverageScenario(
    val position: Int,
    val testScenarioId: String,
    val title: String,
    val steps: Int,
    val cases: Int,
)

/**
 * **현재 시나리오에 한정된 커버리지**(ARTEL-903).
 *
 * 프로젝트 전량을 재는 `GET /api/projects/{projectId}/test-cases/coverage` 와 다른 축이다. 저작
 * 중에 알고 싶은 것은 "이 프로젝트에 케이스가 몇 건인가" 가 아니라 **"지금 만들고 있는 것들이
 * 무엇을 덮었는가"** 다.
 *
 * **씬으로 묶지 않는다.** 그 축이 이 조회가 대신하는 것이다 — 저작이 씬 단위였던 시절에는 대화로
 * `TurnBattleScene 8/29` 를 내보냈지만, 시나리오는 여러 씬을 지나는 흐름이라 그 수로는 다음에
 * 무엇을 할지 정할 수 없다. 단위는 사용자가 만든 것, 곧 시나리오다.
 *
 * @property total 프로젝트의 케이스 전량. 비교 축으로만 쓴다.
 * @property covered 이 런의 시나리오들이 담은 서로 다른 케이스 수. 시나리오끼리 겹치면 한 번만
 *   센다 — 같은 케이스가 맥락을 달리해 두 시나리오에 들어가는 것은 정상이고, 그 둘을 2로 세면
 *   이 런이 실제로 덮은 범위보다 커진다.
 * @property uncovered `total - covered`. 화면이 다시 빼지 않게 세어 준다.
 */
data class RunCoverageResponse(
    val testRunId: String,
    val total: Int,
    val covered: Int,
    val uncovered: Int,
    val scenarios: List<RunCoverageScenario>,
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
