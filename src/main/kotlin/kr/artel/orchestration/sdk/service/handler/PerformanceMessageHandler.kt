package kr.artel.orchestration.sdk.service.handler

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdkperf.dto.SdkDeviceContextMessage
import kr.artel.orchestration.sdkperf.dto.SdkPerformanceMessage
import kr.artel.orchestration.sdkperf.service.SdkPerfIngestService
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession

/**
 * 1초마다 오는 PERFORMANCE 표본을 저장한다 (ARTEL-372).
 *
 * 핸들러는 파싱과 위임만 한다. 런 경계·빌드 연결·결측 처리는 [SdkPerfIngestService]에 있다.
 *
 * 실패해도 소켓은 끊지 않는다. 예외를 그대로 올리면 [kr.artel.orchestration.sdk.service.SdkWebSocketHandler]가
 * 로그만 남기고 다음 프레임으로 넘어가므로, 성능 저장이 실패한다고 QA 실행이 멈추지 않는다.
 * 성능 지표는 보조 신호이고 액션·게임 상태가 본류다.
 */
@Component
class PerformanceMessageHandler(
    private val objectMapper: ObjectMapper,
    private val ingestService: SdkPerfIngestService
) : SdkMessageHandler {

    override val messageType: String = "PERFORMANCE"

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        val message = objectMapper.readValue(payloadText, SdkPerformanceMessage::class.java)
        ingestService.recordPerformance(instanceId.toLong(), session.id, message)
    }
}

/**
 * 연결당 한 번 오는 DEVICE_CONTEXT를 런에 붙인다 (ARTEL-372).
 *
 * 이 메시지가 오지 않은 런은 `isEditor`를 알 수 없고, 빌드 추세에서 제외된다. 에디터가
 * 아니라고 단정할 근거가 없기 때문이다.
 */
@Component
class DeviceContextMessageHandler(
    private val objectMapper: ObjectMapper,
    private val ingestService: SdkPerfIngestService
) : SdkMessageHandler {

    override val messageType: String = "DEVICE_CONTEXT"

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        val message = objectMapper.readValue(payloadText, SdkDeviceContextMessage::class.java)
        ingestService.recordDeviceContext(instanceId.toLong(), session.id, message)
    }
}
