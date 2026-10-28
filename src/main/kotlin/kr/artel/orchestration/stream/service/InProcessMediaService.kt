package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.common.error.TooManyRequestsException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.stream.config.StreamProperties
import kr.artel.orchestration.stream.dto.IceServer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class MediaIceCandidate(val candidate: String = "", val sdpMid: String? = null, val sdpMLineIndex: Int? = null)
internal data class MediaSignal(val type: String, val streamId: String? = null, val sdp: String? = null,
    val candidate: MediaIceCandidate? = null, val state: String? = null)

/** SDK 수신·viewer 송신 peer는 orch JVM 안에서 생성하고 해제한다. */
@Service
class InProcessMediaService(
    private val recorder: QaVideoRecorder,
    private val sessions: SessionManager,
    private val viewers: ViewerSessionRegistry,
    private val mapper: ObjectMapper,
    private val properties: StreamProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val admission = Mutex()
    private val streams = ConcurrentHashMap<UUID, Ingress>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("orch-media-signals"))
    private val factoryGate = Any()
    private var native: NativeRuntime? = null
    @Volatile private var stopping = false

    private class NativeRuntime(val audio: HeadlessAudioDeviceModule, val factory: PeerConnectionFactory)

    private inner class Peer(val signalId: String, val emit: suspend (String) -> Unit) {
        val operations = Mutex()
        lateinit var connection: RTCPeerConnection
        var remoteSet = false
        val incomingIce = ArrayList<MediaIceCandidate>()
        private val outgoingGate = Any()
        private var descriptionSent = false
        private val pendingIce = ArrayList<MediaIceCandidate>()
        private val signals = Channel<String>(128)
        @Volatile var closed = false
        private var nativeClosed = false
        @Volatile var failed = false
        private val sender = scope.launch {
            try {
                for (signal in signals) emit(signal)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = true
                logger.warn("미디어 시그널 전송 실패 [streamId={}]", signalId, error)
            }
        }

        fun candidate(candidate: RTCIceCandidate) = synchronized(outgoingGate) {
            if (closed) return@synchronized
            val value = MediaIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            if (descriptionSent) enqueue(MediaSignal("WEBRTC_ICE", signalId, candidate = value))
            else if (pendingIce.size < 64) pendingIce.add(value)
            else failed = true
        }

        fun description(description: RTCSessionDescription) = synchronized(outgoingGate) {
            if (closed) throw NotFoundException("종료된 영상 연결입니다.")
            enqueue(MediaSignal(if (description.sdpType == RTCSdpType.OFFER) "WEBRTC_OFFER" else "WEBRTC_ANSWER",
                signalId, sdp = description.sdp))
            descriptionSent = true
            pendingIce.forEach { enqueue(MediaSignal("WEBRTC_ICE", signalId, candidate = it)) }
            pendingIce.clear()
        }

        private fun enqueue(signal: MediaSignal) {
            if (!signals.trySend(mapper.writeValueAsString(signal)).isSuccess) {
                failed = true
                throw UpstreamUnavailableException("영상 시그널 대기열이 가득 찼습니다.")
            }
        }

        suspend fun close() = operations.withLock {
            synchronized(outgoingGate) {
                if (nativeClosed) return@withLock
                closed = true
                pendingIce.clear()
                signals.close()
            }
            sender.cancelAndJoin()
            incomingIce.clear()
            withContext(Dispatchers.IO) { connection.close() }
            nativeClosed = true
        }

        suspend fun closeIfInitialized() {
            if (::connection.isInitialized) close()
            else {
                synchronized(outgoingGate) {
                    closed = true
                    pendingIce.clear()
                    signals.close()
                }
                sender.cancelAndJoin()
            }
        }
    }

    private inner class Ingress(val id: UUID, val instanceId: String, val iceServers: List<IceServer>,
        val source: CustomVideoSource, val track: VideoTrack) {
        val frameGate = Any()
        val ready = CompletableDeferred<Unit>()
        val viewerGate = Mutex()
        val viewers = ConcurrentHashMap<String, Peer>()
        var closeTask: CompletableDeferred<Unit>? = null
        var disposed = false
        var trackDisposed = false
        var sourceDisposed = false
        val startedNanos = System.nanoTime()
        lateinit var sdk: Peer
        var received: VideoTrack? = null
        var sink: VideoTrackSink? = null
        @Volatile var closing = false
        @Volatile var failed = false

        fun state(): String {
            if (closing) return "closed"
            if (failed || sdk.failed || (System.nanoTime() - startedNanos > 60_000_000_000L && !ready.isCompleted)) return "failed"
            return sdk.connection.connectionState.name.lowercase()
        }

        fun receive(frame: VideoFrame) {
            try {
                synchronized(frameGate) {
                    if (closing) return
                    val buffer = frame.buffer
                    if (buffer.width <= 0 || buffer.height <= 0 || buffer.width > 1920 || buffer.height > 1080) {
                        failed = true
                        return
                    }
                    // JNI callback은 원본 참조 하나를 넘긴다. recorder는 queue에 넣을 때만 별도 retain한다.
                    recorder.accept(id, frame)
                    source.pushFrame(frame)
                    ready.complete(Unit)
                }
            } catch (error: Exception) {
                failed = true
                logger.warn("수신 영상 처리 실패 [streamId={}]", id, error)
            } finally {
                frame.release()
            }
        }
    }

    suspend fun configure(streamId: UUID, instanceId: String, recordingId: UUID?, iceServers: List<IceServer>): MediaLease {
        val (stream, created) = admission.withLock {
            if (stopping) throw UpstreamUnavailableException("영상 서비스를 종료하고 있습니다.")
            val existing = streams[streamId]
            if (existing != null) {
                if (existing.instanceId != instanceId) throw BadRequestException("영상 연결의 게임 인스턴스가 다릅니다.")
                existing to false
            } else {
                if (streams.size >= properties.mediaMaxIngress) throw TooManyRequestsException("동시 영상 수신 상한에 도달했습니다.", 10)
                val createdStream = withContext(Dispatchers.IO) { createIngress(streamId, instanceId, iceServers) }
                streams[streamId] = createdStream
                createdStream to true
            }
        }
        try {
            recorder.update(streamId, recordingId)
            return MediaLease(created, stream.state())
        } catch (error: Exception) {
            closeStream(streamId)
            throw error
        }
    }

    private fun runtime(): NativeRuntime = synchronized(factoryGate) {
        native?.let { return@synchronized it }
        try {
            val audio = HeadlessAudioDeviceModule()
            try {
                NativeRuntime(audio, PeerConnectionFactory(audio)).also { native = it }
            } catch (error: Throwable) {
                audio.dispose()
                throw error
            }
        } catch (error: LinkageError) {
            throw UpstreamUnavailableException("영상 라이브러리를 초기화할 수 없습니다.", cause = error)
        }
    }

    private fun createIngress(id: UUID, instanceId: String, iceServers: List<IceServer>): Ingress {
        val factory = runtime().factory
        val source = CustomVideoSource()
        var track: VideoTrack? = null
        try {
            track = factory.createVideoTrack("orch-$id", source)
            val ingress = Ingress(id, instanceId, iceServers, source, track)
            val peer = Peer(id.toString()) { sessions.sendRaw(instanceId, it) }
            try {
                peer.connection = factory.createPeerConnection(configuration(iceServers), observer(peer) { transceiver ->
                    val receiver = transceiver.receiver
                    val incoming = receiver.track
                    try {
                        if (incoming is VideoTrack) synchronized(ingress.frameGate) {
                            if (ingress.closing || ingress.received != null) incoming.dispose()
                            else {
                                ingress.received = incoming
                                val sink = VideoTrackSink(ingress::receive)
                                ingress.sink = sink
                                incoming.addSink(sink)
                            }
                        } else incoming?.dispose()
                    } finally {
                        receiver.dispose()
                        transceiver.dispose()
                    }
                }) ?: throw UpstreamUnavailableException("영상 연결을 생성할 수 없습니다.")
                ingress.sdk = peer
                return ingress
            } catch (error: Throwable) {
                scope.launch { peer.closeIfInitialized() }
                throw error
            }
        } catch (error: Throwable) {
            track?.dispose()
            source.dispose()
            throw error
        }
    }

    suspend fun openViewer(streamId: UUID, viewerId: String) {
        val stream = requireStream(streamId)
        withTimeout(20_000) { stream.ready.await() }
        stream.viewerGate.withLock {
            if (stream.closing) throw NotFoundException("종료된 영상 연결입니다.")
            stream.viewers.remove(viewerId)?.close()
            if (stream.viewers.size >= properties.mediaMaxViewers) throw TooManyRequestsException("동시 영상 열람 상한에 도달했습니다.", 10)
            val peer = Peer(viewerId) { viewers.relayToStream(viewerId, it) }
            try {
                peer.connection = runtime().factory.createPeerConnection(configuration(stream.iceServers), observer(peer))
                    ?: throw UpstreamUnavailableException("영상 연결을 생성할 수 없습니다.")
                peer.connection.addTrack(stream.track, listOf("orch-$streamId")).dispose()
                stream.viewers[viewerId] = peer
                peer.operations.withLock {
                    val offer = createDescription(peer.connection, true)
                    setDescription(peer.connection, offer, false)
                    peer.description(offer)
                }
            } catch (error: Throwable) {
                stream.viewers.remove(viewerId, peer)
                withContext(NonCancellable) { peer.closeIfInitialized() }
                throw error
            }
        }
    }

    suspend fun sdkSignal(streamId: UUID, rawJson: String) {
        val stream = requireStream(streamId)
        val signal = parse(rawJson)
        if (signal.type == "STREAM_STATE") {
            if (signal.state in setOf("FAILED", "STOPPED")) stream.failed = true
            return
        }
        stream.sdk.operations.withLock {
            if (stream.closing || stream.sdk.closed) return@withLock
            when (signal.type) {
                "WEBRTC_OFFER" -> {
                    applyRemote(stream.sdk, RTCSessionDescription(RTCSdpType.OFFER, requireSdp(signal)))
                    val answer = createDescription(stream.sdk.connection, false)
                    setDescription(stream.sdk.connection, answer, false)
                    stream.sdk.description(answer)
                }
                "WEBRTC_ICE" -> addIce(stream.sdk, signal.candidate)
                else -> throw BadRequestException("지원하지 않는 영상 시그널입니다.")
            }
        }
    }

    suspend fun viewerSignal(streamId: UUID, viewerId: String, rawJson: String) {
        val peer = requireStream(streamId).viewers[viewerId] ?: throw NotFoundException("영상 열람 연결을 찾을 수 없습니다.")
        val signal = parse(rawJson)
        peer.operations.withLock {
            if (peer.closed) return@withLock
            when (signal.type) {
                "WEBRTC_ANSWER" -> applyRemote(peer, RTCSessionDescription(RTCSdpType.ANSWER, requireSdp(signal)))
                "WEBRTC_ICE" -> addIce(peer, signal.candidate)
                else -> throw BadRequestException("지원하지 않는 영상 시그널입니다.")
            }
        }
    }

    suspend fun closeViewer(streamId: UUID, viewerId: String) {
        val stream = streams[streamId] ?: return
        stream.viewerGate.withLock { stream.viewers.remove(viewerId)?.close() }
    }

    suspend fun closeStream(streamId: UUID) {
        val stream = streams[streamId] ?: return
        val (completion, first) = synchronized(stream.frameGate) {
            if (stream.disposed) return
            val current = stream.closeTask
            if (current != null && current.isActive) current to false
            else {
                stream.closing = true
                CompletableDeferred<Unit>().also { stream.closeTask = it } to true
            }
        }
        if (!first) { completion.await(); return }
        stream.ready.completeExceptionally(NotFoundException("종료된 영상 연결입니다."))
        withContext(NonCancellable) {
            try {
                // frameGate로 현재 callback이 끝난 것을 확인한 뒤 sink를 제거한다.
                stream.received?.let { track -> stream.sink?.let(track::removeSink) }
                // encoder가 native 프레임을 모두 놓은 뒤에만 peer와 source를 해제한다.
                recorder.closeStream(streamId)
                stream.viewerGate.withLock {
                    for (peer in stream.viewers.values) peer.close()
                    stream.viewers.clear()
                }
                stream.sdk.close()
                stream.received?.dispose()
                stream.received = null
                if (!stream.trackDisposed) { stream.track.dispose(); stream.trackDisposed = true }
                if (!stream.sourceDisposed) { stream.source.dispose(); stream.sourceDisposed = true }
                synchronized(stream.frameGate) { stream.disposed = true }
                streams.remove(streamId, stream)
                completion.complete(Unit)
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
                if (!stopping) scope.launch {
                    delay(1000)
                    try { closeStream(streamId) }
                    catch (retryError: Exception) { logger.warn("미디어 종료 재시도 대기 [streamId={}]", streamId, retryError) }
                }
                throw error
            }
        }
    }

    private fun observer(peer: Peer, track: ((RTCRtpTransceiver) -> Unit)? = null) = object : PeerConnectionObserver {
        override fun onIceCandidate(candidate: RTCIceCandidate) {
            try { peer.candidate(candidate) }
            catch (error: Exception) {
                peer.failed = true
                logger.warn("ICE 시그널 대기열 처리 실패 [streamId={}]", peer.signalId, error)
            }
        }
        override fun onConnectionChange(state: RTCPeerConnectionState) {
            if (state in setOf(RTCPeerConnectionState.FAILED, RTCPeerConnectionState.CLOSED)) peer.failed = true
        }
        override fun onTrack(transceiver: RTCRtpTransceiver) {
            try { track?.invoke(transceiver) }
            catch (error: Exception) {
                peer.failed = true
                logger.warn("수신 영상 track 처리 실패 [streamId={}]", peer.signalId, error)
            }
        }
    }

    private fun configuration(servers: List<IceServer>) = RTCConfiguration().apply {
        iceServers = servers.map { server -> RTCIceServer().apply {
            urls = server.urls; username = server.username; password = server.credential
        } }
    }

    private fun parse(raw: String): MediaSignal = try { mapper.readValue(raw, MediaSignal::class.java) }
        catch (error: Exception) { throw BadRequestException("영상 시그널 형식이 올바르지 않습니다.") }

    private fun requireSdp(signal: MediaSignal): String = signal.sdp?.takeIf(String::isNotBlank)
        ?: throw BadRequestException("SDP가 없습니다.")

    private fun requireStream(id: UUID) = streams[id]?.takeUnless { it.closing }
        ?: throw NotFoundException("영상 연결을 찾을 수 없습니다.")

    private suspend fun applyRemote(peer: Peer, description: RTCSessionDescription) {
        setDescription(peer.connection, description, true)
        peer.remoteSet = true
        peer.incomingIce.forEach { candidate -> nativeIce(peer.connection, candidate) }
        peer.incomingIce.clear()
    }

    private fun addIce(peer: Peer, candidate: MediaIceCandidate?) {
        if (candidate == null || candidate.candidate.isBlank()) return
        if (candidate.candidate.length > 8192) throw BadRequestException("ICE 후보가 너무 큽니다.")
        if (peer.remoteSet) nativeIce(peer.connection, candidate)
        else if (peer.incomingIce.size < 64) peer.incomingIce.add(candidate)
        else throw TooManyRequestsException("ICE 후보 대기열이 가득 찼습니다.", 10)
    }

    private fun nativeIce(peer: RTCPeerConnection, candidate: MediaIceCandidate) {
        peer.addIceCandidate(RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex ?: 0, candidate.candidate))
    }

    private suspend fun createDescription(peer: RTCPeerConnection, offer: Boolean): RTCSessionDescription = withTimeout(20_000) {
        val result = CompletableDeferred<RTCSessionDescription>()
        val observer = object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) { result.complete(description) }
            override fun onFailure(error: String) { result.completeExceptionally(IllegalStateException(error)) }
        }
        if (offer) peer.createOffer(RTCOfferOptions(), observer) else peer.createAnswer(RTCAnswerOptions(), observer)
        result.await()
    }

    private suspend fun setDescription(peer: RTCPeerConnection, description: RTCSessionDescription, remote: Boolean) = withTimeout(20_000) {
        val result = CompletableDeferred<Unit>()
        val observer = object : SetSessionDescriptionObserver {
            override fun onSuccess() { result.complete(Unit) }
            override fun onFailure(error: String) { result.completeExceptionally(IllegalStateException(error)) }
        }
        if (remote) peer.setRemoteDescription(description, observer) else peer.setLocalDescription(description, observer)
        result.await()
    }

    @PreDestroy
    fun shutdown() = runBlocking {
        admission.withLock { stopping = true }
        streams.keys.toList().forEach {
            try { closeStream(it) }
            catch (error: Exception) { logger.warn("미디어 종료가 끝나지 않아 native 객체를 보존합니다 [streamId={}]", it, error) }
        }
        scope.cancel()
        synchronized(factoryGate) {
            if (streams.isEmpty()) {
                native?.let { it.factory.dispose(); it.audio.dispose() }
                native = null
            }
        }
    }
}
