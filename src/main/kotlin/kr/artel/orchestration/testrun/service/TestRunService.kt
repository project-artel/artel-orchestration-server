package kr.artel.orchestration.testrun.service

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.project.repository.ProjectMemberRepository
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
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * TestRun 도메인 서비스(코루틴). 여러 시나리오를 묶은 실행 세트(정의)의 CRUD + 시나리오 조합을 담당한다.
 * 접근은 프로젝트 참여로 인가(비참여자 → null/빈 목록). 조합에 넣는 시나리오는 같은 프로젝트 소속이어야 한다.
 *
 * 참고: `scenarioRepository`는 아직 Reactor(Mono/Flux) 리포라 `awaitSingle*()`로 브리지한다(이번 스코프 밖).
 */
@Service
class TestRunService(
    private val runRepository: TestRunRepository,
    private val runScenarioRepository: TestRunScenarioRepository,
    private val scenarioRepository: TestScenarioRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val transactionalOperator: TransactionalOperator,
) {
    private suspend fun isMember(projectId: Long, userId: Long): Boolean =
        projectMemberRepository.findByProjectIdAndAppUserId(projectId, userId).awaitSingleOrNull() != null

    suspend fun list(projectId: Long, userId: Long): TestRunListResponse {
        if (!isMember(projectId, userId)) return TestRunListResponse(emptyList())
        val items = runRepository.findByProjectIdOrderByIdDesc(projectId).map { it.toResponse() }.toList()
        return TestRunListResponse(items)
    }

    suspend fun create(projectId: Long, userId: Long, request: TestRunCreateRequest): TestRunResponse? {
        if (!isMember(projectId, userId)) return null
        val name = request.name?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required")
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
        // scenarioRepository는 아직 Reactor 리포 → Flux.collectList()로 브리지.
        val scenarios = scenarioRepository.findAllById(distinct).collectList().awaitSingle()
        when {
            scenarios.size != distinct.size ->
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "some scenarios were not found")
            scenarios.any { it.projectId != projectId } ->
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a scenario belongs to another project")
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
        return if (isMember(run.projectId, userId)) run else null
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
