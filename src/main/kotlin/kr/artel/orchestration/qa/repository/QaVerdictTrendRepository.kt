package kr.artel.orchestration.qa.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.qa.dto.QaVerdictTrendBucket
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime

data class QaVerdictTrendCounts(
    val key: String,
    val passed: Int = 0,
    val failed: Int = 0,
    val regression: Int = 0,
    val undecided: Int = 0,
    val runs: Int = 0,
    val startedAt: Instant? = null
)

data class QaVerdictTrendTopCase(
    val key: String,
    val testCaseId: Long,
    val caseNumber: Long?,
    val title: String,
    val failures: Int
)

data class QaVerdictTrendBuildRow(val id: Long, val version: String, val isEditor: Boolean, val createdAt: Instant)

/**
 * 대시보드 QA 판정 추이의 집계(ARTEL-1386). 판정의 원본은 `qa_log` 의 `CASE_VERDICT` 행이다 —
 * [kr.artel.orchestration.testcase.service.TestCaseVerdictService] 가 실행 중 Test Case 상태를 바꿀 때마다 한 줄씩 남긴다.
 *
 * **회귀는 기간 밖 판정까지 보고 정한다.** 바로 앞 판정을 `LAG` 로 읽는데, 기간으로 먼저 거르면 기간 첫날의 실패는
 * 앞 판정이 없어 회귀로 잡히지 않는다. 그래서 프로젝트의 판정 전부에 `LAG` 을 건 뒤 기간으로 거른다.
 *
 * 버린 런(ARTEL-1371)은 판정이 없으므로 원래 안 잡히지만, 미판정 수와 실행 수에서도 빼려고 명시해서 거른다.
 * 접근 확인은 부르는 쪽이 한다.
 */
@Repository
class QaVerdictTrendRepository(private val databaseClient: DatabaseClient) {

    suspend fun verdictCounts(
        projectId: Long, from: Instant, to: Instant, bucket: QaVerdictTrendBucket, timeZone: String, gameBuildId: Long?
    ): List<QaVerdictTrendCounts> =
        databaseClient.sql(
            """
            WITH verdicts AS (
                SELECT l.created_at,
                       t.qa_run_id,
                       l.payload ->> 'verification_status' AS status,
                       LAG(l.payload ->> 'verification_status')
                           OVER (PARTITION BY l.payload ->> 'case_id' ORDER BY l.created_at, l.id) AS previous
                  FROM qa_log l
                  JOIN qa_try t ON t.id = l.qa_try_id
                  JOIN qa_run r ON r.id = t.qa_run_id
                  JOIN test_run tr ON tr.id = r.test_run_id
                 WHERE l.type = 'CASE_VERDICT'
                   AND tr.project_id = :projectId
                   AND r.discarded_at IS NULL
                   AND (CAST(:gameBuildId AS BIGINT) IS NULL OR (l.payload ->> 'game_build_id')::bigint = :gameBuildId)
            )
            SELECT ${bucketKey(bucket, "created_at", "qa_run_id")} AS key,
                   COUNT(*) FILTER (WHERE status = 'VERIFIED') AS passed,
                   COUNT(*) FILTER (WHERE status = 'FAILED' AND previous IS DISTINCT FROM 'VERIFIED') AS failed,
                   COUNT(*) FILTER (WHERE status = 'FAILED' AND previous = 'VERIFIED') AS regression
              FROM verdicts
             WHERE created_at >= :from AND created_at < :to
             GROUP BY 1
            """.trimIndent()
        )
            .bindAll(projectId, from, to, bucket, timeZone, gameBuildId)
            .map { row: Readable ->
                QaVerdictTrendCounts(
                    key = row.get("key", String::class.java)!!,
                    passed = row.int("passed"),
                    failed = row.int("failed"),
                    regression = row.int("regression")
                )
            }
            .flow()
            .toList()

    /**
     * 판정하지 못한 Test Case. agent 가 결론을 못 낸 시도와, 모델 API 오류로 미판정이 된 시나리오의 Test Case 다.
     * 시나리오 쪽은 그 시나리오의 스텝에 담긴 Test Case 를 한 번씩 센다.
     */
    suspend fun undecidedCounts(
        projectId: Long, from: Instant, to: Instant, bucket: QaVerdictTrendBucket, timeZone: String, gameBuildId: Long?
    ): List<QaVerdictTrendCounts> =
        databaseClient.sql(
            """
            WITH undecided AS (
                SELECT a.finished_at AS at, t.qa_run_id
                  FROM qa_case_attempt a
                  JOIN qa_try t ON t.id = a.qa_try_id
                  JOIN qa_run r ON r.id = t.qa_run_id
                  JOIN test_run tr ON tr.id = r.test_run_id
                 WHERE a.verdict IN ('INCONCLUSIVE', 'INTERRUPTED')
                   AND tr.project_id = :projectId
                   AND r.discarded_at IS NULL
                   AND (CAST(:gameBuildId AS BIGINT) IS NULL OR r.game_build_id = :gameBuildId)
                UNION ALL
                SELECT t.completed_at AS at, t.qa_run_id
                  FROM qa_try t
                  JOIN qa_run r ON r.id = t.qa_run_id
                  JOIN test_run tr ON tr.id = r.test_run_id
                  JOIN test_scenario ts ON ts.id = t.test_scenario_id
                  CROSS JOIN LATERAL (
                      SELECT DISTINCT step ->> 'case_id' AS case_id
                        FROM jsonb_array_elements(ts.steps) step
                       WHERE step ->> 'case_id' IS NOT NULL
                  ) cases
                 WHERE t.status = 'UNDECIDED'
                   AND tr.project_id = :projectId
                   AND r.discarded_at IS NULL
                   AND (CAST(:gameBuildId AS BIGINT) IS NULL OR r.game_build_id = :gameBuildId)
            )
            SELECT ${bucketKey(bucket, "at", "qa_run_id")} AS key, COUNT(*) AS undecided
              FROM undecided
             WHERE at >= :from AND at < :to
             GROUP BY 1
            """.trimIndent()
        )
            .bindAll(projectId, from, to, bucket, timeZone, gameBuildId)
            .map { row: Readable -> QaVerdictTrendCounts(key = row.get("key", String::class.java)!!, undecided = row.int("undecided")) }
            .flow()
            .toList()

    /** 칸마다 QA 실행 수. `RUN` 이면 실행마다 한 칸이고 시작 시각을 싣는다. 버린 런은 세지 않는다. */
    suspend fun runCounts(
        projectId: Long, from: Instant, to: Instant, bucket: QaVerdictTrendBucket, timeZone: String, gameBuildId: Long?
    ): List<QaVerdictTrendCounts> =
        databaseClient.sql(
            """
            SELECT ${bucketKey(bucket, "r.started_at", "r.id")} AS key, COUNT(*) AS runs, MIN(r.started_at) AS started_at
              FROM qa_run r
              JOIN test_run tr ON tr.id = r.test_run_id
             WHERE tr.project_id = :projectId
               AND r.discarded_at IS NULL
               AND r.started_at >= :from AND r.started_at < :to
               AND (CAST(:gameBuildId AS BIGINT) IS NULL OR r.game_build_id = :gameBuildId)
             GROUP BY 1
            """.trimIndent()
        )
            .bindAll(projectId, from, to, bucket, timeZone, gameBuildId)
            .map { row: Readable ->
                QaVerdictTrendCounts(
                    key = row.get("key", String::class.java)!!,
                    runs = row.int("runs"),
                    startedAt = row.get("started_at", OffsetDateTime::class.java)?.toInstant()
                )
            }
            .flow()
            .toList()

    /** 칸마다 가장 많이 실패한(회귀 포함) Test Case 하나. 횟수가 같으면 번호가 작은 것이다. */
    suspend fun topFailing(
        projectId: Long, from: Instant, to: Instant, bucket: QaVerdictTrendBucket, timeZone: String, gameBuildId: Long?
    ): List<QaVerdictTrendTopCase> =
        databaseClient.sql(
            """
            WITH failures AS (
                SELECT ${bucketKey(bucket, "l.created_at", "t.qa_run_id")} AS key,
                       (l.payload ->> 'case_id')::bigint AS case_id,
                       COUNT(*) AS failures
                  FROM qa_log l
                  JOIN qa_try t ON t.id = l.qa_try_id
                  JOIN qa_run r ON r.id = t.qa_run_id
                  JOIN test_run tr ON tr.id = r.test_run_id
                 WHERE l.type = 'CASE_VERDICT'
                   AND l.payload ->> 'verification_status' = 'FAILED'
                   AND tr.project_id = :projectId
                   AND r.discarded_at IS NULL
                   AND l.created_at >= :from AND l.created_at < :to
                   AND (CAST(:gameBuildId AS BIGINT) IS NULL OR (l.payload ->> 'game_build_id')::bigint = :gameBuildId)
                 GROUP BY 1, 2
            ),
            ranked AS (
                SELECT f.*, ROW_NUMBER() OVER (PARTITION BY f.key ORDER BY f.failures DESC, f.case_id) AS rank
                  FROM failures f
            )
            SELECT ranked.key, ranked.case_id, ranked.failures, i.case_number, tc.step AS title
              FROM ranked
              JOIN test_case tc ON tc.id = ranked.case_id
              LEFT JOIN test_case_identifier i ON i.test_case_id = ranked.case_id
             WHERE ranked.rank = 1
            """.trimIndent()
        )
            .bindAll(projectId, from, to, bucket, timeZone, gameBuildId)
            .map { row: Readable ->
                QaVerdictTrendTopCase(
                    key = row.get("key", String::class.java)!!,
                    testCaseId = row.get("case_id", Number::class.java)!!.toLong(),
                    caseNumber = row.get("case_number", Number::class.java)?.toLong(),
                    title = row.get("title", String::class.java) ?: "",
                    failures = row.int("failures")
                )
            }
            .flow()
            .toList()

    /** 기간 안에 올라온 프로젝트의 게임 빌드, 오래된 것부터. */
    suspend fun buildsIn(projectId: Long, from: Instant, to: Instant): List<QaVerdictTrendBuildRow> =
        databaseClient.sql(
            """
            SELECT id, version, is_editor, created_at
              FROM game_build
             WHERE project_id = :projectId AND created_at >= :from AND created_at < :to
             ORDER BY created_at, id
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("from", from)
            .bind("to", to)
            .map { row: Readable ->
                QaVerdictTrendBuildRow(
                    id = row.get("id", Number::class.java)!!.toLong(),
                    version = row.get("version", String::class.java) ?: "",
                    isEditor = row.get("is_editor", Boolean::class.javaObjectType) ?: false,
                    createdAt = row.get("created_at", OffsetDateTime::class.java)!!.toInstant()
                )
            }
            .flow()
            .toList()

    /** `RUN` 칸 열쇠에는 시간대가 없어 `:timeZone` 을 묶지 않는다 — 글에 없는 이름을 묶으면 실행이 실패한다. */
    private fun DatabaseClient.GenericExecuteSpec.bindAll(
        projectId: Long, from: Instant, to: Instant, bucket: QaVerdictTrendBucket, timeZone: String, gameBuildId: Long?
    ): DatabaseClient.GenericExecuteSpec {
        val base = bind("projectId", projectId).bind("from", from).bind("to", to)
        val spec = if (bucket == QaVerdictTrendBucket.RUN) base else base.bind("timeZone", timeZone)
        return if (gameBuildId == null) spec.bindNull("gameBuildId", java.lang.Long::class.java) else spec.bind("gameBuildId", gameBuildId)
    }

    private fun Readable.int(name: String): Int = (get(name, Number::class.java) ?: 0).toInt()

    private companion object {
        /**
         * 칸 열쇠. 열 이름은 이 파일이 고른 것만 들어온다 — 사용자 입력이 아니다. 날 · 주는 [timeZone] 의 달력으로 나눈다.
         * 주는 월요일에 시작한다(`date_trunc('week')`).
         */
        fun bucketKey(bucket: QaVerdictTrendBucket, at: String, runId: String): String = when (bucket) {
            QaVerdictTrendBucket.DAY -> "to_char($at AT TIME ZONE :timeZone, 'YYYY-MM-DD')"
            QaVerdictTrendBucket.WEEK -> "to_char(date_trunc('week', $at AT TIME ZONE :timeZone), 'YYYY-MM-DD')"
            QaVerdictTrendBucket.RUN -> "CAST($runId AS TEXT)"
        }
    }
}
