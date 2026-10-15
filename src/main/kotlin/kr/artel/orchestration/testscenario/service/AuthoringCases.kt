package kr.artel.orchestration.testscenario.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testcase.dto.CapabilityCase
import kr.artel.orchestration.testcase.dto.CaseInputRow
import kr.artel.orchestration.testcase.dto.CaseWrite
import kr.artel.orchestration.testcase.dto.GroundedSceneEdgeRow
import kr.artel.orchestration.testcase.dto.SceneExitRow
import kr.artel.orchestration.testcase.dto.StartingValue
import kr.artel.orchestration.testcase.dto.ValueMoveRow
import kr.artel.orchestration.testcase.dto.ValueRaiser
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.CaseScope
import kr.artel.orchestration.testcase.service.TestCaseReader
import org.springframework.stereotype.Service

/**
 * 시나리오 작성이 TC 와 지도 사실을 읽는 **유일한 자리**(ARTEL-1064).
 *
 * 맥락([AuthoringVersion])에 빌드가 있으면, 즉 TR 이 빌드 버전을 따르면:
 * - 케이스는 그 버전으로 비춘다([TestCaseReader] — 다른 화면 · QA 와 같은 규칙이다). 대표 행(`test_case`)과 같은
 *   모양이므로 판독기(`CaseConditionReader` · `ScenarioStateReader`)와 검수 로직은 고칠 것이 없다. id 는 대표 id 다.
 * - 지도 사실(값을 올리는 자리 · 움직이는 값 · 화면 출구 · 입력 · 시작값 · 입구 씬)은 그 빌드 지도 하나에서 읽는다.
 *
 * 맥락이 없거나 버전 없는 TR 이면 예전과 같다 — 대표 행과 프로젝트판 질의다.
 *
 * 그 버전에 몫이 없는 케이스(버전 없는 사용자 TC · 다른 버전에만 있는 TC)는 대표 행 그대로 둔다. 저작에 **보내는**
 * 목록은 그 버전에 든 것만으로 따로 거른다. 여기서까지 빼면 그런 케이스를 가리키는 옛 시나리오의 사실이 사라진다.
 */
@Service
class AuthoringCases(
    private val testCases: TestCaseRepository,
    private val reader: TestCaseReader,
) {
    /** 프로젝트의 케이스 전부, id 오름차순. 버전이 있으면 그 버전 몫이 있는 케이스는 그 버전으로 비춘다. */
    suspend fun all(projectId: Long): List<TestCaseEntity> =
        reader.overlay(testCases.findByProjectIdOrderByIdAsc(projectId).toList(), scope())

    /** 케이스 하나. 버전이 있고 그 버전 몫이 있으면 그 버전으로 비춘다. */
    suspend fun byId(testCaseId: Long): TestCaseEntity? =
        testCases.findById(testCaseId)?.let { reader.overlay(it, scope()) }

    /** [byId] 의 여러 건판. 없는 id 는 빠진다. */
    suspend fun byIds(ids: Collection<Long>): List<TestCaseEntity> =
        if (ids.isEmpty()) emptyList() else reader.overlay(testCases.findAllById(ids).toList(), scope())

    // ── 지도 사실: 빌드가 있으면 그 빌드 지도 하나, 없으면 프로젝트판 ─────────────────────────────

    suspend fun valuesChangedByCases(projectId: Long): Flow<CaseWrite> =
        authoringGameBuildId()?.let(testCases::findValuesChangedByCasesOfBuild) ?: testCases.findValuesChangedByCases(projectId)

    suspend fun sceneExits(projectId: Long): Flow<SceneExitRow> =
        authoringGameBuildId()?.let(testCases::findSceneExitsOfBuild) ?: testCases.findSceneExits(projectId)

    suspend fun groundedSceneEdges(projectId: Long): Flow<GroundedSceneEdgeRow> =
        authoringGameBuildId()?.let(testCases::findGroundedSceneEdgesOfBuild) ?: testCases.findGroundedSceneEdges(projectId)

    suspend fun caseInputs(projectId: Long): Flow<CaseInputRow> =
        authoringGameBuildId()?.let(testCases::findCaseInputsOfBuild) ?: testCases.findCaseInputs(projectId)

    suspend fun writtenValues(projectId: Long): Flow<String> =
        authoringGameBuildId()?.let(testCases::findWrittenValuesOfBuild) ?: testCases.findWrittenValues(projectId)

    suspend fun strayWrittenValues(projectId: Long): Flow<String> =
        authoringGameBuildId()?.let(testCases::findStrayWrittenValuesOfBuild) ?: testCases.findStrayWrittenValues(projectId)

    suspend fun valueRaisers(projectId: Long): Flow<ValueRaiser> =
        authoringGameBuildId()?.let(testCases::findValueRaisersOfBuild) ?: testCases.findValueRaisers(projectId)

    suspend fun valueMoves(projectId: Long): Flow<ValueMoveRow> =
        authoringGameBuildId()?.let(testCases::findValueMovesOfBuild) ?: testCases.findValueMoves(projectId)

    suspend fun caseIdByCapability(projectId: Long): Flow<CapabilityCase> =
        authoringGameBuildId()?.let(testCases::findCaseIdByCapabilityOfBuild) ?: testCases.findCaseIdByCapability(projectId)

    suspend fun startingValues(projectId: Long): Flow<StartingValue> =
        authoringGameBuildId()?.let(testCases::findStartingValuesOfBuild) ?: testCases.findStartingValues(projectId)

    suspend fun entrySceneName(projectId: Long): String? =
        authoringGameBuildId()?.let { testCases.findEntrySceneNameOfBuild(it) } ?: testCases.findEntrySceneName(projectId)

    private suspend fun scope(): CaseScope = CaseScope.of(authoringGameBuildId())
}
