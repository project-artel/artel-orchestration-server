package kr.artel.orchestration.telemetry.controller

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.telemetry.dto.TelemetryReport
import kr.artel.orchestration.telemetry.dto.TelemetryStatus
import kr.artel.orchestration.telemetry.dto.UpdateTelemetryRequest
import kr.artel.orchestration.telemetry.service.TelemetryReportSender
import kr.artel.orchestration.telemetry.service.TelemetrySettingsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 관리 화면의 telemetry 항목. ADMIN 만 부른다. */
@RestController
@RequestMapping("/api/admin/settings/telemetry")
class AdminTelemetrySettingsController(
    private val settingsService: TelemetrySettingsService,
    private val reportSender: TelemetryReportSender,
    private val platformAccessService: PlatformAccessService
) {
    @GetMapping
    suspend fun telemetryStatus(@CurrentUserId userId: Long): TelemetryStatus {
        platformAccessService.requireAdmin(userId)
        return settingsService.status()
    }

    /**
     * consent 를 바꾼다. `ARTEL_TELEMETRY_URL` 이 없는 설치에서 켜면 409 `telemetry_unavailable` 이다.
     * `deleteSentData` 로 지우다 receiver 가 실패하면 503 `telemetry_delete_failed` 이고 아무것도 바뀌지 않는다.
     */
    @PutMapping
    suspend fun updateTelemetry(
        @CurrentUserId userId: Long,
        @RequestBody request: UpdateTelemetryRequest
    ): TelemetryStatus {
        platformAccessService.requireAdmin(userId)
        return settingsService.update(request.enabled, request.deleteSentData == true, userId)
    }

    /** 어제 report. 다음 tick 이 보낼 것과 같은 JSON 이다. consent 가 꺼져 있어도 만들고, 아무것도 보내지 않는다. */
    @GetMapping("/preview")
    suspend fun previewTelemetryReport(@CurrentUserId userId: Long): TelemetryReport {
        platformAccessService.requireAdmin(userId)
        return reportSender.previewYesterday()
    }
}
