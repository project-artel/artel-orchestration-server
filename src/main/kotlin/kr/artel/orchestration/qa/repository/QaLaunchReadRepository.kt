package kr.artel.orchestration.qa.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
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
    val gameBuildIsEditor: Boolean?,
    val model: String?,
    val reasoningEffort: String?,
    val keySource: String?,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val casesPassed: Int?,
    val casesTotal: Int?,
    val issueCount: Int,
    val startedBy: Long = 0,
    /** 코드 검사가 추가 검토로 고른 판정 수와, 그중 사람이 아직 정하지 않은 수(ARTEL-1254). */
    val reviewFlagged: Int = 0,
    val reviewPending: Int = 0,
    val casesFailed: Int? = null,
)

/** 상단바 알림함 한 줄(ARTEL-1254): 내가 시작해 끝난 실행과 그 프로젝트, 읽었는지. */
data class QaNoticeRow(
    val run: QaRunSummaryRow,
    val projectId: Long,
    val projectName: String,
    val read: Boolean,
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
            $RUN_COLUMNS
            $RUN_FROM
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

    /**
     * 내가 시작해 끝난 실행, 최신순으로 [limit] 개(ARTEL-1254). 프로젝트를 가리지 않지만, 지금 참여하지 않는
     * 프로젝트의 실행은 뺀다.
     */
    suspend fun findNotices(appUserId: Long, limit: Int): List<QaNoticeRow> =
        databaseClient.sql(
            """
            $RUN_COLUMNS,
                   p.id AS project_id, p.name AS project_name,
                   EXISTS (SELECT 1 FROM qa_notice_read n WHERE n.app_user_id = :appUserId AND n.qa_run_id = r.id) AS notice_read
            $RUN_FROM
              JOIN project p ON p.id = gi.project_id
             WHERE r.started_by = :appUserId AND r.completed_at IS NOT NULL
               AND EXISTS (SELECT 1 FROM project_member pm WHERE pm.project_id = gi.project_id AND pm.app_user_id = :appUserId)
             ORDER BY r.completed_at DESC, r.id DESC
             LIMIT :limit
            """.trimIndent()
        )
            .bind("appUserId", appUserId)
            .bind("limit", limit)
            .map { row: Readable ->
                QaNoticeRow(
                    run = row.toRunRow(),
                    projectId = row.long("project_id"),
                    projectName = row.get("project_name", String::class.java) ?: "",
                    read = row.get("notice_read", Boolean::class.javaObjectType) ?: false,
                )
            }
            .flow()
            .toList()

    /** 알림함 배지의 수: 내가 시작해 끝났는데 읽지 않은 실행. [findNotices] 와 같은 조건이다. */
    suspend fun countUnreadNotices(appUserId: Long): Int =
        databaseClient.sql(
            """
            SELECT COUNT(*) AS cnt
              FROM qa_run r
              JOIN game_instance gi ON gi.id = r.game_instance_id
             WHERE r.started_by = :appUserId AND r.completed_at IS NOT NULL
               AND EXISTS (SELECT 1 FROM project_member pm WHERE pm.project_id = gi.project_id AND pm.app_user_id = :appUserId)
               AND NOT EXISTS (SELECT 1 FROM qa_notice_read n WHERE n.app_user_id = :appUserId AND n.qa_run_id = r.id)
            """.trimIndent()
        )
            .bind("appUserId", appUserId)
            .map { row: Readable -> row.long("cnt").toInt() }
            .one()
            .awaitSingle()

    /** 알림 하나를 읽은 것으로 둔다. 내가 시작한 실행이 아니면 아무것도 하지 않고 0 이다. */
    suspend fun markNoticeRead(appUserId: Long, qaRunId: Long): Long =
        databaseClient.sql(
            """
            INSERT INTO qa_notice_read (app_user_id, qa_run_id)
            SELECT r.started_by, r.id FROM qa_run r WHERE r.id = :qaRunId AND r.started_by = :appUserId
            ON CONFLICT DO NOTHING
            """.trimIndent()
        )
            .bind("appUserId", appUserId)
            .bind("qaRunId", qaRunId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    /** 내가 시작해 끝난 실행인가. 이미 읽은 알림을 다시 읽음으로 두는 요청을 404 와 가른다. */
    suspend fun isNoticeOf(appUserId: Long, qaRunId: Long): Boolean =
        databaseClient.sql("SELECT 1 FROM qa_run r WHERE r.id = :qaRunId AND r.started_by = :appUserId")
            .bind("appUserId", appUserId)
            .bind("qaRunId", qaRunId)
            .map { _: Readable -> true }
            .flow()
            .toList()
            .isNotEmpty()

    /** 내가 시작해 끝난 실행 전부를 읽은 것으로 둔다. */
    suspend fun markAllNoticesRead(appUserId: Long): Long =
        databaseClient.sql(
            """
            INSERT INTO qa_notice_read (app_user_id, qa_run_id)
            SELECT r.started_by, r.id FROM qa_run r WHERE r.started_by = :appUserId AND r.completed_at IS NOT NULL
            ON CONFLICT DO NOTHING
            """.trimIndent()
        )
            .bind("appUserId", appUserId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

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
        gameBuildIsEditor = get("game_build_is_editor", java.lang.Boolean::class.java)?.booleanValue(),
        model = get("model", String::class.java),
        reasoningEffort = get("reasoning_effort", String::class.java),
        keySource = get("key_source", String::class.java),
        status = get("status", String::class.java)!!,
        startedAt = instant("started_at")!!,
        completedAt = instant("completed_at"),
        casesPassed = (get("cases_passed", Number::class.java))?.toInt(),
        casesTotal = (get("cases_total", Number::class.java))?.toInt(),
        casesFailed = (get("cases_failed", Number::class.java))?.toInt(),
        issueCount = (get("issue_count", Number::class.java))?.toInt() ?: 0,
        startedBy = long("started_by"),
        reviewFlagged = (get("review_flagged", Number::class.java))?.toInt() ?: 0,
        reviewPending = (get("review_pending", Number::class.java))?.toInt() ?: 0,
    )

    private companion object {
        /** 실행 목록 한 행의 칸. 부르는 쪽이 칸을 더 붙일 수 있다. */
        val RUN_COLUMNS = """
SELECT r.id, r.test_run_id, tr.name AS test_run_name,
       r.game_instance_id, gi.name AS game_instance_name,
       gb.version AS game_build_version, gb.is_editor AS game_build_is_editor,
       ft.model, ft.reasoning_effort, r.key_source,
       r.status, r.started_at, r.completed_at,
       agg.cases_passed, agg.cases_total, agg.cases_failed,
       (SELECT COUNT(*) FROM issue i JOIN qa_try t2 ON t2.id = i.qa_try_id
         WHERE t2.qa_run_id = r.id) AS issue_count,
       r.started_by,
       (SELECT COUNT(*) FROM qa_verdict_review v WHERE v.qa_run_id = r.id AND v.flagged) AS review_flagged,
       (SELECT COUNT(*) FROM qa_verdict_review v
         WHERE v.qa_run_id = r.id AND v.flagged AND v.decision IS NULL) AS review_pending
        """.trimIndent()

        /** 그 칸들이 읽는 표. 부르는 쪽이 JOIN 을 더 붙이고 WHERE 를 쓴다. */
        val RUN_FROM = """
  FROM qa_run r
  JOIN game_instance gi ON gi.id = r.game_instance_id
  LEFT JOIN test_run tr ON tr.id = r.test_run_id
  LEFT JOIN game_build gb ON gb.id = r.game_build_id
  LEFT JOIN LATERAL (
      SELECT t.model, t.reasoning_effort FROM qa_try t
       WHERE t.qa_run_id = r.id ORDER BY t.id LIMIT 1
  ) ft ON TRUE
  LEFT JOIN LATERAL (
      SELECT SUM(t.cases_passed) AS cases_passed, SUM(t.cases_total) AS cases_total,
             SUM(CASE WHEN EXISTS (SELECT 1 FROM qa_case_attempt a WHERE a.qa_try_id = t.id)
                 THEN (SELECT COUNT(*) FROM (
                     SELECT DISTINCT ON (a.test_case_id) a.verdict FROM qa_case_attempt a
                      WHERE a.qa_try_id = t.id ORDER BY a.test_case_id, a.created_at DESC, a.id DESC
                 ) latest WHERE latest.verdict = 'FAILED')
                 ELSE t.cases_total - t.cases_passed END) AS cases_failed
        FROM qa_try t WHERE t.qa_run_id = r.id
  ) agg ON TRUE
        """.trimIndent()
    }

    private fun Readable.long(name: String): Long = (get(name, Number::class.java))!!.toLong()

    private fun Readable.instant(name: String): Instant? =
        get(name, OffsetDateTime::class.java)?.toInstant()
}
