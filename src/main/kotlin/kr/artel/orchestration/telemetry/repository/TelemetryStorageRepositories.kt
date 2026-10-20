package kr.artel.orchestration.telemetry.repository

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `telemetry_error_event`(V120). orchestration 이 5xx 로 답한 예외를 telemetry report 에 싣기 위해 남긴다. */
@Repository
class TelemetryErrorEventRepository(
    private val databaseClient: DatabaseClient
) {
    suspend fun insert(exceptionType: String, code: String, framesJson: String, createdAt: Instant) {
        databaseClient.sql(
            """
            INSERT INTO telemetry_error_event (exception_type, code, frames, created_at)
            VALUES (:exceptionType, :code, :frames, :createdAt)
            """
        )
            .bind("exceptionType", exceptionType)
            .bind("code", code)
            .bind("frames", Json.of(framesJson))
            .bind("createdAt", createdAt)
            .fetch().rowsUpdated().awaitSingle()
    }

    /** [cutoff] 보다 먼저 생긴 행을 지우고 지운 수를 돌려준다. */
    suspend fun deleteOlderThan(cutoff: Instant): Long =
        databaseClient.sql("DELETE FROM telemetry_error_event WHERE created_at < :cutoff")
            .bind("cutoff", cutoff)
            .fetch().rowsUpdated().awaitSingle()

    suspend fun deleteAll(): Long =
        databaseClient.sql("DELETE FROM telemetry_error_event")
            .fetch().rowsUpdated().awaitSingle()
}

/** `telemetry_report`(V119). artel.kr 의 receiver 가 받은 report 를 둔다. */
@Repository
class TelemetryReportRepository(
    private val databaseClient: DatabaseClient
) {
    /** 같은 설치의 같은 날이 이미 있으면 덮어쓴다. 보내는 쪽은 응답을 못 받으면 같은 날을 다시 보낸다. */
    suspend fun upsert(installId: UUID, reportDate: LocalDate, schemaVersion: Int, reportJson: String, receivedAt: Instant) {
        databaseClient.sql(
            """
            INSERT INTO telemetry_report (install_id, report_date, schema_version, report, received_at)
            VALUES (:installId, :reportDate, :schemaVersion, :report, :receivedAt)
            ON CONFLICT (install_id, report_date) DO UPDATE
               SET schema_version = EXCLUDED.schema_version,
                   report = EXCLUDED.report,
                   received_at = EXCLUDED.received_at
            """
        )
            .bind("installId", installId)
            .bind("reportDate", reportDate)
            .bind("schemaVersion", schemaVersion)
            .bind("report", Json.of(reportJson))
            .bind("receivedAt", receivedAt)
            .fetch().rowsUpdated().awaitSingle()
    }

    /** 그 설치가 보낸 report 를 모두 지우고 지운 수를 돌려준다. */
    suspend fun deleteByInstallId(installId: UUID): Long =
        databaseClient.sql("DELETE FROM telemetry_report WHERE install_id = :installId")
            .bind("installId", installId)
            .fetch().rowsUpdated().awaitSingle()
}
