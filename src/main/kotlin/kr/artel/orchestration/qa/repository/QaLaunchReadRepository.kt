package kr.artel.orchestration.qa.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime

/** 실행(`qa_run`) 목록 한 행. 응답 모양으로 옮기는 것은 서비스가 한다. */
data class QaRunSummaryRow(
    val id: Long,
    val testRunId: Long,
    val testRunName: String?,
    val gameInstanceId: Long,
    val gameInstanceName: String?,
    val gameBuildVersion: String?,
    val model: String?,
    val reasoningEffort: String?,
    val keySource: String?,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val casesPassed: Int?,
    val casesTotal: Int?,
    val issueCount: Int,
)

data class SinceLastQaIssueRow(
    val id: Long,
    val title: String,
    val severity: String?,
    val status: String,
    val qaTryId: Long,
    val scenarioId: Long,
    val scenarioTitle: String,
    val step: Int?,
    val reportedAt: Instant,
)

data class SinceLastQaChangeRow(
    val kind: String,
    val at: Instant,
    val subjectId: Long?,
    val subjectName: String?,
    val count: Int,
)

/**
 * QA 실행 화면이 읽는 값들(ARTEL-1083). 여러 표를 접어 읽기만 한다.
 *
 * LATERAL 집계와 jsonb 펼치기는 파생 쿼리로 표현되지 않아 [DatabaseClient] 를 쓴다
 * ([QaTryDetailRepository] 와 같은 이유).
 *
 * **접근 판정은 여기서 안 한다.** 부르는 쪽([kr.artel.orchestration.qa.service.QaLaunchReadService])이
 * 프로젝트 참여를 먼저 확인하고 온다. 질의마다 멤버십 조인을 다시 쓰면 유출 지점이 그만큼 는다.
 *
 * `?` 연산자는 쓰지 않는다 — R2DBC 파라미터 자리로 먹힌다. jsonb 키 존재는 `->> … IS NOT NULL` 로 본다.
 */
@Repository
class QaLaunchReadRepository(
    private val databaseClient: DatabaseClient,
) {

    /**
     * 프로젝트의 실행을 최신순으로. [testRunId] 를 주면 그 런의 실행만.
     *
     * 프로젝트는 게임 인스턴스로 가른다. `qa_run` 에는 project 칸이 없고, 런은 실행 뒤 지워질 수 있지만
     * 인스턴스는 soft delete 라 행이 남는다 — 지워진 런의 실행도 기록에서 빠지지 않는다.
     *
     * 케이스 합은 SUM 이라 판정이 하나도 없으면 null 로 남는다. 0 으로 뭉개면 "0/0 통과" 로 읽힌다.
     */
    suspend fun findRuns(projectId: Long, testRunId: Long?, limit: Int): List<QaRunSummaryRow> {
        val runFilter = if (testRunId != null) "AND r.test_run_id = :testRunId" else ""
        var spec = databaseClient.sql(
            """
            SELECT r.id, r.test_run_id, tr.name AS test_run_name,
                   r.game_instance_id, gi.name AS game_instance_name,
                   gb.version AS game_build_version,
                   ft.model, ft.reasoning_effort, r.key_source,
                   r.status, r.started_at, r.completed_at,
                   agg.cases_passed, agg.cases_total,
                   (SELECT COUNT(*) FROM issue i JOIN qa_try t2 ON t2.id = i.qa_try_id
                     WHERE t2.qa_run_id = r.id) AS issue_count
              FROM qa_run r
              JOIN game_instance gi ON gi.id = r.game_instance_id
              LEFT JOIN test_run tr ON tr.id = r.test_run_id
              LEFT JOIN game_build gb ON gb.id = r.game_build_id
              LEFT JOIN LATERAL (
                  SELECT t.model, t.reasoning_effort FROM qa_try t
                   WHERE t.qa_run_id = r.id ORDER BY t.id LIMIT 1
              ) ft ON TRUE
              LEFT JOIN LATERAL (
                  SELECT SUM(t.cases_passed) AS cases_passed, SUM(t.cases_total) AS cases_total
                    FROM qa_try t WHERE t.qa_run_id = r.id
              ) agg ON TRUE
             WHERE gi.project_id = :projectId
               $runFilter
             ORDER BY r.started_at DESC, r.id DESC
             LIMIT :limit
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("limit", limit)
        if (testRunId != null) spec = spec.bind("testRunId", testRunId)
        return spec.map { row: Readable -> row.toRunRow() }.flow().toList()
    }

    /** 한 실행에서 나온 이슈. 나온 순서대로. */
    suspend fun findIssuesOfRun(qaRunId: Long): List<SinceLastQaIssueRow> =
        databaseClient.sql(
            """
            SELECT i.id, i.title, i.severity, i.status, i.qa_try_id,
                   t.test_scenario_id, COALESCE(ts.title, '') AS scenario_title,
                   CASE WHEN (i.detail ->> 'step') ~ '^[0-9]+$' THEN (i.detail ->> 'step')::int END AS step,
                   i.reported_at
              FROM issue i
              JOIN qa_try t ON t.id = i.qa_try_id
              LEFT JOIN test_scenario ts ON ts.id = t.test_scenario_id
             WHERE t.qa_run_id = :qaRunId
             ORDER BY i.reported_at, i.id
            """.trimIndent()
        )
            .bind("qaRunId", qaRunId)
            .map { row: Readable ->
                SinceLastQaIssueRow(
                    id = row.long("id"),
                    title = row.get("title", String::class.java) ?: "",
                    severity = row.get("severity", String::class.java),
                    status = row.get("status", String::class.java)!!,
                    qaTryId = row.long("qa_try_id"),
                    scenarioId = row.long("test_scenario_id"),
                    scenarioTitle = row.get("scenario_title", String::class.java) ?: "",
                    step = row.get("step", Int::class.javaObjectType),
                    reportedAt = row.instant("reported_at")!!,
                )
            }
            .flow()
            .toList()

    /**
     * [since] 뒤로 이 런에 대해 기록된 변경. 종류마다 한 질의이고, 묶어서 최신순으로 돌려준다.
     *
     * - 이슈 해결: 이 런의 실행에서 나온 이슈 중 [since] 뒤에 해결된 것.
     * - 빌드 보고: 이 프로젝트에 [since] 뒤 처음 보고된 빌드(editor build 제외).
     * - 시나리오 추가: 이 런에 [since] 뒤 붙은 시나리오.
     * - 시나리오 수정: 그 전부터 이 런에 있던 시나리오 중 [since] 뒤 수정된 것. 추가와 겹치지 않게 가른다.
     * - 케이스 새 버전: 이 런 시나리오의 케이스 중 런의 빌드에서 [since] 뒤 생긴 버전 — 건수로 하나.
     */
    suspend fun findChangesSince(projectId: Long, testRunId: Long, since: Instant): List<SinceLastQaChangeRow> {
        val rows = mutableListOf<SinceLastQaChangeRow>()
        rows += changes(
            """
            SELECT 'ISSUE_RESOLVED' AS kind, i.resolved_at AS at, i.id AS subject_id, i.title AS subject_name, 1 AS cnt
              FROM issue i
              JOIN qa_try t ON t.id = i.qa_try_id
              JOIN qa_run r ON r.id = t.qa_run_id
             WHERE r.test_run_id = :testRunId AND i.resolved_at > :since
            """, mapOf("testRunId" to testRunId, "since" to since),
        )
        rows += changes(
            """
            SELECT 'BUILD_REPORTED' AS kind, gb.created_at AS at, gb.id AS subject_id, gb.version AS subject_name, 1 AS cnt
              FROM game_build gb
             WHERE gb.project_id = :projectId AND gb.created_at > :since
            """, mapOf("projectId" to projectId, "since" to since),
        )
        rows += changes(
            """
            SELECT 'SCENARIO_ADDED' AS kind, trs.created_at AS at, s.id AS subject_id, s.title AS subject_name, 1 AS cnt
              FROM test_run_scenario trs
              JOIN test_scenario s ON s.id = trs.test_scenario_id
             WHERE trs.test_run_id = :testRunId AND trs.created_at > :since
            """, mapOf("testRunId" to testRunId, "since" to since),
        )
        rows += changes(
            """
            SELECT 'SCENARIO_UPDATED' AS kind, s.updated_at AS at, s.id AS subject_id, s.title AS subject_name, 1 AS cnt
              FROM test_run_scenario trs
              JOIN test_scenario s ON s.id = trs.test_scenario_id
             WHERE trs.test_run_id = :testRunId AND trs.created_at <= :since AND s.updated_at > :since
            """, mapOf("testRunId" to testRunId, "since" to since),
        )
        rows += changes(
            """
            SELECT 'CASE_VERSION_ADDED' AS kind, MAX(tv.created_at) AS at, NULL::bigint AS subject_id,
                   NULL::text AS subject_name, COUNT(DISTINCT tv.id)::int AS cnt
              FROM test_run tr
              JOIN test_run_scenario trs ON trs.test_run_id = tr.id
              JOIN test_scenario s ON s.id = trs.test_scenario_id
              CROSS JOIN LATERAL jsonb_array_elements(s.steps) e
              JOIN test_case_version tv ON tv.test_case_id = (e ->> 'case_id')::bigint
                                       AND tv.game_build_id = tr.game_build_id
             WHERE tr.id = :testRunId AND tv.created_at > :since
               AND (e ->> 'case_id') ~ '^[0-9]+$'
            HAVING COUNT(DISTINCT tv.id) > 0
            """, mapOf("testRunId" to testRunId, "since" to since),
        )
        return rows.sortedByDescending { it.at }
    }

    private suspend fun changes(sql: String, binds: Map<String, Any>): List<SinceLastQaChangeRow> =
        binds.entries.fold(databaseClient.sql(sql.trimIndent())) { spec, (name, value) -> spec.bind(name, value) }
            .map { row: Readable ->
                SinceLastQaChangeRow(
                    kind = row.get("kind", String::class.java)!!,
                    at = row.instant("at")!!,
                    subjectId = row.get("subject_id", Long::class.javaObjectType),
                    subjectName = row.get("subject_name", String::class.java),
                    count = row.get("cnt", Int::class.javaObjectType) ?: 1,
                )
            }
            .flow()
            .toList()

    private fun Readable.toRunRow() = QaRunSummaryRow(
        id = long("id"),
        testRunId = long("test_run_id"),
        testRunName = get("test_run_name", String::class.java),
        gameInstanceId = long("game_instance_id"),
        gameInstanceName = get("game_instance_name", String::class.java),
        gameBuildVersion = get("game_build_version", String::class.java),
        model = get("model", String::class.java),
        reasoningEffort = get("reasoning_effort", String::class.java),
        keySource = get("key_source", String::class.java),
        status = get("status", String::class.java)!!,
        startedAt = instant("started_at")!!,
        completedAt = instant("completed_at"),
        casesPassed = (get("cases_passed", Number::class.java))?.toInt(),
        casesTotal = (get("cases_total", Number::class.java))?.toInt(),
        issueCount = (get("issue_count", Number::class.java))?.toInt() ?: 0,
    )

    private fun Readable.long(name: String): Long = (get(name, Number::class.java))!!.toLong()

    private fun Readable.instant(name: String): Instant? =
        get(name, OffsetDateTime::class.java)?.toInstant()
}
