package kr.artel.orchestration.qa.review

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitSingleOrNull
import org.springframework.r2dbc.core.flow
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class CaseReviewRequest(val decision: String, val note: String? = null)
data class CaseFailureOccurrence(val attemptId: String, val testCaseId: String, val qaTryId: String,
    val qaRunId: String?, val logId: String?, val verdict: String, val review: String?,
    val validFailure: Boolean, val falsePositive: Boolean, val finishedAt: Instant?)
data class CaseIssueHistory(val issueId: String, val testCaseId: String?, val previousIssueId: String?,
    val mergedInto: String?, val autoClosed: Boolean, val occurrences: List<CaseFailureOccurrence>)

@RestController
@RequestMapping("/api/projects/{projectId}/qa-evaluation")
class QaCaseIssueController(private val database: DatabaseClient, private val access: ProjectAccessService,
    private val reviews: QaVerdictReviewService) {

    @PutMapping("/verdicts/{logId}/review")
    suspend fun review(@PathVariable projectId: Long, @PathVariable logId: Long, @CurrentUserId userId: Long,
        @RequestBody request: CaseReviewRequest): QaVerdictReviewEntity {
        access.requireMember(projectId, userId)
        database.sql("""SELECT l.id FROM qa_log l JOIN qa_try t ON t.id = l.qa_try_id
            JOIN test_scenario s ON s.id = t.test_scenario_id WHERE l.id = :id AND s.project_id = :project""")
            .bind("id", logId).bind("project", projectId).map { row, _ -> row.get("id")!! }.awaitSingleOrNull()
            ?: throw NotFoundException()
        return reviews.decideOutcome(null, logId, userId, request.decision, request.note)
    }

    @GetMapping("/issues/{issueId}")
    suspend fun history(@PathVariable projectId: Long, @PathVariable issueId: Long, @CurrentUserId userId: Long,
        @RequestParam(required = false) beforeLogId: Long?): CaseIssueHistory {
        access.requireMember(projectId, userId)
        val issue = database.sql("""SELECT i.* FROM issue i JOIN qa_try t ON t.id = i.qa_try_id
            JOIN test_scenario s ON s.id = t.test_scenario_id WHERE i.id = :id AND s.project_id = :project""")
            .bind("id", issueId).bind("project", projectId).map { row, _ ->
                CaseIssueHistory(issueId.toString(), row.get("test_case_id")?.toString(), row.get("previous_issue_id")?.toString(),
                    row.get("merged_into")?.toString(), row.get("auto_closed", java.lang.Boolean::class.java)!!.booleanValue(), emptyList())
            }.awaitSingleOrNull() ?: throw NotFoundException()
        val occurrences = database.sql("""
            SELECT a.*, t.qa_run_id, f.valid_failure, f.false_positive, r.decision
            FROM qa_case_failure f JOIN qa_case_attempt a ON a.id = f.attempt_id JOIN qa_try t ON t.id = a.qa_try_id
            LEFT JOIN qa_verdict_review r ON r.qa_log_id = a.qa_log_id
            WHERE f.issue_id = :issue AND a.qa_log_id < :before ORDER BY a.qa_log_id DESC LIMIT 100
        """).bind("issue", issueId).bind("before", beforeLogId ?: Long.MAX_VALUE).map { row, _ ->
            CaseFailureOccurrence(row.get("id")!!.toString(), row.get("test_case_id")!!.toString(), row.get("qa_try_id")!!.toString(),
                row.get("qa_run_id")?.toString(), row.get("qa_log_id")?.toString(), row.get("verdict", String::class.java)!!,
                row.get("decision", String::class.java), row.get("valid_failure", java.lang.Boolean::class.java)!!.booleanValue(),
                row.get("false_positive", java.lang.Boolean::class.java)!!.booleanValue(), row.get("finished_at", Instant::class.java))
        }.flow().toList()
        return issue.copy(occurrences = occurrences)
    }
}
