package kr.artel.orchestration.qa.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.qa.dto.QaNotice
import kr.artel.orchestration.qa.dto.QaNoticeListResponse
import kr.artel.orchestration.qa.dto.QaRunSummaryListResponse
import kr.artel.orchestration.qa.dto.QaRunSummaryResponse
import kr.artel.orchestration.qa.dto.SinceLastQaChange
import kr.artel.orchestration.qa.dto.SinceLastQaIssue
import kr.artel.orchestration.qa.dto.SinceLastQaResponse
import kr.artel.orchestration.qa.repository.QaLaunchReadRepository
import kr.artel.orchestration.qa.repository.QaRunSummaryRow
import kr.artel.orchestration.testrun.repository.TestRunRepository
import org.springframework.stereotype.Service

/**
 * QA 실행 화면이 읽는 값(ARTEL-1083) — 프로젝트의 실행 목록과, 테스트 런의 "지난 실행 이후".
 *
 * 둘 다 읽기만 한다. 접근은 프로젝트 참여로 가르고, 아니면 404 다 — 빈 목록으로 위장하지 않는다
 * (`IssueService.listByProject` 와 같은 규칙).
 */
@Service
class QaLaunchReadService(
    private val repository: QaLaunchReadRepository,
    private val projectAccess: ProjectAccessService,
    private val testRunRepository: TestRunRepository,
) {

    /**
     * 프로젝트의 실행을 최신순으로 [limit] 개. [testRunId] 를 주면 그 테스트 런의 실행만 고른다.
     *
     * 히스토리 화면은 테스트 런마다 따로 읽는다(ARTEL-1100). 실행은 계속 쌓이므로 프로젝트 전체의
     * 최신 몇 개로는 오래된 런의 실행이 잘려 나간다. 다른 프로젝트의 런을 지목하면 비어 있다 —
     * 실행을 프로젝트로 거르는 조건이 쿼리에 있다.
     */
    suspend fun listRuns(projectId: Long, appUserId: Long, limit: Int, testRunId: Long? = null): QaRunSummaryListResponse {
        if (limit !in 1..MAX_LIMIT) throw BadRequestException("limit 은 1~$MAX_LIMIT 이어야 합니다.")
        projectAccess.requireMember(projectId, appUserId)
        return QaRunSummaryListResponse(repository.findRuns(projectId, testRunId, limit).map { it.toResponse() })
    }

    /**
     * 테스트 런의 마지막 실행과, 그 실행에서 나온 이슈, 그 뒤로 바뀐 것.
     *
     * "뒤로" 의 기준은 마지막 실행의 **시작** 시각이다. 실행 중에 고친 시나리오도 다음 실행에서는
     * 새것이고, 끝 시각을 쓰면 그 사이의 수정이 빠진다.
     */
    suspend fun sinceLastQa(projectId: Long, testRunId: Long, appUserId: Long): SinceLastQaResponse {
        projectAccess.requireMember(projectId, appUserId)
        val run = testRunRepository.findById(testRunId)
        if (run == null || run.projectId != projectId) throw NotFoundException()

        val last = repository.findRuns(projectId, testRunId, 1).firstOrNull()
            ?: return SinceLastQaResponse(lastQaRun = null, issues = emptyList(), changes = emptyList())
        val issues = repository.findIssuesOfRun(last.id).map {
            SinceLastQaIssue(
                id = it.id.toString(),
                title = it.title,
                severity = it.severity,
                status = it.status,
                qaTryId = it.qaTryId.toString(),
                scenarioId = it.scenarioId.toString(),
                scenarioTitle = it.scenarioTitle,
                step = it.step,
                reportedAt = it.reportedAt,
            )
        }
        val changes = repository.findChangesSince(projectId, testRunId, last.startedAt).map {
            SinceLastQaChange(
                kind = it.kind,
                at = it.at,
                subjectId = it.subjectId?.toString(),
                subjectName = it.subjectName,
                count = it.count,
            )
        }
        return SinceLastQaResponse(lastQaRun = last.toResponse(), issues = issues, changes = changes)
    }

    /** 상단바 알림함(ARTEL-1254): 내가 시작해 끝난 실행 최신 [limit] 개와 읽지 않은 수. */
    suspend fun listNotices(appUserId: Long, limit: Int): QaNoticeListResponse {
        if (limit !in 1..MAX_LIMIT) throw BadRequestException("limit 은 1~$MAX_LIMIT 이어야 합니다.")
        val items = repository.findNotices(appUserId, limit).map {
            QaNotice(run = it.run.toResponse(), projectId = it.projectId.toString(), projectName = it.projectName, read = it.read)
        }
        return QaNoticeListResponse(items, repository.countUnreadNotices(appUserId))
    }

    /** 알림 하나를 읽음으로. 내가 시작한 실행이 아니면 404 다. */
    suspend fun readNotice(appUserId: Long, qaRunId: Long): QaNoticeListResponse {
        if (repository.markNoticeRead(appUserId, qaRunId) == 0L && !repository.isNoticeOf(appUserId, qaRunId)) {
            throw NotFoundException()
        }
        return listNotices(appUserId, NOTICE_LIMIT)
    }

    /** 내 알림 전부를 읽음으로. */
    suspend fun readAllNotices(appUserId: Long): QaNoticeListResponse {
        repository.markAllNoticesRead(appUserId)
        return listNotices(appUserId, NOTICE_LIMIT)
    }

    private fun QaRunSummaryRow.toResponse() = QaRunSummaryResponse(
        id = id.toString(),
        testRunId = testRunId.toString(),
        testRunName = testRunName,
        gameInstanceId = gameInstanceId.toString(),
        gameInstanceName = gameInstanceName,
        gameBuildVersion = gameBuildVersion,
        model = model,
        reasoningEffort = reasoningEffort,
        keySource = keySource,
        status = status,
        startedAt = startedAt,
        completedAt = completedAt,
        casesPassed = casesPassed,
        casesTotal = casesTotal,
        casesFailed = casesFailed,
        issueCount = issueCount,
        startedBy = startedBy.toString(),
        reviewFlagged = reviewFlagged,
        reviewPending = reviewPending,
    )

    companion object {
        const val MAX_LIMIT = 50
        /** 알림함이 한 번에 보이는 수. 읽음으로 둔 뒤 돌려주는 목록도 이만큼이다. */
        const val NOTICE_LIMIT = 10
    }
}
