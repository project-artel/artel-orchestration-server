package kr.artel.orchestration.telemetry.service

import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.telemetry.config.TelemetryProperties
import kr.artel.orchestration.telemetry.dto.TelemetryStatus
import kr.artel.orchestration.telemetry.repository.TelemetryErrorEventRepository
import org.springframework.stereotype.Service

/**
 * 관리 화면의 telemetry 항목. consent 를 바꾸고, 끌 때 원하면 이미 보낸 report 를 receiver 에서 지운다.
 *
 * 지우기를 고르면 receiver 의 `DELETE` 가 성공한 뒤에만 consent 를 끄고 install id 를 버린다. 순서를 뒤집으면 지우기가
 * 실패했을 때 어느 install id 의 report 를 지워야 하는지 이 설치도 더는 모른다.
 *
 * consent 를 끄면 아직 보내지 않은 `telemetry_error_event` 도 지운다. 그 행은 보내려고 남긴 것뿐이다.
 */
@Service
class TelemetrySettingsService(
    private val properties: TelemetryProperties,
    private val consentService: TelemetryConsentService,
    private val receiverClient: TelemetryReceiverClient,
    private val errorEventRepository: TelemetryErrorEventRepository
) {
    suspend fun status(): TelemetryStatus = consentService.status()

    suspend fun update(enabled: Boolean, deleteSentData: Boolean, adminUserId: Long): TelemetryStatus {
        if (enabled) {
            if (!properties.endpointConfigured) throw TelemetryUnavailableException()
            consentService.saveConsent(true, adminUserId)
            return status()
        }
        if (deleteSentData) {
            if (!properties.endpointConfigured) throw TelemetryUnavailableException()
            val installId = consentService.installId()
            if (installId != null && !receiverClient.delete(installId)) throw TelemetryDeleteFailedException()
            consentService.forgetInstall(adminUserId)
        } else {
            consentService.saveConsent(false, adminUserId)
        }
        errorEventRepository.deleteAll()
        return status()
    }
}

/** `ARTEL_TELEMETRY_URL` 이 비어 있는 설치에서 telemetry 를 켜거나 보낸 report 를 지우려 할 때. */
class TelemetryUnavailableException :
    ConflictException("이 설치에는 telemetry 를 보낼 주소가 없습니다.", code = "telemetry_unavailable")

/** receiver 가 지우기 요청에 2xx 로 답하지 않았을 때. consent 는 그대로다. */
class TelemetryDeleteFailedException :
    UpstreamUnavailableException("telemetry receiver 가 보낸 report 를 지우지 못했습니다.", code = "telemetry_delete_failed")
