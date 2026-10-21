package kr.artel.orchestration.game.repository

import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * 빌드 목록 화면의 요약을 프로젝트 하나에 한 문장으로 읽는다. 빌드마다 묻는 N+1 을 피하려고 인스턴스와 run 의
 * 집계를 `LEFT JOIN LATERAL` 로 빌드 행에 붙였다. 파생 쿼리로는 표현되지 않아 [DatabaseClient] 를 쓴다.
 *
 * 소켓이 붙어 있는지는 DB 에 없고 `SessionManager` 메모리에만 있다. 그래서 인스턴스 id 를 쉼표로 이어 [Row.instanceIds]
 * 로 돌려주고, 세는 일은 서비스가 한다. 접근 확인도 서비스가 먼저 한다.
 *
 * 정렬은 [GameBuildRepository.findAllForMember] 와 같다.
 */
@Repository
class GameBuildSummaryRepository(
    private val databaseClient: DatabaseClient
) {
    data class Row(
        val id: Long,
        val version: String,
        val label: String?,
        val isEditor: Boolean,
        val createdAt: Instant,
        val instanceIds: List<Long>,
        val lastReportedAt: Instant?,
        val cheatCount: Int,
        val behaviorCount: Int,
        val catalogReceivedAt: Instant?,
        val qaRunCount: Int,
        val latestRunId: Long?,
        val latestRunStatus: String?,
        val latestRunStartedAt: Instant?,
        val latestRunCompletedAt: Instant?,
        val stepsPassed: Int?,
        val stepsTotal: Int?
    )

    suspend fun findByProject(projectId: Long): List<Row> =
        databaseClient.sql(SQL)
            .bind("projectId", projectId)
            .map { row, _ ->
                Row(
                    id = row.get("id", java.lang.Long::class.java)!!.toLong(),
                    version = row.get("version", String::class.java)!!,
                    label = row.get("label", String::class.java),
                    isEditor = row.get("is_editor", java.lang.Boolean::class.java)!!.booleanValue(),
                    createdAt = row.get("created_at", Instant::class.java)!!,
                    instanceIds = row.get("instance_ids", String::class.java)
                        ?.split(',')?.filter { it.isNotBlank() }?.map { it.toLong() }.orEmpty(),
                    lastReportedAt = row.get("last_reported_at", Instant::class.java),
                    cheatCount = row.get("cheat_count", java.lang.Integer::class.java)!!.toInt(),
                    behaviorCount = row.get("behavior_count", java.lang.Integer::class.java)!!.toInt(),
                    catalogReceivedAt = row.get("catalog_received_at", Instant::class.java),
                    qaRunCount = row.get("qa_run_count", java.lang.Long::class.java)!!.toInt(),
                    latestRunId = row.get("latest_run_id", java.lang.Long::class.java)?.toLong(),
                    latestRunStatus = row.get("latest_run_status", String::class.java),
                    latestRunStartedAt = row.get("latest_run_started_at", Instant::class.java),
                    latestRunCompletedAt = row.get("latest_run_completed_at", Instant::class.java),
                    stepsPassed = row.get("steps_passed", java.lang.Long::class.java)?.toInt(),
                    stepsTotal = row.get("steps_total", java.lang.Long::class.java)?.toInt()
                )
            }
            .flow()
            .toList()

    private companion object {
        // jsonb_typeof 로 거른다 — jsonb_array_length 는 배열이 아닌 값에 오류를 던진다.
        const val SQL = """
            SELECT gb.id, gb.version, gb.label, gb.is_editor, gb.created_at,
                   inst.instance_ids, inst.last_reported_at,
                   CASE WHEN jsonb_typeof(gb.game_function_catalog -> 'cheats') = 'array'
                        THEN jsonb_array_length(gb.game_function_catalog -> 'cheats') ELSE 0 END AS cheat_count,
                   CASE WHEN jsonb_typeof(gb.game_function_catalog -> 'behaviors') = 'array'
                        THEN jsonb_array_length(gb.game_function_catalog -> 'behaviors') ELSE 0 END AS behavior_count,
                   gb.game_function_catalog_received_at AS catalog_received_at,
                   (SELECT COUNT(*) FROM qa_run qr WHERE qr.game_build_id = gb.id) AS qa_run_count,
                   lr.id AS latest_run_id, lr.status AS latest_run_status,
                   lr.started_at AS latest_run_started_at, lr.completed_at AS latest_run_completed_at,
                   steps.steps_passed, steps.steps_total
            FROM game_build gb
            LEFT JOIN LATERAL (
                SELECT STRING_AGG(gi.id::text, ',') AS instance_ids, MAX(gi.last_connected_at) AS last_reported_at
                FROM game_instance gi
                WHERE gi.last_game_build_id = gb.id AND gi.deleted_at IS NULL
            ) inst ON TRUE
            LEFT JOIN LATERAL (
                SELECT qr.id, qr.status, qr.started_at, qr.completed_at
                FROM qa_run qr
                WHERE qr.game_build_id = gb.id
                ORDER BY qr.started_at DESC, qr.id DESC
                LIMIT 1
            ) lr ON TRUE
            LEFT JOIN LATERAL (
                SELECT SUM(qt.steps_passed) AS steps_passed, SUM(qt.steps_total) AS steps_total
                FROM qa_try qt
                WHERE qt.qa_run_id = lr.id
            ) steps ON TRUE
            WHERE gb.project_id = :projectId
            ORDER BY gb.is_editor, gb.created_at DESC, gb.id DESC
        """
    }
}
