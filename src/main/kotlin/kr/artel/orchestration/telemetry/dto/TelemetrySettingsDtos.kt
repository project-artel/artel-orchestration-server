package kr.artel.orchestration.telemetry.dto

import java.time.LocalDate

/** [TelemetryStatus.source] 값. 관리 화면이나 첫 가입에서 고른 값이 `platform_setting` 에 있다. */
const val CONSENT_SOURCE_SETTING = "setting"

/** [TelemetryStatus.source] 값. 아직 아무도 고르지 않아 `ARTEL_TELEMETRY` 를 따른다. */
const val CONSENT_SOURCE_ENVIRONMENT = "environment"

/** `GET /api/admin/settings/telemetry` 와 `PUT` 의 응답. */
data class TelemetryStatus(
    /** `ARTEL_TELEMETRY_URL` 이 있는지. false 면 관리 화면은 이 항목을 숨긴다. */
    val available: Boolean,
    /** 지금 적용되는 consent. */
    val enabled: Boolean,
    /** [CONSENT_SOURCE_SETTING] 또는 [CONSENT_SOURCE_ENVIRONMENT]. */
    val source: String,
    /** consent 가 처음 켜질 때 만든 UUID. 아직 없으면 null 이다. */
    val installId: String?,
    /** 마지막으로 보낸 report 의 날짜. 보낸 적이 없으면 null 이다. */
    val lastSentDate: LocalDate?
)

/**
 * `PUT /api/admin/settings/telemetry` 요청 본문.
 *
 * [deleteSentData] 가 true 면 consent 를 끄기 전에 receiver 에 `DELETE /api/telemetry/reports/{installId}` 를 보내
 * 이미 보낸 report 를 지운다. 그 요청이 실패하면 아무것도 바꾸지 않고 503 이다.
 */
data class UpdateTelemetryRequest(
    val enabled: Boolean,
    val deleteSentData: Boolean? = null
)
