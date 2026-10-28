package kr.artel.orchestration.stream.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** SDK와 viewer는 각각 orch의 WebRTC peer와 연결한다. native 오류 시 직접 P2P로 전환하지 않는다. */
@Service
class StreamSignalRelay(private val media: QaMediaRelay) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun startStream(instanceId: String, streamId: String) = media.openViewer(instanceId, streamId)

    // viewer 임대는 registry에서, SDK ingress 임대는 QA 녹화와 함께 갱신한다.
    @Suppress("UNUSED_PARAMETER")
    suspend fun renewStream(instanceId: String, streamId: String) = Unit

    suspend fun stopStream(instanceId: String, streamId: String) = media.closeViewer(instanceId, streamId)

    suspend fun toSdk(viewer: ViewerSessionRegistry.ViewerSession, streamId: String?, rawJson: String) {
        if (streamId != viewer.streamId) {
            logger.debug("만료된 viewer 시그널 무시 [streamId={}]", streamId)
            return
        }
        media.viewerSignal(viewer.instanceId, viewer.streamId, rawJson)
    }

    suspend fun toViewer(instanceId: String, streamId: String?, rawJson: String) = media.sdkSignal(instanceId, streamId, rawJson)
}
