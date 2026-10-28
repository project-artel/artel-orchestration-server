package kr.artel.orchestration.stream.service.handler

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdk.service.handler.SdkMessageHandler
import kr.artel.orchestration.stream.dto.SignalEnvelope
import kr.artel.orchestration.stream.dto.StreamMessageType
import kr.artel.orchestration.stream.service.StreamSignalRelay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession

/**
 * 게임이 보낸 시그널링을 orch 내부의 해당 WebRTC peer에 전달한다.
 *
 * 세 메시지가 하는 일이 같아서 한 파일에 둔다. [SdkMessageHandler]가 타입 하나만 선언하는
 * 구조라 클래스는 셋이어야 하지만, 타입 상수만 다른 8줄짜리를 파일 셋으로 흩는 것은 얻는 것
 * 없이 읽을 곳만 늘린다. 인터페이스를 여러 타입을 받도록 넓히지 않는 이유는 그쪽이 기존
 * 핸들러 둘을 건드리기 때문이다.
 *
 * 이 handler는 streamId만 읽는다. 내부 미디어 서비스가 SDP·ICE를 검증하고 처리한다.
 */
sealed class StreamSignalMessageHandler(
    private val objectMapper: ObjectMapper,
    private val relay: StreamSignalRelay
) : SdkMessageHandler {

    private val logger = LoggerFactory.getLogger(StreamSignalMessageHandler::class.java)

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        val envelope = try {
            objectMapper.readValue(payloadText, SignalEnvelope::class.java)
        } catch (exception: Exception) {
            logger.warn("SDK 시그널링 파싱 실패 [instanceId: $instanceId]: ${exception.message}")
            return
        }

        relay.toViewer(instanceId, envelope.streamId, payloadText)
    }
}

@Component
class WebRtcOfferMessageHandler(
    objectMapper: ObjectMapper,
    relay: StreamSignalRelay
) : StreamSignalMessageHandler(objectMapper, relay) {
    override val messageType: String = StreamMessageType.WEBRTC_OFFER
}

@Component
class WebRtcIceMessageHandler(
    objectMapper: ObjectMapper,
    relay: StreamSignalRelay
) : StreamSignalMessageHandler(objectMapper, relay) {
    override val messageType: String = StreamMessageType.WEBRTC_ICE
}

/**
 * 상태는 내부 peer의 복구 판단에 반영한다. TURN이 없으면 서로 다른 망에 있는
 * 게임과 브라우저는 협상까지 성공한 뒤 미디어만 오지 않는데, 그 사실이 화면에 "연결 중"으로만
 * 남으면 사용자는 망 제약을 스트림 버그로 신고하게 된다.
 */
@Component
class StreamStateMessageHandler(
    objectMapper: ObjectMapper,
    relay: StreamSignalRelay
) : StreamSignalMessageHandler(objectMapper, relay) {
    override val messageType: String = StreamMessageType.STREAM_STATE
}
