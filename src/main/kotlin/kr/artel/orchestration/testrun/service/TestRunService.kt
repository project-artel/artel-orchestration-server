package kr.artel.orchestration.testrun.service

import kr.artel.orchestration.common.error.BadRequestException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.project.service.ProjectAccessService
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

    /** 런 삭제: 조합 행(test_run_scenario)까지 정리한 뒤 런 행 삭제(트랜잭션). 접근 불가면 no-op. */
    suspend fun delete(runId: Long, userId: Long) {
        val run = accessible(runId, userId) ?: return
        transactionalOperator.executeAndAwait {
            runScenarioRepository.deleteByTestRunId(runId)
            runRepository.delete(run)
        }
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
