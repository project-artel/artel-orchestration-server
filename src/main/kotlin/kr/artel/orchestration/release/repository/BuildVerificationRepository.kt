package kr.artel.orchestration.release.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime

data class VerificationBuildRow(val id: Long, val version: String, val isEditor: Boolean, val createdAt: Instant)

/**
 * 빌드에 담긴 TC 한 줄과 그 버전의 지금 판정. [statusPassed] 는 `VERIFIED` 면 true, `FAILED` 면 false, 그 밖(`DRAFT` ·
 * 다시 검증할 `REGRESSION` · `BROKEN`)이면 null 이다. [statusAt] 은 그 판정이 난 시각(`verified_at`), [carried] 는 앞 버전
 * 판정을 이어받은 것이다.
 */
data class VerificationCaseRow(val testCaseId: Long, val statusPassed: Boolean?, val statusAt: Instant?, val carried: Boolean)

/** 시도 한 번의 판정. [passed] 가 null 이면 판정 못함이다. */
data class VerificationAttemptRow(val testCaseId: Long, val finishedAt: Instant, val passed: Boolean?)

/**
 * 빌드 검증 진행의 원재료(ARTEL-1386). 빌드의 TC 는 `test_case_version` 이고, 판정은 `qa_case_attempt` 다 —
 * 사람이 검토해 바꾼 판정(`qa_verdict_review.decision`)이 있으면 그것을 쓴다. 버린 런(ARTEL-1371)과
 * 중간에 멈춘 시도(`INTERRUPTED`)는 판정이 아니므로 뺀다. 접근 확인은 부르는 쪽이 한다.
 */
@Repository
class BuildVerificationRepository(private val databaseClient: DatabaseClient) {

    suspend fun build(projectId: Long, gameBuildId: Long): VerificationBuildRow? =
        databaseClient.sql("SELECT id, version, is_editor, created_at FROM game_build WHERE id = :id AND project_id = :projectId")
            .bind("id", gameBuildId)
            .bind("projectId", projectId)
            .map(::buildRow)
            .one()
            .awaitFirstOrNull()

    /** 케이스 라이브러리에 보이는 빌드(TC 가 있고 숨기지 않은 것), 최근에 올라온 것부터. */
    suspend fun libraryBuilds(projectId: Long, limit: Int): List<VerificationBuildRow> =
        databaseClient.sql(
            """
            SELECT b.id, b.version, b.is_editor, b.created_at
              FROM game_build b
              LEFT JOIN test_case_version_setting s ON s.game_build_id = b.id
             WHERE b.project_id = :projectId
               AND COALESCE(s.hidden, FALSE) = FALSE
               AND EXISTS (SELECT 1 FROM test_case_version v WHERE v.game_build_id = b.id)
             ORDER BY b.created_at DESC, b.id DESC
             LIMIT :limit
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("limit", limit)
            .map(::buildRow)
            .flow()
            .toList()

    /** [build] 바로 앞에 올라온 라이브러리 빌드. 대시보드의 이전 버전 비교와 같은 고르기다(만든 순서). */
    suspend fun previousBuild(projectId: Long, build: VerificationBuildRow): VerificationBuildRow? =
        databaseClient.sql(
            """
            SELECT b.id, b.version, b.is_editor, b.created_at
              FROM game_build b
              LEFT JOIN test_case_version_setting s ON s.game_build_id = b.id
             WHERE b.project_id = :projectId
               AND b.id <> :id
               AND b.created_at <= :createdAt
               AND COALESCE(s.hidden, FALSE) = FALSE
               AND EXISTS (SELECT 1 FROM test_case_version v WHERE v.game_build_id = b.id)
             ORDER BY b.created_at DESC, b.id DESC
             LIMIT 1
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("id", build.id)
            .bind("createdAt", build.createdAt)
            .map(::buildRow)
            .one()
            .awaitFirstOrNull()

    suspend fun cases(gameBuildId: Long): List<VerificationCaseRow> =
        databaseClient.sql(
            """
            SELECT test_case_id,
                   CASE verification_status WHEN 'VERIFIED' THEN TRUE WHEN 'FAILED' THEN FALSE END AS status_passed,
                   verified_at,
                   verdict_carried_from_build_id IS NOT NULL AS carried
              FROM test_case_version
             WHERE game_build_id = :gameBuildId
            """.trimIndent()
        )
            .bind("gameBuildId", gameBuildId)
            .map { row: Readable ->
                VerificationCaseRow(
                    testCaseId = row.long("test_case_id"),
                    statusPassed = row.get("status_passed", Boolean::class.javaObjectType),
                    statusAt = row.get("verified_at", OffsetDateTime::class.java)?.toInstant(),
                    carried = row.get("carried", Boolean::class.javaObjectType) ?: false
                )
            }
            .flow()
            .toList()

    /** [gameBuildId] 의 TC 중 그 빌드에서 통과(`VERIFIED`)인 것. 회귀를 가를 때 앞 버전에 쓴다. */
    suspend fun passedCaseIds(gameBuildId: Long): Set<Long> =
        databaseClient.sql("SELECT test_case_id FROM test_case_version WHERE game_build_id = :gameBuildId AND verification_status = 'VERIFIED'")
            .bind("gameBuildId", gameBuildId)
            .map { row: Readable -> row.long("test_case_id") }
            .flow()
            .toList()
            .toSet()

    suspend fun attempts(projectId: Long, gameBuildId: Long): List<VerificationAttemptRow> =
        databaseClient.sql(
            """
            SELECT a.test_case_id,
                   a.finished_at,
                   CASE WHEN rv.decision IN ('PASSED', 'FAILED') THEN rv.decision
                        WHEN rv.decision IS NOT NULL THEN 'INCONCLUSIVE'
                        ELSE a.verdict
                   END AS verdict
              FROM qa_case_attempt a
              JOIN qa_try t ON t.id = a.qa_try_id
              JOIN qa_run r ON r.id = t.qa_run_id
              LEFT JOIN qa_verdict_review rv ON rv.qa_log_id = a.qa_log_id
             WHERE a.project_id = :projectId
               AND r.game_build_id = :gameBuildId
               AND r.discarded_at IS NULL
               AND a.finished_at IS NOT NULL
               AND a.verdict <> 'INTERRUPTED'
             ORDER BY a.finished_at, a.id
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("gameBuildId", gameBuildId)
            .map { row: Readable ->
                VerificationAttemptRow(
                    testCaseId = row.long("test_case_id"),
                    finishedAt = row.get("finished_at", OffsetDateTime::class.java)!!.toInstant(),
                    passed = when (row.get("verdict", String::class.java)) {
                        "PASSED" -> true
                        "FAILED" -> false
                        else -> null
                    }
                )
            }
            .flow()
            .toList()

    /** 이 빌드에서 시작한 QA 실행의 시작 시각. 버린 런은 뺀다. */
    suspend fun runStarts(projectId: Long, gameBuildId: Long): List<Instant> =
        databaseClient.sql(
            """
            SELECT r.started_at
              FROM qa_run r
              JOIN test_run tr ON tr.id = r.test_run_id
             WHERE tr.project_id = :projectId AND r.game_build_id = :gameBuildId AND r.discarded_at IS NULL
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("gameBuildId", gameBuildId)
            .map { row: Readable -> row.get("started_at", OffsetDateTime::class.java)!!.toInstant() }
            .flow()
            .toList()

    /** 이 빌드의 QA 에서 나온 이슈 중 안 닫힌 `BLOCKER` · `CRITICAL`. 보관하거나 다른 이슈로 합친 것은 뺀다. */
    suspend fun openBlockers(projectId: Long, gameBuildId: Long): Int =
        databaseClient.sql(
            """
            SELECT COUNT(*) AS blockers
              FROM issue i
              JOIN qa_try t ON t.id = i.qa_try_id
              JOIN qa_run r ON r.id = t.qa_run_id
              JOIN test_run tr ON tr.id = r.test_run_id
             WHERE tr.project_id = :projectId
               AND r.game_build_id = :gameBuildId
               AND i.status = 'OPEN'
               AND i.archived_at IS NULL
               AND i.merged_into IS NULL
               AND i.severity IN ('BLOCKER', 'CRITICAL')
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("gameBuildId", gameBuildId)
            .map { row: Readable -> (row.get("blockers", Number::class.java) ?: 0).toInt() }
            .one()
            .awaitFirstOrNull() ?: 0

    private fun buildRow(row: Readable) = VerificationBuildRow(
        id = row.long("id"),
        version = row.get("version", String::class.java) ?: "",
        isEditor = row.get("is_editor", Boolean::class.javaObjectType) ?: false,
        createdAt = row.get("created_at", OffsetDateTime::class.java)!!.toInstant()
    )

    private fun Readable.long(name: String): Long = get(name, Number::class.java)!!.toLong()
}
