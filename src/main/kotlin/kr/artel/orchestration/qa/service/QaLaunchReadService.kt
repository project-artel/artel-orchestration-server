package kr.artel.orchestration.qa.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.qa.dto.QaRunListItem
import kr.artel.orchestration.qa.dto.QaRunListResponse
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
    private val qaTryService: QaTryService,
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
     * `GET /api/projects/{projectId}/qa-runs` 의 응답(ARTEL-1161) — [listRuns] 의 요약 줄에 run 개요(`tries` 등)를 붙인다.
     *
     * 이 경로는 QA 실행 · 히스토리(ARTEL-1083, `limit` · `testRunId`)와 빌드 상세(ARTEL-1104, `size` · `gameBuildId`)가
     * 같이 쓴다. 상한은 부르는 쪽이 자기 파라미터 규칙으로 확인한다 — `limit` 은 [MAX_LIMIT], `size` 는 [MAX_SIZE].
     */
    suspend fun listProjectRuns(
        projectId: Long,
        appUserId: Long,
        count: Int,
        testRunId: Long?,
        gameBuildId: Long?,
    ): QaRunListResponse {
        require(count in 1..MAX_SIZE) { "count 는 1~$MAX_SIZE 이어야 한다: $count" }
        projectAccess.requireMember(projectId, appUserId)
        val summaries = repository.findRuns(projectId, testRunId, count, gameBuildId).map { it.toResponse() }
        val runs = qaTryService.runResponses(summaries.map { it.id.toLong() })
        return QaRunListResponse(summaries.mapNotNull { summary -> runs[summary.id.toLong()]?.let { QaRunListItem.of(summary, it) } })
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
        issueCount = issueCount,
    )

    companion object {
        const val MAX_LIMIT = 50
        const val MAX_SIZE = 100
    }
}
