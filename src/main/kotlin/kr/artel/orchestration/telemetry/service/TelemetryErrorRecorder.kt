package kr.artel.orchestration.telemetry.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.mono
import kr.artel.orchestration.telemetry.config.TelemetryProperties
import kr.artel.orchestration.telemetry.repository.TelemetryErrorEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** 남길 frame 의 class 이름 접두어. 라이브러리 frame 은 설치마다 같아 알려 주는 것이 없다. */
private const val OWN_PACKAGE_PREFIX = "kr.artel."

/**
 * `ApiExceptionHandler` 가 5xx 로 답한 예외를 `telemetry_error_event` 에 남긴다. consent 가 켜져 있을 때만 남긴다.
 *
 * 예외 message 와 cause 는 남기지 않는다. 남기는 것은 클래스 이름, 응답의 오류 code, `kr.artel` 아래 frame 최대
 * 10개(`class:method:line`)다.
 *
 * 응답을 기다리게 하지 않는다. 저장은 따로 구독해 돌리고, 실패하면 경고 한 줄만 남긴다 — 오류 응답을 쓰는 길에서
 * 또 하나의 오류를 내면 원래 응답이 가려진다.
 */
@Component
class TelemetryErrorRecorder(
    private val properties: TelemetryProperties,
    private val consentService: TelemetryConsentService,
    private val errorEventRepository: TelemetryErrorEventRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(TelemetryErrorRecorder::class.java)

    fun record(error: Throwable, code: String) {
        if (!properties.endpointConfigured) return
        val exceptionType = error.javaClass.name
        val frames = ownFrames(error)
        val createdAt = Instant.now(clock)
        mono {
            if (consentService.isEnabled()) {
                errorEventRepository.insert(exceptionType, code, objectMapper.writeValueAsString(frames), createdAt)
            }
        }.subscribe(
            { },
            { failure -> logger.warn("Telemetry error event was not stored: {}", failure.javaClass.simpleName) }
        )
    }

    private fun ownFrames(error: Throwable): List<String> =
        error.stackTrace.asSequence()
            .filter { it.className.startsWith(OWN_PACKAGE_PREFIX) }
            .map { "${it.className}:${it.methodName}:${maxOf(it.lineNumber, 0)}" }
            .take(MAX_TELEMETRY_FRAMES)
            .toList()
}
