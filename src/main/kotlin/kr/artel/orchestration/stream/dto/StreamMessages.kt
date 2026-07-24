package kr.artel.orchestration.stream.dto

/**
 * 화면 스트리밍 시그널링 메시지들. 계약 전체는 `docs/streaming-protocol.md`에 있다.
 *
 * 서버가 만들어 보내는 메시지만 여기 타입으로 있다. 브라우저와 SDK 사이를 오가는 SDP/ICE는
 * 서버가 열어 보지 않고 원문 그대로 넘긴다. 중간에서 다시 직렬화하면 한쪽이 필드를 추가했을 때
 * 서버가 그 필드를 조용히 떨어뜨리게 되는데, WebRTC 협상에서 그 손실은 "연결은 되는데 미디어가
 * 없다"처럼 원인을 찾기 어려운 모습으로 나타난다.
 */
object StreamMessageType {
    const val STREAM_START = "STREAM_START"
    const val STREAM_RENEW = "STREAM_RENEW"
    const val STREAM_STOP = "STREAM_STOP"
    const val STREAM_READY = "STREAM_READY"
    const val STREAM_STATE = "STREAM_STATE"
    const val WEBRTC_OFFER = "WEBRTC_OFFER"
    const val WEBRTC_ANSWER = "WEBRTC_ANSWER"
    const val WEBRTC_ICE = "WEBRTC_ICE"
    const val RENEW = "RENEW"
    const val STOP = "STOP"
    const val ERROR = "ERROR"
}

/**
 * 브라우저의 `RTCIceServer`와 같은 모양이다. SDK에도 그대로 전달되므로 양쪽이 같은 값을 본다.
 */
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

/**
 * 인코딩 상한. 게임이 프레임을 만드는 비용을 정하는 값이라 서버가 쥐고 있다.
 */
data class VideoConstraints(
    val maxWidth: Int,
    val maxFramerate: Int
)

data class StreamStartMessage(
    val streamId: String,
    val iceServers: List<IceServer>,
    val video: VideoConstraints,
    val leaseSeconds: Long,
    val type: String = StreamMessageType.STREAM_START
)

data class StreamRenewMessage(
    val streamId: String,
    val type: String = StreamMessageType.STREAM_RENEW
)

data class StreamStopMessage(
    val streamId: String,
    val type: String = StreamMessageType.STREAM_STOP
)

data class StreamReadyMessage(
    val streamId: String,
    val iceServers: List<IceServer>,
    val type: String = StreamMessageType.STREAM_READY
)

data class StreamErrorMessage(
    val code: String,
    val message: String,
    val type: String = StreamMessageType.ERROR
)

/**
 * 시그널링 메시지에서 서버가 읽는 부분만 담는 봉투.
 *
 * 서버는 `type`으로 어디로 보낼지 정하고 `streamId`로 아직 살아 있는 세션의 것인지 확인한다.
 * 나머지 본문은 열어 보지 않는다.
 */
data class SignalEnvelope(
    val type: String,
    val streamId: String? = null
)
