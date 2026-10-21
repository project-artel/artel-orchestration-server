package kr.artel.orchestration.testrun.dto

import com.fasterxml.jackson.annotation.JsonProperty
import kr.artel.orchestration.testscenario.dto.ScenarioExecutionMode
import java.time.Instant

/**
 * @property gameBuildId 이 TR 이 따를 TC 버전(게임 빌드, ARTEL-1048). 주지 않으면 프로젝트의 최신 게임 빌드다.
 * @property unversioned true 면 버전 없이 만든다. [gameBuildId] 와 함께 주지 않는다.
 * @property folderId 그 폴더 안에 만든다(ARTEL-1049). 빌드 버전은 폴더의 것이다.
 */
data class TestRunCreateRequest(
    val name: String? = null,
    val description: String? = null,
    val gameBuildId: String? = null,
    val unversioned: Boolean = false,
    val folderId: String? = null,
)

/**
 * TR 을 다른 빌드 버전 · 폴더로 옮긴다(ARTEL-1048, 1049). [folderId] 를 주면 그 폴더 안으로 가고 빌드 버전도 폴더의
 * 것이 된다. 주지 않으면 [gameBuildId] 버전 바로 아래로 간다(null 이면 버전 없음).
 */
data class TestRunGameBuildRequest(
    val gameBuildId: String? = null,
    val folderId: String? = null,
)

data class TestRunUpdateRequest(
    val name: String? = null,
    val description: String? = null,
)

/**
 * TR 을 만든 게임 버전(ARTEL-994). [label] 은 사람이 붙인 이름이고, 없으면 화면이 [version] 을 쓴다.
 * [isEditor] 면 editor 버전이다(ARTEL-1180). 같은 version 의 build 행과 따로 있다.
 */
data class TestRunGameBuild(
    val gameBuildId: String,
    val version: String,
    val label: String?,
    val isEditor: Boolean = false,
)

/**
 * id 계열은 FE 64비트 정밀도 손실 방지로 문자열.
 *
 * @property gameBuild 이 TR 을 만든 게임 버전(ARTEL-994). build 가 없던 프로젝트의 TR, QA 를 한 번도 돌리지
 *   않은 옛 TR 은 null 이다.
 */
data class TestRunResponse(
    val id: String,
    val projectId: String,
    val name: String,
    val description: String?,
    val createdAt: Instant,
    val gameBuild: TestRunGameBuild? = null,
    /** 든 폴더(ARTEL-1049). null 이면 그 빌드 버전 바로 아래다. */
    val folderId: String? = null,
    /** 담은 시나리오 수(ARTEL-1048). 목록에서만 채운다. */
    val scenarioCount: Int? = null,
    /** 마지막 QA 실행(ARTEL-1048). 돌린 적이 없으면 null 이다. 목록에서만 채운다. */
    val lastQaRun: TestRunLastQa? = null,
)

/** TR 의 마지막 QA 실행 요약. [passed] · [failed] 는 그 실행의 시나리오 결과 수다. */
data class TestRunLastQa(
    val qaRunId: String,
    val status: String,
    val startedAt: Instant,
    val passed: Int,
    val failed: Int,
    val total: Int,
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
    @JsonProperty("execution_mode") val executionMode: ScenarioExecutionMode,
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
 * @property total 기준 케이스 수. 버전을 따르는 런은 그 버전의 케이스(`BROKEN` 제외), 아니면 프로젝트 전량이다
 *   (ARTEL-1069). 비교 축으로만 쓴다.
 * @property covered 이 런의 시나리오들이 담은 서로 다른 케이스 수. 시나리오끼리 겹치면 한 번만
 *   센다 — 같은 케이스가 맥락을 달리해 두 시나리오에 들어가는 것은 정상이고, 그 둘을 2로 세면
 *   이 런이 실제로 덮은 범위보다 커진다.
 * @property uncovered `total - covered`. 화면이 다시 빼지 않게 세어 준다.
 */
/**
 * 런의 케이스 중 켜진 게임의 버전에 없는 것(ARTEL-1069). QA 는 이 케이스들을 판정하지 않고 넘긴다.
 *
 * @property gameBuildId 판정이 속할 게임 빌드. 게임 버전을 모르면 null 이고 목록은 비어 있다.
 */
data class RunCasesOutsideBuildResponse(
    val testRunId: String,
    val gameBuildId: String?,
    val testCaseIds: List<String>,
)

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
