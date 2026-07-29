package kr.artel.orchestration.stream.service

import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.stream.config.StreamProperties
import kr.artel.orchestration.stream.dto.StreamRenewMessage
import kr.artel.orchestration.stream.dto.StreamStartMessage
import kr.artel.orchestration.stream.dto.StreamStopMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 시그널링을 브라우저와 게임 사이로 옮기는 유일한 지점.
 *
 * 미디어는 여기를 지나지 않는다. 서버가 아는 것은 "어떤 streamId가 어느 인스턴스의 것인가"뿐이고,
 * SDP와 ICE 본문은 열어 보지 않고 원문 그대로 넘긴다.
 */
@Service
class StreamSignalRelay(
    private val sessionManager: SessionManager,
    private val viewers: ViewerSessionRegistry,
    private val properties: StreamProperties
) {
    private val logger = LoggerFactory.getLogger(StreamSignalRelay::class.java)

    /**
     * 게임에게 스트림을 시작하라고 알린다.
     *
     * 이미 돌고 있는 세션이 있으면 SDK가 그것을 대체한다. 앞서 [stopStream]을 보내지 않는
     * 이유는, 그렇게 하면 교체가 두 메시지의 도착 순서에 기대는 동작이 되기 때문이다.
     */
    suspend fun startStream(instanceId: String, streamId: String) =
        sessionManager.send(
            instanceId,
            StreamStartMessage(
                streamId = streamId,
                iceServers = properties.iceServers,
                video = properties.video,
                leaseSeconds = properties.leaseSeconds
            )
        )

    suspend fun renewStream(instanceId: String, streamId: String) =
        sessionManager.send(instanceId, StreamRenewMessage(streamId))

    suspend fun stopStream(instanceId: String, streamId: String) =
        sessionManager.send(instanceId, StreamStopMessage(streamId))

    /**
     * 브라우저가 보낸 시그널링을 게임으로 넘긴다. 자기 세션의 streamId가 아니면 버린다.
     */
    suspend fun toSdk(viewer: ViewerSessionRegistry.ViewerSession, streamId: String?, rawJson: String) {
        if (streamId != viewer.streamId) {
            logger.debug("만료된 streamId의 뷰어 시그널링을 버립니다 [받은 값: $streamId, 현재: ${viewer.streamId}]")
            return
        }

        sessionManager.sendRaw(viewer.instanceId, rawJson)
    }

    /**
     * 게임이 보낸 시그널링을 브라우저로 넘긴다. 그 streamId를 보고 있는 뷰어가 없으면 버린다.
     * 밀려난 뷰어 앞으로 뒤늦게 도착한 후보가 새 협상에 섞이는 일을 막는 자리다.
     */
    fun toViewer(streamId: String?, rawJson: String) {
        if (streamId == null || !viewers.relayToStream(streamId, rawJson)) {
            logger.debug("보고 있는 뷰어가 없는 시그널링을 버립니다 [streamId: $streamId]")
        }
    }
}
