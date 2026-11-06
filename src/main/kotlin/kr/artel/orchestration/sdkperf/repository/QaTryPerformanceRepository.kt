package kr.artel.orchestration.sdkperf.repository

import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * 성능 표본 한 줄과 그 시각의 게임 frame.
 *
 * @property frame 표본을 받은 시각 이전에 이 try 로 들어온 마지막 `PULSE` · `ACTION_RESULT` 의 `frame`. 아직 그런 메시지가
 *   없었으면 null 이다
 */
data class QaTryPerformanceRow(
    val receivedAt: Instant,
    val frame: Long?,
    val frameCount: Int,
    val sampledMs: Double,
    val meanMs: Double,
    val hitchCount: Int,
    val cpuPercent: Double?,
    val workingSetBytes: Long?
)

@Repository
class QaTryPerformanceRepository(private val db: DatabaseClient) {

    /**
     * [instanceId] 의 표본 중 [from] 부터 [to] 까지 받은 것을 시각 순으로, 마지막 [limit] 개만 읽는다.
     *
     * 원본 표본을 직접 읽는 유일한 경로다. 런 상세 조회처럼 런 전체를 훑지 않고, 시각 구간과 개수로 묶은 최근 표본만
     * 읽는다 — `idx_sdk_performance_sample_instance_received` 로 그 구간만 본다. frame 은 표본마다 `qa_log` 에서
     * 한 줄을 찾는다(`idx_qa_log_try_id_desc`).
     */
    suspend fun findSamples(qaTryId: Long, instanceId: Long, from: Instant, to: Instant, limit: Int): List<QaTryPerformanceRow> =
        db.sql(
            """
            SELECT recent.* FROM (
              SELECT s.received_at, s.frame_count, s.sampled_ms, s.mean_ms, s.hitch_count,
                     s.process_cpu_percent, s.process_working_set_bytes,
                     (SELECT (l.payload->>'frame')::BIGINT FROM qa_log l
                       WHERE l.qa_try_id = :qaTryId AND l.direction = 'SDK_TO_ORCHE'
                         AND l.type IN ('PULSE', 'ACTION_RESULT') AND l.created_at <= s.received_at
                         AND jsonb_typeof(l.payload->'frame') = 'number'
                       ORDER BY l.id DESC LIMIT 1) AS frame
                FROM sdk_performance_sample s
               WHERE s.game_instance_id = :instanceId AND s.received_at >= :from AND s.received_at <= :to
               ORDER BY s.received_at DESC
               LIMIT :limit
            ) recent
            ORDER BY recent.received_at
            """
        )
            .bind("qaTryId", qaTryId)
            .bind("instanceId", instanceId)
            .bind("from", from)
            .bind("to", to)
            .bind("limit", limit)
            .map { row, _ ->
                QaTryPerformanceRow(
                    receivedAt = row.get("received_at", Instant::class.java)!!,
                    frame = row.get("frame", Long::class.javaObjectType),
                    frameCount = row.get("frame_count", Int::class.javaObjectType)!!,
                    sampledMs = row.get("sampled_ms", Double::class.javaObjectType)!!,
                    meanMs = row.get("mean_ms", Double::class.javaObjectType)!!,
                    hitchCount = row.get("hitch_count", Int::class.javaObjectType)!!,
                    cpuPercent = row.get("process_cpu_percent", Double::class.javaObjectType),
                    workingSetBytes = row.get("process_working_set_bytes", Long::class.javaObjectType)
                )
            }
            .flow()
            .toList()
}
