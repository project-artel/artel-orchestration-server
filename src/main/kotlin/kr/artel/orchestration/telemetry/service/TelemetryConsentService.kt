package kr.artel.orchestration.telemetry.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.settings.repository.PlatformSettingRepository
import kr.artel.orchestration.telemetry.config.TelemetryProperties
import kr.artel.orchestration.telemetry.dto.CONSENT_SOURCE_ENVIRONMENT
import kr.artel.orchestration.telemetry.dto.CONSENT_SOURCE_SETTING
import kr.artel.orchestration.telemetry.dto.TelemetryStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** 관리자가 고른 consent. 평문 JSON(`{"enabled":true}`)이다. 행이 있으면 `ARTEL_TELEMETRY` 보다 우선한다. */
const val TELEMETRY_CONSENT_SETTING = "telemetry.consent"

/** 이 설치의 무작위 UUID. consent 가 처음 켜질 때 만든다. 평문이다. */
const val TELEMETRY_INSTALL_ID_SETTING = "telemetry.install_id"

/** 마지막으로 보낸 report 의 UTC 날짜(`2026-10-19`). 평문이다. */
const val TELEMETRY_LAST_SENT_DATE_SETTING = "telemetry.last_sent_date"

/** `telemetry.consent` 에 저장하는 값. */
data class StoredTelemetryConsent(val enabled: Boolean)

/**
 * telemetry consent 와 그에 딸린 두 값(install id, 마지막 전송 날짜)을 `platform_setting` 에 둔다. 모양은
 * `AnthropicKeyService` 를 따른다.
 *
 * **적용되는 consent.** `telemetry.consent` 행이 있으면 그 값이고, 없으면 `ARTEL_TELEMETRY` 다. 관리 화면이나 첫
 * 가입에서 한 번 고르면 행이 생기고, 그 뒤로는 환경 변수를 바꿔도 따르지 않는다.
 *
 * **consent 가 켜질 때 마지막 전송 날짜를 그제로 당긴다.** 다음 tick 에 어제 report 하나만 나간다. 관리 화면의
 * 미리보기가 보여 준 것이 바로 그 report 다. consent 가 꺼져 있던 동안의 날들은 보내지 않는다 — 날짜를 당기지
 * 않으면 scheduler 가 최대 7일 전까지 거슬러 그 날들을 보낸다.
 */
@Service
class TelemetryConsentService(
    private val platformSettingRepository: PlatformSettingRepository,
    private val properties: TelemetryProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(TelemetryConsentService::class.java)

    suspend fun status(): TelemetryStatus {
        val stored = storedConsent()
        return TelemetryStatus(
            available = properties.endpointConfigured,
            enabled = stored?.enabled ?: properties.consentFromEnvironment,
            source = if (stored != null) CONSENT_SOURCE_SETTING else CONSENT_SOURCE_ENVIRONMENT,
            installId = installId(),
            lastSentDate = lastSentDate()
        )
    }

    /** 지금 적용되는 consent. 보낼 곳이 있는지는 보지 않는다. */
    suspend fun isEnabled(): Boolean = storedConsent()?.enabled ?: properties.consentFromEnvironment

    suspend fun installId(): String? =
        platformSettingRepository.findById(TELEMETRY_INSTALL_ID_SETTING)?.settingValue

    suspend fun lastSentDate(): LocalDate? {
        val stored = platformSettingRepository.findById(TELEMETRY_LAST_SENT_DATE_SETTING) ?: return null
        return runCatching { LocalDate.parse(stored.settingValue) }.getOrNull()
    }

    /**
     * consent 를 저장한다. [actorUserId] 는 고른 관리자이고 `updated_by` 에 남는다.
     *
     * 켤 때 install id 가 없으면 만들고, 이미 켜져 있지 않았다면 마지막 전송 날짜를 그제로 당긴다(클래스 설명). 끌 때는
     * install id 와 마지막 전송 날짜를 그대로 둔다. 다시 켜면 같은 install id 로 이어 보낸다. 받은 report 를 지우는
     * 끄기는 [forgetInstall] 이 따로 한다.
     */
    suspend fun saveConsent(enabled: Boolean, actorUserId: Long) {
        val wasEnabled = isEnabled()
        upsertPlain(TELEMETRY_CONSENT_SETTING, objectMapper.writeValueAsString(StoredTelemetryConsent(enabled)), actorUserId)
        if (enabled) {
            ensureInstallId(actorUserId)
            if (!wasEnabled) startSendingFromYesterday(actorUserId)
        }
        logger.info("Telemetry consent set to {} by app_user {}", if (enabled) "on" else "off", actorUserId)
    }

    /** consent 를 끄고 install id 와 마지막 전송 날짜를 지운다. 다시 켜면 새 install id 로 시작한다. */
    suspend fun forgetInstall(actorUserId: Long) {
        upsertPlain(TELEMETRY_CONSENT_SETTING, objectMapper.writeValueAsString(StoredTelemetryConsent(false)), actorUserId)
        platformSettingRepository.deleteById(TELEMETRY_INSTALL_ID_SETTING)
        platformSettingRepository.deleteById(TELEMETRY_LAST_SENT_DATE_SETTING)
        logger.info("Telemetry consent turned off and install id cleared by app_user {}", actorUserId)
    }

    /**
     * install id 를 돌려준다. 없으면 만든다. 환경 변수로 consent 가 켜진 설치는 scheduler 가 처음 보낼 때 여기서 만들고,
     * 그때 [actorUserId] 는 null 이다.
     */
    suspend fun ensureInstallId(actorUserId: Long?): String {
        installId()?.let { return it }
        platformSettingRepository.insertIfAbsent(
            settingKey = TELEMETRY_INSTALL_ID_SETTING,
            settingValue = UUID.randomUUID().toString(),
            updatedBy = actorUserId,
            updatedAt = Instant.now(clock)
        )
        // 동시에 만든 쪽이 먼저 넣었으면 그 값이 남아 있다. 넣은 값이 아니라 남은 값을 읽는다.
        return requireNotNull(installId()) { "telemetry.install_id was not stored" }
    }

    /** scheduler 가 보낸 날짜를 남긴다. 사람이 쓴 값이 아니라 `updated_by` 는 null 이다. */
    suspend fun markSent(reportDate: LocalDate) {
        upsertPlain(TELEMETRY_LAST_SENT_DATE_SETTING, reportDate.toString(), actorUserId = null)
    }

    private suspend fun startSendingFromYesterday(actorUserId: Long) {
        val dayBeforeYesterday = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(2)
        val lastSent = lastSentDate()
        if (lastSent == null || lastSent.isBefore(dayBeforeYesterday)) {
            upsertPlain(TELEMETRY_LAST_SENT_DATE_SETTING, dayBeforeYesterday.toString(), actorUserId)
        }
    }

    private suspend fun storedConsent(): StoredTelemetryConsent? {
        val stored = platformSettingRepository.findById(TELEMETRY_CONSENT_SETTING) ?: return null
        return try {
            objectMapper.readValue(stored.settingValue, StoredTelemetryConsent::class.java)
        } catch (error: Exception) {
            // 읽을 수 없는 행은 고른 적 없는 것으로 본다. 켜진 것으로 읽어 보내는 것보다 안전하다.
            logger.warn("Stored telemetry consent could not be read; falling back to ARTEL_TELEMETRY")
            null
        }
    }

    private suspend fun upsertPlain(settingKey: String, settingValue: String, actorUserId: Long?) {
        platformSettingRepository.upsert(
            settingKey = settingKey,
            settingValue = settingValue,
            encrypted = false,
            updatedBy = actorUserId,
            updatedAt = Instant.now(clock)
        )
    }
}
