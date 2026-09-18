package kr.artel.orchestration.testrun.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.dto.RunCoverageResponse
import kr.artel.orchestration.testrun.dto.RunCoverageScenario
import kr.artel.orchestration.testrun.dto.RunDeletionPreview
import kr.artel.orchestration.testrun.dto.RunDeletionResult
import kr.artel.orchestration.testrun.dto.RunScenarioItem
import kr.artel.orchestration.testrun.dto.RunScenariosResponse
import kr.artel.orchestration.testrun.dto.TestRunCreateRequest
import kr.artel.orchestration.testrun.dto.TestRunListResponse
import kr.artel.orchestration.testrun.dto.TestRunResponse
import kr.artel.orchestration.testrun.dto.TestRunUpdateRequest
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant

/**
 * TestRun 도메인 서비스(코루틴). 여러 시나리오를 묶은 실행 세트(정의)의 CRUD + 시나리오 조합을 담당한다.
 * 접근은 프로젝트 참여로 인가(비참여자 → null/빈 목록). 조합에 넣는 시나리오는 같은 프로젝트 소속이어야 한다.
 */
@Service
class TestRunService(
    private val runRepository: TestRunRepository,
    private val runScenarioRepository: TestRunScenarioRepository,
    private val scenarioRepository: TestScenarioRepository,
    private val projectAccessService: ProjectAccessService,
    private val qaRunRepository: QaRunRepository,
    private val qaTryRepository: QaTryRepository,
    private val testCaseRepository: TestCaseRepository,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
) {
    suspend fun list(projectId: Long, userId: Long): TestRunListResponse {
        if (!projectAccessService.isMember(projectId, userId)) return TestRunListResponse(emptyList())
        val items = runRepository.findByProjectIdOrderByIdDesc(projectId).map { it.toResponse() }.toList()
        return TestRunListResponse(items)
    }

    suspend fun create(projectId: Long, userId: Long, request: TestRunCreateRequest): TestRunResponse? {
        if (!projectAccessService.isMember(projectId, userId)) return null
        val name = request.name?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("name is required")
        return runRepository.save(
            TestRunEntity(projectId = projectId, name = name, description = request.description?.ifBlank { null })
        ).toResponse()
    }

    suspend fun get(runId: Long, userId: Long): TestRunResponse? =
        accessible(runId, userId)?.toResponse()

    suspend fun update(runId: Long, userId: Long, request: TestRunUpdateRequest): TestRunResponse? {
        val existing = accessible(runId, userId) ?: return null
        val updated = existing.copy(
            name = request.name?.ifBlank { null } ?: existing.name,
            description = if (request.description == null) existing.description else request.description.ifBlank { null },
        )
        return runRepository.save(updated).toResponse()
    }

    /**
     * 런을 지우면 무엇이 같이 없어지는지 미리 센다(ARTEL-487). 접근 불가/미존재면 null.
     *
     * 묻기 위한 값이다 — 세어 보지 않고 "시나리오도 지울까요?"만 띄우면 사용자는 그것이 몇 개인지,
     * 다른 런에서도 쓰이는지 모르는 채로 되돌릴 수 없는 답을 해야 한다.
     */
    suspend fun deletionPreview(runId: Long, userId: Long): RunDeletionPreview? {
        accessible(runId, userId) ?: return null
        val removable = runScenarioRepository.findScenarioIdsOnlyInRun(runId).toList()
        val withHistory = removable.count { qaTryRepository.countByTestScenarioId(it) > 0 }
        return RunDeletionPreview(
            testRunId = runId.toString(),
            scenarioCount = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().size,
            removableScenarioCount = removable.size - withHistory,
            keptForQaHistoryCount = withHistory,
        )
    }

    /**
     * 런 삭제: 조합 행(test_run_scenario)까지 정리한 뒤 런 행 삭제(트랜잭션). 접근 불가면 no-op.
     *
     * [dropScenarios] 면 **이 런에만 담긴** 시나리오도 함께 지운다(ARTEL-487). 런만 지우면 남은
     * 시나리오가 커버리지를 계속 채워, 사용자가 보기에는 지웠는데 숫자가 그대로다. 다른 런에도 든
     * 시나리오와 QA 실행 이력이 있는 시나리오는 남긴다 — 남의 조합과 실행 기록은 이 삭제가 건드릴
     * 것이 아니다(시나리오 단건 삭제의 `force` 와 같은 규율).
     *
     * QA 를 돌린 적 있는 런은 **거절한다**. `qa_run.test_run_id` 가 cascade 없는 외래키라 그냥
     * 지우면 DB 가 막아 500 이 되는데, 그때 사용자는 무엇이 막았는지 알 길이 없다.
     */
    suspend fun delete(runId: Long, userId: Long, dropScenarios: Boolean = false): RunDeletionResult {
        val run = accessible(runId, userId) ?: return RunDeletionResult(0, 0)
        val qaRuns = qaRunRepository.countByTestRunId(runId)
        if (qaRuns > 0) {
            throw ConflictException(
                message = "이 런에는 QA 실행 이력이 ${qaRuns}건 있어 삭제할 수 없습니다. " +
                    "실행 기록을 먼저 정리해 주세요.",
                code = "run_has_qa_history",
            )
        }
        val removable = if (!dropScenarios) emptyList()
        else runScenarioRepository.findScenarioIdsOnlyInRun(runId).toList()
        var deleted = 0
        var kept = 0
        transactionalOperator.executeAndAwait {
            for (scenarioId in removable) {
                if (qaTryRepository.countByTestScenarioId(scenarioId) > 0) {
                    kept++
                    continue
                }
                runScenarioRepository.deleteByTestScenarioId(scenarioId)
                scenarioRepository.deleteById(scenarioId)
                deleted++
            }
            runScenarioRepository.deleteByTestRunId(runId)
            runRepository.delete(run)
        }
        return RunDeletionResult(deletedScenarioCount = deleted, keptForQaHistoryCount = kept)
    }

    suspend fun getScenarios(runId: Long, userId: Long): RunScenariosResponse? {
        accessible(runId, userId) ?: return null
        return resolveScenarios(runId)
    }

    suspend fun setScenarios(runId: Long, userId: Long, scenarioIds: List<Long>): RunScenariosResponse? {
        val run = accessible(runId, userId) ?: return null
        validateScenarios(run.projectId, scenarioIds)
        transactionalOperator.executeAndAwait {
            runScenarioRepository.deleteByTestRunId(runId)
            val rows = scenarioIds.mapIndexed { index, scenarioId ->
                TestRunScenarioEntity(testRunId = runId, testScenarioId = scenarioId, position = index)
            }
            runScenarioRepository.saveAll(rows).toList()
        }
        return resolveScenarios(runId)
    }

    private suspend fun validateScenarios(projectId: Long, scenarioIds: List<Long>) {
        if (scenarioIds.isEmpty()) return
        val distinct = scenarioIds.toSet()
        val scenarios = scenarioRepository.findAllById(distinct).toList()
        when {
            scenarios.size != distinct.size ->
                throw BadRequestException("some scenarios were not found")
            scenarios.any { it.projectId != projectId } ->
                throw BadRequestException("a scenario belongs to another project")
        }
    }

    /**
     * **이 런의 시나리오들이 무엇을 담았는가**(ARTEL-903).
     *
     * 저작 중에 대화로 내보내던 씬별 집계(`TurnBattleScene 8/29`)를 대신한다. 그 축을 걷어낸
     * 이유는 [kr.artel.orchestration.testrun.dto.RunCoverageResponse] 에 적었다 — 씬은 저작의
     * 단위가 아니고, 사용자가 만든 단위는 시나리오다.
     *
     * 케이스 수는 저장된 스텝의 `case_id` 에서 매번 센다. 커버 집합을 따로 저장하지 않는 것은
     * 커버리지 조회 전체의 규칙과 같다(`TestCaseRepository.findUncoveredIdsByProjectId`) — 두
     * 벌을 두면 갈라지는 날이 오고, 그때 어느 쪽이 맞는지 알 방법이 없다.
     */
    suspend fun coverage(runId: Long, userId: Long): RunCoverageResponse? {
        val run = accessible(runId, userId) ?: return null
        val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        val rows = links.mapNotNull { link ->
            val scenario = scenarioRepository.findById(link.testScenarioId) ?: return@mapNotNull null
            val draft = scenario.toDraft(objectMapper)
            val cases = draft.steps.mapNotNull { it.caseId }.toSet()
            RunCoverageScenario(
                position = link.position,
                testScenarioId = link.testScenarioId.toString(),
                title = draft.title,
                steps = draft.steps.size,
                cases = cases.size,
            ) to cases
        }
        // 런 합계는 시나리오별 수의 덧셈이 아니다 — 같은 케이스가 두 시나리오에 들어가는 것은
        // 정상이고(맥락이 다르면 다른 검증이다), 그것을 2로 세면 덮은 범위보다 커진다.
        val covered = rows.flatMap { it.second }.toSet().size
        val total = testCaseRepository.countByProjectId(run.projectId).toInt()
        return RunCoverageResponse(
            testRunId = runId.toString(),
            total = total,
            covered = covered,
            uncovered = (total - covered).coerceAtLeast(0),
            scenarios = rows.map { it.first },
        )
    }

    private suspend fun resolveScenarios(runId: Long): RunScenariosResponse {
        val items = runScenarioRepository.findByTestRunIdOrderByPosition(runId)
            .map { RunScenarioItem(it.position, it.testScenarioId.toString()) }
            .toList()
        return RunScenariosResponse(runId.toString(), items)
    }

    private suspend fun accessible(runId: Long, userId: Long): TestRunEntity? {
        val run = runRepository.findById(runId) ?: return null
        return if (projectAccessService.isMember(run.projectId, userId)) run else null
    }

    private fun TestRunEntity.toResponse(): TestRunResponse =
        TestRunResponse(
            id = requireNotNull(id).toString(),
            projectId = projectId.toString(),
            name = name,
            description = description,
            createdAt = createdAt ?: Instant.EPOCH,
        )
}
