package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testcase.dto.ReviewDecision
import kr.artel.orchestration.testcase.dto.ReviewDecisionRequest
import kr.artel.orchestration.testcase.service.CaseReviewService
import kr.artel.orchestration.testcase.service.CaseScope
import kr.artel.orchestration.testcase.service.TestCaseReader
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testrun.service.RunAuthoringLocks
import kr.artel.orchestration.testscenario.dto.ScenarioReviewItem
import kr.artel.orchestration.testscenario.dto.ScenarioReviewRun
import kr.artel.orchestration.testscenario.dto.ScenarioReviewStep
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * MCP 로 만든 시나리오의 승인 대기와 결정.
 *
 * 목록은 요청한 사람의 것만 준다. 결정은 프로젝트 멤버면 누구나 할 수 있고, 사람이 읽은 내용 그대로일 때만 받는다.
 * 승인된 시나리오를 고친 것은 이전 본문이 남지 않아 거절(삭제)하지 못한다.
 */
@Service
class ScenarioReviewService(
    private val accessService: TestScenarioAccessService,
    private val scenarios: TestScenarioRepository,
    private val runLinks: TestRunScenarioRepository,
    private val runs: TestRunRepository,
    private val testCases: TestCaseReader,
    private val objectMapper: ObjectMapper,
    private val scenarioService: TestScenarioService,
    private val runLocks: RunAuthoringLocks,
    private val qaTries: QaTryRepository,
    private val transactionalOperator: TransactionalOperator,
    private val projectChanges: ProjectChangeStreamManager,
    private val absorbService: ScenarioAbsorbService,
) {
    suspend fun queue(projectId: Long, appUserId: Long): List<ScenarioReviewItem> {
        if (!accessService.isMember(projectId, appUserId)) throw NotFoundException()
        return scenarios.findByProjectIdAndReviewStatusAndReviewRequestedByOrderByIdAsc(projectId, "DRAFT", appUserId).toList().map { scenario ->
            val inRuns = runLinks.findByTestScenarioId(requireNotNull(scenario.id)).toList()
                .mapNotNull { runs.findById(it.testRunId) }
            val draft = scenario.toDraft(objectMapper)
            // 런이 고른 버전의 Test Case 내용으로 보여 준다. QA 가 그 내용으로 돌기 때문이다.
            val cases = testCases.byIds(
                draft.steps.mapNotNull { it.caseId },
                CaseScope.of(inRuns.firstOrNull()?.gameBuildId),
            )
            ScenarioReviewItem(
                testScenarioId = scenario.id.toString(),
                title = draft.title,
                description = draft.description,
                executionMode = scenario.executionMode,
                runs = inRuns.map { ScenarioReviewRun(it.id.toString(), it.name) },
                steps = draft.steps.map { step ->
                    val case = step.caseId?.let { cases[it] }
                    ScenarioReviewStep(
                        action = step.action,
                        testCaseId = step.caseId?.toString(),
                        scene = case?.scene,
                        precondition = case?.precondition,
                        expectedValue = case?.expectedValue,
                    )
                },
                updatedAt = requireNotNull(scenario.updatedAt),
                rejectable = !scenario.reviewEditsApproved && scenario.reviewRequestedBy != null,
            )
        }
    }

    /** 승인하면 걷어낼 시나리오를 적어 둔다. */
    suspend fun record(scenarioId: Long, absorbedIds: List<Long>) {
        val scenario = scenarios.findById(scenarioId) ?: return
        scenarios.save(scenario.copy(reviewAbsorbs = Json.of(objectMapper.writeValueAsString(absorbedIds))))
    }

    suspend fun decide(projectId: Long, scenarioId: Long, appUserId: Long, request: ReviewDecisionRequest) {
        if (!accessService.isMember(projectId, appUserId)) throw NotFoundException()
        val scenario = scenarios.findById(scenarioId)?.takeIf { it.projectId == projectId } ?: throw NotFoundException()
        runLocks.assertScenarioWritable(scenarioId)
        val changed = when (request.decision) {
            ReviewDecision.APPROVE -> {
                if (scenario.reviewStatus == "DRAFT") {
                    scenarioService.validateReviewedCases(projectId, scenario.toDraft(objectMapper))
                }
                scenarios.approveDraft(scenarioId, projectId, request.expectedUpdatedAt)
            }
            ReviewDecision.REJECT -> reject(scenarioId, projectId, request)
        }
        if (changed == 0) throw refusal(projectId, scenarioId, request)
        if (request.decision == ReviewDecision.APPROVE) absorbService.absorbOnApproval(scenario)
        projectChanges.notify(projectId, ProjectChangeTopic.TEST_RUNS)
    }

    private suspend fun reject(scenarioId: Long, projectId: Long, request: ReviewDecisionRequest): Int {
        if (qaTries.countByTestScenarioId(scenarioId) > 0) {
            throw ConflictException("QA 실행 이력이 있어 거절할 수 없습니다.", "scenario_has_qa_history")
        }
        // 지우지 못했으면 런 연결도 그대로 둔다.
        return try {
            transactionalOperator.executeAndAwait {
                val deleted = scenarios.rejectDraft(scenarioId, projectId, request.expectedUpdatedAt)
                if (deleted == 0) throw NothingRejected()
                runLinks.deleteByTestScenarioId(scenarioId)
                deleted
            }
        } catch (_: NothingRejected) {
            0
        }
    }

    private suspend fun refusal(projectId: Long, scenarioId: Long, request: ReviewDecisionRequest): Exception {
        val current = scenarios.findById(scenarioId)?.takeIf { it.projectId == projectId } ?: return NotFoundException()
        return when {
            current.reviewStatus != "DRAFT" -> ConflictException("이미 처리된 시나리오입니다.", CaseReviewService.REVIEW_SETTLED)
            current.updatedAt != request.expectedUpdatedAt ->
                ConflictException("내용이 바뀌었습니다. 다시 확인해 주세요.", CaseReviewService.REVIEW_STALE)
            else -> ConflictException("승인된 시나리오를 고친 것은 거절할 수 없습니다.", CaseReviewService.REVIEW_NOT_REJECTABLE)
        }
    }

    private class NothingRejected : RuntimeException()
}
