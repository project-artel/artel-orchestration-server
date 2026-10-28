package kr.artel.orchestration.qa.review

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.storage.PresignedUpload
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.awaitSingleOrNull
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class VideoSegmentRequest(val startedAt: Instant, val endedAt: Instant, val sizeBytes: Long)
data class VideoGapRequest(val id: UUID, val startedAt: Instant, val endedAt: Instant, val reason: String)
data class VideoSegment(val id: UUID, val startedAt: Instant, val endedAt: Instant, val state: String, val url: String?)
data class VideoGap(val startedAt: Instant, val endedAt: Instant, val reason: String)
data class QaRecording(val id: UUID, val instanceId: String, val startedAt: Instant, val endedAt: Instant?, val expiresAt: Instant?, val error: String?)
data class QaRecordingPlayback(val recording: QaRecording?, val segments: List<VideoSegment>, val gaps: List<VideoGap>)

@Service
class QaRecordingStore(private val database: DatabaseClient, private val storage: DocumentStorage, private val access: ProjectAccessService) {
    suspend fun active(): List<QaRecording> {
        // run이 시나리오를 바꿔도 같은 recording을 유지한다. 단독 try는 별도 소유 키를 쓴다.
        database.sql("""
            INSERT INTO qa_recording(id, owner_key, qa_run_id, qa_try_id, project_id, game_instance_id, started_at)
            SELECT gen_random_uuid(), 'run:' || r.id, r.id, NULL, tr.project_id, r.game_instance_id, r.started_at
            FROM qa_run r JOIN test_run tr ON tr.id = r.test_run_id WHERE r.status IN ('STARTING','RUNNING')
            UNION ALL
            SELECT gen_random_uuid(), 'try:' || t.id, NULL, t.id, s.project_id, t.game_instance_id, t.started_at
            FROM qa_try t JOIN test_scenario s ON s.id = t.test_scenario_id
            WHERE t.qa_run_id IS NULL AND t.status IN ('STARTING','RUNNING') ON CONFLICT(owner_key) DO NOTHING
        """).fetch().awaitRowsUpdated()
        database.sql("""
            UPDATE qa_recording v SET ended_at = COALESCE(r.completed_at, t.completed_at) + interval '5 seconds',
                expires_at = COALESCE(r.completed_at, t.completed_at) + interval '30 days'
            FROM qa_recording source LEFT JOIN qa_run r ON r.id = source.qa_run_id LEFT JOIN qa_try t ON t.id = source.qa_try_id
            WHERE v.id = source.id AND v.ended_at IS NULL AND COALESCE(r.completed_at, t.completed_at) IS NOT NULL
        """).fetch().awaitRowsUpdated()
        return database.sql("SELECT * FROM qa_recording WHERE ended_at IS NULL OR ended_at > now()")
            .map { row, _ -> recording(row) }.flow().toList()
    }

    suspend fun error(id: UUID, error: String?) {
        val statement = database.sql("UPDATE qa_recording SET error = :error WHERE id = :id").bind("id", id)
        (if (error == null) statement.bindNull("error", String::class.java) else statement.bind("error", error.take(1000))).fetch().awaitRowsUpdated()
    }

    suspend fun reserve(recordingId: UUID, id: UUID, request: VideoSegmentRequest): PresignedUpload {
        val recording = find(recordingId) ?: throw NotFoundException()
        if (request.endedAt.plus(Duration.ofDays(30)).isBefore(Instant.now()) || recording.expiresAt?.isBefore(Instant.now()) == true || request.sizeBytes !in 1..33554432L ||
            request.endedAt < request.startedAt || Duration.between(request.startedAt, request.endedAt) > Duration.ofMinutes(2))
            throw BadRequestException("영상 구간 또는 크기가 올바르지 않습니다.")
        val key = "qa-video/$recordingId/$id.mp4"
        database.sql("""
            INSERT INTO qa_video_segment(id, recording_id, started_at, ended_at, object_key, size_bytes)
            VALUES(:id, :recording, :start, :end, :key, :size) ON CONFLICT(id) DO NOTHING
        """).bind("id", id).bind("recording", recordingId).bind("start", request.startedAt).bind("end", request.endedAt)
            .bind("key", key).bind("size", request.sizeBytes).fetch().awaitRowsUpdated()
        val stored = database.sql("SELECT object_key, size_bytes FROM qa_video_segment WHERE id = :id AND recording_id = :recording")
            .bind("id", id).bind("recording", recordingId).map { row, _ -> row.get("object_key", String::class.java)!! to (row.get("size_bytes") as Number).toLong() }
            .awaitSingleOrNull() ?: throw BadRequestException("다른 녹화에서 사용한 구간 ID입니다.")
        if (stored.first != key || stored.second != request.sizeBytes) throw BadRequestException("영상 구간 정보가 다릅니다.")
        return storage.presignUpload(key, "video/mp4", request.sizeBytes)
    }

    suspend fun complete(recordingId: UUID, id: UUID) {
        val segment = database.sql("SELECT object_key, size_bytes FROM qa_video_segment WHERE id = :id AND recording_id = :recording")
            .bind("id", id).bind("recording", recordingId).map { row, _ -> row.get("object_key", String::class.java)!! to (row.get("size_bytes") as Number).toLong() }
            .awaitSingleOrNull() ?: throw NotFoundException()
        val head = storage.head(segment.first).awaitSingleOrNull() ?: throw BadRequestException("영상 업로드를 확인할 수 없습니다.")
        if (head.sizeBytes != segment.second) throw BadRequestException("영상 크기가 일치하지 않습니다.")
        database.sql("UPDATE qa_video_segment SET state = 'READY' WHERE id = :id AND state = 'UPLOADING'")
            .bind("id", id).fetch().awaitRowsUpdated()
    }

    suspend fun gap(id: UUID, request: VideoGapRequest) {
        find(id) ?: throw NotFoundException()
        if (request.endedAt < request.startedAt || request.reason.length > 1000) throw BadRequestException("누락 구간이 올바르지 않습니다.")
        database.sql("""INSERT INTO qa_video_gap(id, recording_id, started_at, ended_at, reason)
            VALUES(:id, :recording, :start, :end, :reason) ON CONFLICT(id) DO NOTHING""")
            .bind("id", request.id).bind("recording", id).bind("start", request.startedAt).bind("end", request.endedAt)
            .bind("reason", request.reason).fetch().awaitRowsUpdated()
    }

    suspend fun playback(projectId: Long, tryId: Long, userId: Long): QaRecordingPlayback {
        access.requireMember(projectId, userId)
        val owner = database.sql("""SELECT COALESCE('run:' || t.qa_run_id, 'try:' || t.id) AS owner FROM qa_try t
            JOIN test_scenario s ON s.id = t.test_scenario_id WHERE t.id = :try AND s.project_id = :project""")
            .bind("try", tryId).bind("project", projectId).map { row, _ -> row.get("owner", String::class.java)!! }.awaitSingleOrNull() ?: throw NotFoundException()
        val recording = database.sql("SELECT * FROM qa_recording WHERE owner_key = :owner").bind("owner", owner)
            .map { row, _ -> recording(row) }.awaitSingleOrNull() ?: return QaRecordingPlayback(null, emptyList(), emptyList())
        val expired = recording.expiresAt?.isBefore(Instant.now()) == true
        val segments = database.sql("SELECT * FROM qa_video_segment WHERE recording_id = :id ORDER BY started_at, id")
            .bind("id", recording.id).map { row, _ ->
                val state = if (expired || row.get("ended_at", Instant::class.java)!!.plus(Duration.ofDays(30)).isBefore(Instant.now())) "EXPIRED" else row.get("state", String::class.java)!!
                val url = if (state == "READY") storage.presignDownload(row.get("object_key", String::class.java)!!, "qa.mp4", Duration.ofMinutes(5)).url else null
                VideoSegment(row.get("id", UUID::class.java)!!, row.get("started_at", Instant::class.java)!!,
                    row.get("ended_at", Instant::class.java)!!, state, url)
            }.flow().toList()
        val gaps = database.sql("SELECT * FROM qa_video_gap WHERE recording_id = :id ORDER BY started_at")
            .bind("id", recording.id).map { row, _ -> VideoGap(row.get("started_at", Instant::class.java)!!,
                row.get("ended_at", Instant::class.java)!!, row.get("reason", String::class.java)!!) }.flow().toList()
        val coverageEnd = recording.endedAt ?: Instant.now().minusSeconds(120)
        return QaRecordingPlayback(recording, segments, gaps + missingVideoIntervals(recording.startedAt, coverageEnd, segments))
    }

    suspend fun expire() {
        val expired = database.sql("""SELECT s.id, s.object_key FROM qa_video_segment s
            WHERE s.ended_at < now() - interval '30 days' AND s.state <> 'EXPIRED' ORDER BY s.ended_at LIMIT 100""")
            .map { row, _ -> row.get("id", UUID::class.java)!! to row.get("object_key", String::class.java)!! }.flow().toList()
        for ((id, key) in expired) {
            storage.delete(key).awaitSingleOrNull()
            database.sql("UPDATE qa_video_segment SET state = 'EXPIRED' WHERE id = :id").bind("id", id).fetch().awaitRowsUpdated()
        }
    }

    private suspend fun find(id: UUID) = database.sql("SELECT * FROM qa_recording WHERE id = :id").bind("id", id)
        .map { row, _ -> recording(row) }.awaitSingleOrNull()

    companion object {
        private fun recording(row: Row) = QaRecording(row.get("id", UUID::class.java)!!, row.get("game_instance_id")!!.toString(),
            row.get("started_at", Instant::class.java)!!, row.get("ended_at", Instant::class.java), row.get("expires_at", Instant::class.java), row.get("error", String::class.java))
    }
}

@RestController
class QaRecordingController(private val recordings: QaRecordingStore) {
    @GetMapping("/api/projects/{projectId}/qa-evaluation/tries/{tryId}/recording")
    suspend fun playback(@PathVariable projectId: Long, @PathVariable tryId: Long, @CurrentUserId userId: Long) = recordings.playback(projectId, tryId, userId)

    @PostMapping("/internal/qa/recordings/{recordingId}/segments/{id}")
    suspend fun reserve(@PathVariable recordingId: UUID, @PathVariable id: UUID, @RequestBody request: VideoSegmentRequest) = recordings.reserve(recordingId, id, request)

    @PostMapping("/internal/qa/recordings/{recordingId}/segments/{id}/complete")
    suspend fun complete(@PathVariable recordingId: UUID, @PathVariable id: UUID) = recordings.complete(recordingId, id)

    @PostMapping("/internal/qa/recordings/{recordingId}/gaps")
    suspend fun gap(@PathVariable recordingId: UUID, @RequestBody request: VideoGapRequest) = recordings.gap(recordingId, request)
}
