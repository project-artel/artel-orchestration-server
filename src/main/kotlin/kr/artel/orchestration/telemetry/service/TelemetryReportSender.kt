package kr.artel.orchestration.telemetry.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.telemetry.config.TelemetryProperties
import kr.artel.orchestration.telemetry.dto.TelemetryReport
import kr.artel.orchestration.telemetry.repository.TelemetryErrorEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 밀린 날의 telemetry report 를 보낸다. `TelemetryReportScheduler` 가 한 시간마다 부른다.
 *
 * 보낼 곳이 없거나 consent 가 꺼져 있으면 아무것도 읽지도 보내지도 않는다. 둘 다 있으면 마지막으로 보낸 날의 다음
 * 날부터 어제까지를 오래된 날부터 하나씩 보낸다. 어제보다 [TelemetryProperties.maxBackfillDays] 일 넘게 오래된 날은
 * 건너뛴다. 하나가 실패하면 그 자리에서 멈추고 다음 tick 에 같은 날부터 다시 보낸다 — 순서를 지켜야 마지막 전송
 * 날짜 하나로 어디까지 보냈는지 말할 수 있다.
 *
 * 마지막 전송 날짜가 없으면 그제까지 보낸 것으로 본다. 환경 변수로 consent 를 켠 설치는 첫 tick 에 어제 하나만 보낸다.
 */
@Service
class TelemetryReportSender(
    private val properties: TelemetryProperties,
    private val consentService: TelemetryConsentService,
    private val reportBuilder: TelemetryReportBuilder,
    private val receiverClient: TelemetryReceiverClient,
    private val errorEventRepository: TelemetryErrorEventRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(TelemetryReportSender::class.java)

    /** 보낸 report 수를 돌려준다. */
    suspend fun sendDueReports(): Int {
        if (!properties.endpointConfigured) return 0
        deleteExpiredErrorEvents()
        if (!consentService.isEnabled()) return 0

        val installId = consentService.ensureInstallId(actorUserId = null)
        val yesterday = today().minusDays(1)
        val lastSent = consentService.lastSentDate() ?: yesterday.minusDays(1)
        val oldestAllowed = yesterday.minusDays(properties.maxBackfillDays - 1)
        var reportDate = maxOf(lastSent.plusDays(1), oldestAllowed)

        var sent = 0
        while (!reportDate.isAfter(yesterday)) {
            if (!send(reportBuilder.build(reportDate, installId))) {
                logger.warn("Telemetry report for {} was not sent; retrying on the next tick", reportDate)
                return sent
            }
            consentService.markSent(reportDate)
            sent += 1
            reportDate = reportDate.plusDays(1)
        }
        return sent
    }

    /** 관리 화면의 미리보기. 다음 tick 이 보낼 어제 report 와 같은 것을 만든다. 아무것도 쓰지 않는다. */
    suspend fun previewYesterday(): TelemetryReport =
        reportBuilder.build(today().minusDays(1), consentService.installId())

    private suspend fun send(report: TelemetryReport): Boolean {
        val body = objectMapper.writeValueAsBytes(report)
        if (!receiverClient.send(body)) return false
        logger.info("Telemetry report for {} sent ({} bytes)", report.install.reportDate, body.size)
        return true
    }

    private suspend fun deleteExpiredErrorEvents() {
        val cutoff = clock.instant().minus(Duration.ofDays(properties.errorRetentionDays))
        val deleted = errorEventRepository.deleteOlderThan(cutoff)
        if (deleted > 0) logger.info("Deleted {} telemetry error events older than {}", deleted, cutoff)
    }

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
}
