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
 * 게임의 영상 frame과 상태를 orch 내부 영상 서비스에 전달한다.
 *
 * 세 메시지가 하는 일이 같아서 한 파일에 둔다. [SdkMessageHandler]가 타입 하나만 선언하는
 * 구조라 클래스는 셋이어야 하지만, 타입 상수만 다른 8줄짜리를 파일 셋으로 흩는 것은 얻는 것
 * 없이 읽을 곳만 늘린다. 인터페이스를 여러 타입을 받도록 넓히지 않는 이유는 그쪽이 기존
 * 핸들러 둘을 건드리기 때문이다.
 *
 * 이 handler는 streamId만 읽는다. 내부 서비스가 JPEG 크기·형식·전송률을 검증한다.
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
 * SDK의 transport capability와 실패 상태를 받아 재연결 및 업데이트 안내에 사용한다.
 */
@Component
class StreamStateMessageHandler(
    objectMapper: ObjectMapper,
    relay: StreamSignalRelay
) : StreamSignalMessageHandler(objectMapper, relay) {
    override val messageType: String = StreamMessageType.STREAM_STATE
}

@Component
class StreamFrameMessageHandler(objectMapper: ObjectMapper, relay: StreamSignalRelay)
    : StreamSignalMessageHandler(objectMapper, relay) {
    override val messageType = StreamMessageType.STREAM_FRAME
}
