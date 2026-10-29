package kr.artel.orchestration.qa.review

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class ReliabilityGroup(val key: String, val counts: ReliabilityCounts)
data class QaReliabilityResponse(
    val from: Instant,
    val to: Instant,
    val overall: ReliabilityCounts,
    val daily: List<ReliabilityGroup>,
    val cases: List<ReliabilityGroup>,
    val sample: String = "USER_SELECTED_REVIEWS",
    val includesInterruptedFailures: Boolean = true,
)
data class QaReviewRevision(
    val id: String,
    val decision: String,
    val note: String?,
    val decidedBy: String?,
    val decidedAt: Instant,
)

@Service
class QaReliabilityService(
    private val database: DatabaseClient,
    private val access: ProjectAccessService,
    private val clock: Clock,
) {
    suspend fun report(projectId: Long, userId: Long, from: Instant?, to: Instant?): QaReliabilityResponse {
        requireMember(projectId, userId)
        val end = to ?: Instant.now(clock)
        val start = from ?: end.minus(Duration.ofDays(30))
        if (!start.isBefore(end) || Duration.between(start, end) > Duration.ofDays(366)) {
            throw BadRequestException("조회 기간은 0일 초과 366일 이하여야 합니다.")
        }
        val groups = database.sql(RELIABILITY_SQL).bind("projectId", projectId).bind("from", start).bind("to", end)
            .map { row, _ ->
                fun count(name: String) = row.get(name, java.lang.Long::class.java)!!.toLong()
                fun ratio(numerator: Long, denominator: Long) = if (denominator == 0L) null else numerator.toDouble() / denominator
                val total = count("total"); val reviewed = count("reviewed")
                val tp = count("tp"); val tn = count("tn"); val fp = count("fp"); val fn = count("fn")
                val counts = ReliabilityCounts(total, reviewed, count("inconclusive"), tp, tn, fp, fn, count("interrupted_fn"),
                    ratio(fp, fp + tn), ratio(fn, tp + fn), ratio(reviewed, total))
                Triple(row.get("kind", String::class.java)!!, row.get("group_key", String::class.java)!!, counts)
            }.flow().toList()
        return QaReliabilityResponse(start, end,
            groups.firstOrNull { it.first == "overall" }?.third ?: reliabilityCounts(emptyList()),
            groups.filter { it.first == "day" }.map { ReliabilityGroup(it.second, it.third) },
            groups.filter { it.first == "case" }.map { ReliabilityGroup(it.second, it.third) })
    }

    suspend fun history(projectId: Long, logId: Long, userId: Long, before: Long?): List<QaReviewRevision> {
        requireMember(projectId, userId)
        val visible = database.sql("""
            SELECT l.id FROM qa_log l JOIN qa_try t ON t.id = l.qa_try_id
            JOIN test_scenario s ON s.id = t.test_scenario_id WHERE l.id = :id AND s.project_id = :project
        """).bind("id", logId).bind("project", projectId).map { row, _ -> row.get("id")!! }.flow().toList()
        if (visible.isEmpty()) throw NotFoundException()
        return database.sql("""
            SELECT * FROM qa_verdict_review_revision WHERE qa_log_id = :id AND id < :before ORDER BY id DESC LIMIT 100
        """).bind("id", logId).bind("before", before ?: Long.MAX_VALUE).map { row, _ ->
            QaReviewRevision(row.get("id")!!.toString(), row.get("decision", String::class.java)!!,
                row.get("note", String::class.java), row.get("decided_by")?.toString(), row.get("decided_at", Instant::class.java)!!)
        }.flow().toList()
    }

    private suspend fun requireMember(projectId: Long, userId: Long) {
        access.requireMember(projectId, userId)
    }

    companion object {
        // 시도별 STATUS 원본을 세므로 같은 TC의 재검증도 독립 표본이다.
        private val RELIABILITY_SQL = """
            WITH observations AS (
                SELECT l.id, COALESCE(a.test_case_id::text, l.payload->>'case_id') AS case_id,
                       COALESCE(a.started_at, l.created_at) AS observed_at,
                       COALESCE(a.verdict, CASE l.payload->>'status' WHEN 'COMPLETED' THEN 'PASSED' WHEN 'FAILED' THEN 'FAILED' END) AS verdict,
                       r.decision
                FROM qa_log l JOIN qa_try t ON t.id = l.qa_try_id
                JOIN test_scenario s ON s.id = t.test_scenario_id
                LEFT JOIN qa_case_attempt a ON a.qa_log_id = l.id
                LEFT JOIN qa_verdict_review r ON r.qa_log_id = l.id
                WHERE s.project_id = :projectId AND l.type = 'STATUS'
                  AND (a.id IS NOT NULL OR (l.payload->>'is_verification' = 'true' AND l.payload->>'status' IN ('COMPLETED','FAILED')))
                  AND COALESCE(a.started_at, l.created_at) >= :from AND COALESCE(a.started_at, l.created_at) < :to
                  AND COALESCE(a.test_case_id::text, l.payload->>'case_id') IS NOT NULL
            ), grouped AS (
                SELECT 'overall' AS kind, 'all' AS group_key, * FROM observations
                UNION ALL SELECT 'day', (observed_at AT TIME ZONE 'Asia/Seoul')::date::text, * FROM observations
                UNION ALL SELECT 'case', case_id, * FROM observations
            )
            SELECT kind, group_key, COUNT(*) AS total, COUNT(decision) AS reviewed,
                COUNT(*) FILTER (WHERE decision = 'INCONCLUSIVE') AS inconclusive,
                COUNT(*) FILTER (WHERE verdict = 'FAILED' AND decision = 'FAILED') AS tp,
                COUNT(*) FILTER (WHERE verdict = 'PASSED' AND decision = 'PASSED') AS tn,
                COUNT(*) FILTER (WHERE verdict = 'FAILED' AND decision = 'PASSED') AS fp,
                COUNT(*) FILTER (WHERE verdict IN ('PASSED','INCONCLUSIVE','INTERRUPTED') AND decision = 'FAILED') AS fn,
                COUNT(*) FILTER (WHERE verdict IN ('INCONCLUSIVE','INTERRUPTED') AND decision = 'FAILED') AS interrupted_fn
            FROM grouped GROUP BY kind, group_key ORDER BY kind, group_key
        """
    }
}

@RestController
@RequestMapping("/api/projects/{projectId}/qa-evaluation")
class QaReliabilityController(private val reliability: QaReliabilityService) {
    @GetMapping("/reliability")
    suspend fun report(@PathVariable projectId: Long, @CurrentUserId userId: Long,
        @RequestParam(required = false) from: Instant?, @RequestParam(required = false) to: Instant?) =
        reliability.report(projectId, userId, from, to)

    @GetMapping("/verdicts/{logId}/reviews")
    suspend fun history(@PathVariable projectId: Long, @PathVariable logId: Long, @CurrentUserId userId: Long,
        @RequestParam(required = false) before: Long?) = reliability.history(projectId, logId, userId, before)
}
