package kr.artel.orchestration.qa.review

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class CaseContentReviewResponse(
    val testCaseId: String, val state: String, val note: String?, val logId: String?, val attemptId: String?,
    val qaTryId: String?, val qaRunId: String?, val updatedAt: Instant, val revisedAt: Instant?, val resolvedAt: Instant?,
)

@Service
class QaCaseContentReviews(private val database: DatabaseClient, private val access: ProjectAccessService) {
    suspend fun list(projectId: Long, userId: Long, testCaseId: Long?): List<CaseContentReviewResponse> {
        access.requireMember(projectId, userId)
        return database.sql("""
            SELECT r.*, a.qa_try_id, t.qa_run_id FROM qa_case_content_review r
            JOIN test_case c ON c.id = r.test_case_id AND c.project_id = r.project_id
            LEFT JOIN qa_case_attempt a ON a.id = r.source_attempt_id LEFT JOIN qa_try t ON t.id = a.qa_try_id
            WHERE r.project_id = :project AND (:caseId = 0 OR r.test_case_id = :caseId) ORDER BY r.test_case_id
        """).bind("project", projectId).bind("caseId", testCaseId ?: 0L).map { row, _ ->
            CaseContentReviewResponse(row.get("test_case_id")!!.toString(), row.get("state", String::class.java)!!,
                row.get("note", String::class.java), row.get("source_log_id")?.toString(), row.get("source_attempt_id")?.toString(),
                row.get("qa_try_id")?.toString(), row.get("qa_run_id")?.toString(), row.get("updated_at", Instant::class.java)!!,
                row.get("revised_at", Instant::class.java), row.get("resolved_at", Instant::class.java))
        }.flow().toList()
    }
}

@RestController
class QaCaseContentReviewController(private val reviews: QaCaseContentReviews) {
    @GetMapping("/api/projects/{projectId}/qa-evaluation/case-content-reviews")
    suspend fun list(@PathVariable projectId: Long, @CurrentUserId userId: Long,
        @RequestParam(required = false) testCaseId: Long?) = reviews.list(projectId, userId, testCaseId)
}
