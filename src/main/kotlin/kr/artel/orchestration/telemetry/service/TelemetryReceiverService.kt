package kr.artel.orchestration.telemetry.service

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.telemetry.dto.TELEMETRY_SCHEMA_VERSION
import kr.artel.orchestration.telemetry.dto.TelemetryReport
import kr.artel.orchestration.telemetry.repository.TelemetryReportRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** 받는 report 날짜의 과거 쪽 한도(일). 보내는 쪽은 7일까지만 거슬러 보내므로 그보다 넉넉히 둔다. */
private const val OLDEST_ACCEPTED_REPORT_DAYS = 30L

/**
 * artel.kr 이 직접 설치한 서버의 telemetry report 를 받아 `telemetry_report` 에 둔다.
 *
 * report 는 보내는 쪽과 같은 [TelemetryReport] 타입으로 읽는다. 모르는 필드가 있거나 필수 필드가 빠졌거나 타입이 다르면
 * 400 이다. 저장하는 것은 받은 바이트가 아니라 읽은 객체를 다시 직렬화한 것이라, 타입에 없는 값은 저장되지 않는다.
 */
@Service
class TelemetryReceiverService(
    private val reportRepository: TelemetryReportRepository,
    objectMapper: ObjectMapper,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(TelemetryReceiverService::class.java)

    private val writer = objectMapper.writer()

    private val reader: ObjectReader = objectMapper.readerFor(TelemetryReport::class.java)
        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    suspend fun receive(body: ByteArray) {
        val report = parse(body)
        if (report.schemaVersion != TELEMETRY_SCHEMA_VERSION) {
            throw InvalidTelemetryReportException("schemaVersion 은 ${TELEMETRY_SCHEMA_VERSION} 이어야 합니다.")
        }
        val installId = parseInstallId(report.install.installId)
        val reportDate = report.install.reportDate
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        if (reportDate.isAfter(today.plusDays(1)) || reportDate.isBefore(today.minusDays(OLDEST_ACCEPTED_REPORT_DAYS))) {
            throw InvalidTelemetryReportException("reportDate 가 받을 수 있는 범위 밖입니다.")
        }
        reportRepository.upsert(
            installId = installId,
            reportDate = reportDate,
            schemaVersion = report.schemaVersion,
            reportJson = writer.writeValueAsString(report),
            receivedAt = Instant.now(clock)
        )
        logger.info("Telemetry report for {} received ({} bytes)", reportDate, body.size)
    }

    /** 그 설치가 보낸 report 를 모두 지운다. 지울 것이 없어도 성공이다 — 있었는지를 알려 주지 않는다. */
    suspend fun forget(installId: String) {
        val deleted = reportRepository.deleteByInstallId(parseInstallId(installId))
        logger.info("Telemetry reports deleted on request ({} rows)", deleted)
    }

    private fun parse(body: ByteArray): TelemetryReport =
        try {
            reader.readValue(body)
        } catch (error: JacksonException) {
            // 어느 필드가 틀렸는지는 알려 주지 않는다. 보내는 쪽은 우리 서버이고, 틀렸다면 버전이 맞지 않는 것이다.
            throw InvalidTelemetryReportException("report 모양이 schemaVersion ${TELEMETRY_SCHEMA_VERSION} 과 맞지 않습니다.")
        }

    private fun parseInstallId(value: String?): UUID =
        value?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.takeIf { it.toString() == value.lowercase() }
            ?: throw InvalidTelemetryReportException("installId 는 UUID 여야 합니다.")
}

class InvalidTelemetryReportException(message: String) :
    BadRequestException(message, code = "invalid_telemetry_report")
