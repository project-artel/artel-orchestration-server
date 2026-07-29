package kr.artel.orchestration.testrun.service

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
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * TestRun 도메인 서비스. 여러 시나리오를 묶은 실행 세트(정의)의 CRUD + 시나리오 조합을 담당한다.
 * 접근은 프로젝트 참여로 인가. 조합에 넣는 시나리오는 같은 프로젝트 소속이어야 한다.
 * (실제 QA 실행 인스턴스(qa_try)와의 배선은 이 단계 범위 밖.)
 */
@Service
class TestRunService(
    private val runRepository: TestRunRepository,
    private val runScenarioRepository: TestRunScenarioRepository,
    private val scenarioRepository: TestScenarioRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val transactionalOperator: TransactionalOperator,
) {
    private fun isMember(projectId: Long, userId: Long): Mono<Boolean> =
        projectMemberRepository.findByProjectIdAndAppUserId(projectId, userId).hasElement()

    fun list(projectId: Long, userId: Long): Mono<TestRunListResponse> =
        isMember(projectId, userId).flatMap { member ->
            if (!member) return@flatMap Mono.just(TestRunListResponse(emptyList()))
            runRepository.findByProjectIdOrderByIdDesc(projectId)
                .map { it.toResponse() }
                .collectList()
                .map { TestRunListResponse(it) }
        }

    fun create(projectId: Long, userId: Long, request: TestRunCreateRequest): Mono<TestRunResponse> =
        isMember(projectId, userId).flatMap { member ->
            if (!member) return@flatMap Mono.empty()
            val name = request.name?.takeIf { it.isNotBlank() }
                ?: return@flatMap Mono.error(ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required"))
            runRepository.save(
                TestRunEntity(projectId = projectId, name = name, description = request.description?.ifBlank { null })
            ).map { it.toResponse() }
        }

    fun get(runId: Long, userId: Long): Mono<TestRunResponse> =
        accessible(runId, userId).map { it.toResponse() }

    fun update(runId: Long, userId: Long, request: TestRunUpdateRequest): Mono<TestRunResponse> =
        accessible(runId, userId).flatMap { existing ->
            val updated = existing.copy(
                name = request.name?.ifBlank { null } ?: existing.name,
                description = if (request.description == null) existing.description else request.description.ifBlank { null },
            )
            runRepository.save(updated).map { it.toResponse() }
        }

    /** 런 삭제: 조합 행(test_run_scenario)까지 정리한 뒤 런 행 삭제. */
    fun delete(runId: Long, userId: Long): Mono<Void> =
        accessible(runId, userId).flatMap { run ->
            runScenarioRepository.deleteByTestRunId(runId)
                .then(runRepository.delete(run))
                .`as`(transactionalOperator::transactional)
                .then()
        }

    fun getScenarios(runId: Long, userId: Long): Mono<RunScenariosResponse> =
        accessible(runId, userId).flatMap { resolveScenarios(runId) }

    fun setScenarios(runId: Long, userId: Long, scenarioIds: List<Long>): Mono<RunScenariosResponse> =
        accessible(runId, userId).flatMap { run ->
            validateScenarios(run.projectId, scenarioIds)
                .then(replaceScenarios(runId, scenarioIds).`as`(transactionalOperator::transactional).then())
                .then(resolveScenarios(runId))
        }

    private fun replaceScenarios(runId: Long, scenarioIds: List<Long>): Mono<Void> {
        val rows = scenarioIds.mapIndexed { index, scenarioId ->
            TestRunScenarioEntity(testRunId = runId, testScenarioId = scenarioId, position = index)
        }
        return runScenarioRepository.deleteByTestRunId(runId)
            .thenMany(runScenarioRepository.saveAll(rows))
            .then()
    }

    private fun validateScenarios(projectId: Long, scenarioIds: List<Long>): Mono<Void> {
        if (scenarioIds.isEmpty()) return Mono.empty()
        val distinct = scenarioIds.toSet()
        return scenarioRepository.findAllById(distinct).collectList().flatMap { scenarios ->
            when {
                scenarios.size != distinct.size ->
                    Mono.error(ResponseStatusException(HttpStatus.BAD_REQUEST, "some scenarios were not found"))
                scenarios.any { it.projectId != projectId } ->
                    Mono.error(ResponseStatusException(HttpStatus.BAD_REQUEST, "a scenario belongs to another project"))
                else -> Mono.empty()
            }
        }
    }

    private fun resolveScenarios(runId: Long): Mono<RunScenariosResponse> =
        runScenarioRepository.findByTestRunIdOrderByPosition(runId)
            .map { RunScenarioItem(it.position, it.testScenarioId.toString()) }
            .collectList()
            .map { RunScenariosResponse(runId.toString(), it) }

    private fun accessible(runId: Long, userId: Long): Mono<TestRunEntity> =
        runRepository.findById(runId).filterWhen { isMember(it.projectId, userId) }

    private fun TestRunEntity.toResponse(): TestRunResponse =
        TestRunResponse(
            id = requireNotNull(id).toString(),
            projectId = projectId.toString(),
            name = name,
            description = description,
            createdAt = createdAt ?: Instant.EPOCH,
        )
}
