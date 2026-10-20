package kr.artel.orchestration.telemetry.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `ARTEL_TELEMETRY` 가 받는 두 값. */
private val CONSENT_VALUES = setOf("on", "off")

/**
 * 직접 설치한 서버가 하루 한 번 보내는 telemetry report 와, artel.kr 이 그것을 받는 receiver 의 설정.
 *
 * 보내는 쪽과 받는 쪽이 한 클래스에 있는 이유는 같은 서버 이미지가 둘 다 하기 때문이다. 직접 설치한 서버는
 * [endpoint] 를 채워 보내기만 하고, artel.kr 은 [endpoint] 를 비워 두고 받기만 한다.
 */
@ConfigurationProperties("artel.telemetry")
data class TelemetryProperties(
    /**
     * report 를 보낼 주소(`ARTEL_TELEMETRY_URL`). `POST {endpoint}` 로 보내고 `DELETE {endpoint}/{installId}` 로
     * 지운다. 기본값이 빈 문자열인 이유는 artel.kr 의 서버가 자기 자신에게 보내지 않게 하려는 것이다. 비어 있으면
     * 관리 화면의 telemetry 항목도 숨겨진다.
     */
    val endpoint: String = "",
    /**
     * 관리자가 아직 아무것도 고르지 않았을 때의 consent(`ARTEL_TELEMETRY`, `on` 또는 `off`). 관리 화면이나 첫 가입에서
     * 한 번 고르면 `platform_setting` 의 `telemetry.consent` 가 이 값보다 우선한다.
     */
    val consent: String = "off",
    /** report 한 번을 보내거나 지우는 요청의 응답 상한. */
    val sendTimeout: Duration = Duration.ofSeconds(10),
    /**
     * scheduler 주기. `@Scheduled` 가 빈을 읽지 못해 `TelemetryReportScheduler` 가 같은 키를 placeholder 로 다시
     * 읽는다. 기본값을 바꾸면 그쪽도 함께 바꾼다.
     */
    val intervalMillis: Long = 3_600_000,
    /** 기동 뒤 첫 tick 까지의 시간. [intervalMillis] 와 같은 이유로 scheduler 가 placeholder 로 다시 읽는다. */
    val initialDelayMillis: Long = 300_000,
    /** 밀린 날짜를 보낼 때 어제부터 거슬러 올라가는 최대 일수. receiver 가 오래 멈춰 있어도 이보다 오래된 날은 버린다. */
    val maxBackfillDays: Long = 7,
    /** `telemetry_error_event` 를 남겨 두는 일수. 이보다 오래된 행은 보내는 job 이 지운다. */
    val errorRetentionDays: Long = 14,
    val receiver: Receiver = Receiver()
) {
    init {
        require(consent.trim().lowercase() in CONSENT_VALUES) { "artel.telemetry.consent must be on or off" }
        require(!sendTimeout.isZero && !sendTimeout.isNegative) { "artel.telemetry.send-timeout must be positive" }
        require(intervalMillis > 0) { "artel.telemetry.interval-millis must be positive" }
        require(maxBackfillDays > 0) { "artel.telemetry.max-backfill-days must be positive" }
        require(errorRetentionDays > 0) { "artel.telemetry.error-retention-days must be positive" }
    }

    /** report 를 보낼 곳이 있는지. 없으면 보내지도, consent 를 묻지도 않는다. */
    val endpointConfigured: Boolean
        get() = endpoint.isNotBlank()

    /** 환경 변수가 정한 consent. */
    val consentFromEnvironment: Boolean
        get() = consent.trim().lowercase() == "on"

    /** `POST /api/telemetry/reports` 와 `DELETE /api/telemetry/reports/{installId}` 를 받는 쪽의 한도. */
    data class Receiver(
        /** 요청 본문 상한(바이트). 넘으면 413 이다. 하루치 report 는 보통 수 KB 다. */
        val maxBodyBytes: Int = 256 * 1024,
        /** 한 클라이언트 주소가 [window] 안에 보낼 수 있는 요청 수. 넘으면 429 다. */
        val requestsPerWindow: Int = 30,
        val window: Duration = Duration.ofHours(1),
        /** 메모리에 들고 있는 주소 수의 상한. 넘으면 창이 끝난 주소부터 지운다. */
        val maxTrackedAddresses: Int = 100_000
    ) {
        init {
            require(maxBodyBytes > 0) { "artel.telemetry.receiver.max-body-bytes must be positive" }
            require(requestsPerWindow > 0) { "artel.telemetry.receiver.requests-per-window must be positive" }
            require(!window.isZero && !window.isNegative) { "artel.telemetry.receiver.window must be positive" }
            require(maxTrackedAddresses > 0) { "artel.telemetry.receiver.max-tracked-addresses must be positive" }
        }
    }
}

/**
 * 이 서버 이미지의 버전(`ARTEL_VERSION`). 이미지 빌드가 넣고, 없으면 compose 의 image tag 를 쓴다.
 *
 * pom 의 `0.0.1-SNAPSHOT` 은 쓰지 않는다. 어느 설치에서나 같은 값이라 보내도 알 수 있는 것이 없고, 진짜 버전처럼
 * 읽혀 오히려 틀린 결론을 낸다. 비어 있으면 report 에 null 로 나간다.
 */
@ConfigurationProperties("artel")
data class ArtelVersionProperties(
    val version: String = ""
) {
    /** report 에 싣는 값. 비어 있으면 null 이다. */
    val reportedVersion: String?
        get() = version.trim().ifEmpty { null }
}
