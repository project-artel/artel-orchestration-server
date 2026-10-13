package kr.artel.orchestration.testrun.dto

import java.time.Instant

/**
 * TS 불러오기 모달의 트리(ARTEL-994). 게임 버전 → TR → TS 순서로 묶는다. id 는 모두 문자열이다.
 *
 * @property versions 최근 build 부터. 버전이 없는 TR 과 어느 TR 에도 없는 TS 는 `gameBuild = null` 인 마지막
 *   묶음에 들어간다. TR 이 하나도 없는 버전은 넣지 않는다.
 */
data class ScenarioLibraryResponse(
    val versions: List<ScenarioLibraryVersion>,
)

data class ScenarioLibraryVersion(
    val gameBuild: TestRunGameBuild?,
    val testRuns: List<ScenarioLibraryRun>,
)

/**
 * @property testRunId null 이면 어느 TR 에도 없는 TS 묶음이다. 그때 [name] 도 null 이다.
 */
data class ScenarioLibraryRun(
    val testRunId: String?,
    val name: String?,
    val testScenarios: List<ScenarioLibraryScenario>,
)

/** @property caseCount 스텝이 검증하는 서로 다른 TC 수. 같은 TC 의 검증 구간은 한 번만 센다. */
data class ScenarioLibraryScenario(
    val testScenarioId: String,
    val title: String,
    val stepCount: Int,
    val caseCount: Int,
    val updatedAt: Instant?,
)

/** `POST /api/projects/{projectId}/test-runs/{runId}/scenarios/imports`. 순서대로 TR 끝에 붙는다. */
data class ScenarioImportRequest(
    val testScenarioIds: List<String?>? = null,
)

/**
 * 가져오기 결과(ARTEL-994).
 *
 * @property importedScenarios 새로 만든 복사본. 요청 순서와 같다.
 * @property unmatchedCaseIds 이 TR 의 build 에 짝이 없어 바꿔 끼우지 못한 `case_id`. 원래 id 그대로 남는다 —
 *   그 케이스가 이 버전에서 사라졌다는 뜻이라 사용자가 알아야 한다. TR 에 build 가 없으면 바꿔 끼우지 않으므로
 *   비어 있다.
 */
data class ScenarioImportResponse(
    val testRunId: String,
    val importedScenarios: List<ImportedScenario>,
    val unmatchedCaseIds: List<String>,
)

data class ImportedScenario(
    val sourceTestScenarioId: String,
    val testScenarioId: String,
    val title: String,
)
