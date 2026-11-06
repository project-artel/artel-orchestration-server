package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.CaseReviewItem
import kr.artel.orchestration.testcase.dto.ReviewDecision
import kr.artel.orchestration.testcase.dto.ReviewDecisionRequest
import kr.artel.orchestration.testcase.entity.ReviewStatus
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.service.CaseRecommendationService
import org.springframework.stereotype.Service

/**
 * MCP 로 만든 Test Case 의 승인 대기와 결정.
 *
 * 목록은 요청한 사람의 것만 준다. 결정은 프로젝트 멤버면 누구나 할 수 있다. 요청한 사람이 없어도 대기 건이 남지 않게 한다.
 * 결정은 사람이 읽은 내용 그대로일 때만 받는다. 승인과 거절이 겹치거나 에이전트가 그 사이 고쳐도 안 읽은 내용이 승인되지 않는다.
 */
@Service
class CaseReviewService(
    private val projectAccess: ProjectAccessService,
    private val testCases: TestCaseRepository,
    private val projectChanges: ProjectChangeStreamManager,
    private val recommendations: CaseRecommendationService,
) {
    suspend fun queue(projectId: Long, userId: Long): List<CaseReviewItem> {
        if (!projectAccess.isMember(projectId, userId)) throw NotFoundException()
        return testCases
            .findByProjectIdAndReviewStatusAndReviewRequestedByOrderByIdAsc(projectId, ReviewStatus.DRAFT.name, userId)
            .map { case ->
                CaseReviewItem(
                    id = case.id.toString(),
                    scene = case.scene,
                    step = case.step,
                    precondition = case.precondition,
                    expectedValue = case.expectedValue,
                    kind = case.kind,
                    updatedAt = requireNotNull(case.updatedAt),
                )
            }
            .toList()
    }

    suspend fun decide(projectId: Long, caseId: Long, userId: Long, request: ReviewDecisionRequest) {
        if (!projectAccess.isMember(projectId, userId)) throw NotFoundException()
        val changed = when (request.decision) {
            ReviewDecision.APPROVE -> testCases.approveDraft(caseId, projectId, request.expectedUpdatedAt)
            ReviewDecision.REJECT -> testCases.rejectDraft(caseId, projectId, request.expectedUpdatedAt)
        }
        if (changed == 0) throw refusal(projectId, caseId, request)
        if (request.decision == ReviewDecision.APPROVE) recommendations.linkApproved(caseId, userId)
        projectChanges.notify(projectId, ProjectChangeTopic.TEST_CASES)
    }

    private suspend fun refusal(projectId: Long, caseId: Long, request: ReviewDecisionRequest): Exception {
        val current = testCases.findById(caseId)?.takeIf { it.projectId == projectId } ?: return NotFoundException()
        return when {
            current.reviewStatus != ReviewStatus.DRAFT.name ->
                ConflictException("이미 처리된 Test Case 입니다.", REVIEW_SETTLED)
            current.updatedAt != request.expectedUpdatedAt ->
                ConflictException("내용이 바뀌었습니다. 다시 확인해 주세요.", REVIEW_STALE)
            else -> ConflictException("승인을 요청받은 것이 아니거나 시나리오가 쓰고 있어 거절할 수 없습니다.", REVIEW_NOT_REJECTABLE)
        }
    }

    companion object {
        const val REVIEW_SETTLED = "review_settled"
        const val REVIEW_STALE = "review_stale"
        const val REVIEW_NOT_REJECTABLE = "review_not_rejectable"
    }
}
