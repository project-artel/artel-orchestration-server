package kr.artel.orchestration.telemetry.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.telemetry.service.TelemetryReportSender
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * telemetry report 를 한 시간마다 보내는 scheduler. 무엇을 보낼지는 [TelemetryReportSender] 가 정한다.
 *
 * 다른 scheduler 와 달리 `@ConditionalOnProperty` 로 끄지 않는다. consent 는 관리 화면에서 서버를 다시 띄우지 않고
 * 바뀌므로, 켜졌는지는 tick 마다 DB 를 보고 판단해야 한다. 보낼 곳(`ARTEL_TELEMETRY_URL`)이 비어 있으면 tick 은 DB 도
 * 읽지 않고 끝난다.
 *
 * `fixedDelay` 와 `runBlocking` 은 `TestCaseEmbeddingScheduler` 와 같은 이유다. tick 이 겹치면 같은 날을 두 번 보낸다.
 * 첫 tick 은 기동 5분 뒤다. 서버를 자주 다시 띄우는 설치도 한 시간을 기다리지 않고 보낸다.
 *
 * 주기 placeholder 의 기본값은 [TelemetryProperties.intervalMillis] 와 같아야 한다.
 */
@Configuration
@EnableScheduling
class TelemetryReportScheduler(
    private val sender: TelemetryReportSender
) {
    private val logger = LoggerFactory.getLogger(TelemetryReportScheduler::class.java)

    @Scheduled(
        fixedDelayString = "\${artel.telemetry.interval-millis:3600000}",
        initialDelayString = "\${artel.telemetry.initial-delay-millis:300000}"
    )
    fun tick() {
        runBlocking {
            try {
                sender.sendDueReports()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("Telemetry tick failed: {}", error.javaClass.simpleName, error)
            }
        }
    }
}
