package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.common.error.TooManyRequestsException
import kr.artel.orchestration.qa.review.QaRecordingStore
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.stream.config.StreamProperties
import kr.artel.orchestration.stream.dto.IceServer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
internal data class MediaSignal(val type: String, val streamId: String? = null, val state: String? = null,
    val transport: String? = null, val frameId: Long? = null, val jpeg: String? = null)

/** 영상도 SDK와 viewer의 인증된 WebSocket을 사용한다. 별도 포트나 UDP peer는 만들지 않는다. */
@org.springframework.stereotype.Service
class InProcessMediaService(private val recorder: QaVideoRecorder, private val sessions: SessionManager,
    private val viewers: ViewerSessionRegistry, private val mapper: ObjectMapper, private val properties: StreamProperties) {
    private val admission = Mutex()
    private val streams = ConcurrentHashMap<UUID, Ingress>()
    @Volatile private var stopping = false

    private class Ingress(val id: UUID, val instanceId: String, val sdkIdentity: Any?) {
        val gate = Mutex()
        val started = System.nanoTime()
        val viewers = ConcurrentHashMap.newKeySet<String>()
        var capable = false
        var failed = false
        var unsupported = false
        var closing = false
        var lastFrameId = -1L
        var lastFrameNanos = 0L
        var tokens = 2.0
        var refillNanos = started
        var latest: Pair<Long, String>? = null
        fun state(): String = when {
            closing -> "closed"
            failed -> "failed"
            unsupported -> "unsupported"
            !capable && System.nanoTime() - started > 10_000_000_000L -> "unsupported"
            lastFrameNanos != 0L && System.nanoTime() - lastFrameNanos < 5_000_000_000L -> "connected"
            capable && System.nanoTime() - maxOf(lastFrameNanos, started) > 15_000_000_000L -> "failed"
            else -> "connecting"
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun configure(streamId: UUID, instanceId: String, recordingId: UUID?, iceServers: List<IceServer>): MediaLease {
        val (stream, created) = admission.withLock {
            check(!stopping) { "영상 서비스를 종료 중입니다." }
            val existing = streams[streamId]
            if (existing != null) {
                if (existing.instanceId != instanceId) throw BadRequestException("영상 연결의 게임 인스턴스가 다릅니다.")
                if (existing.sdkIdentity !== sessions.sessionIdentity(instanceId)) existing.failed = true
                existing to false
            } else {
                if (streams.size >= properties.mediaMaxIngress) throw TooManyRequestsException("동시 영상 수신 상한에 도달했습니다.", 10)
                Ingress(streamId, instanceId, sessions.sessionIdentity(instanceId)).also { streams[streamId] = it } to true
            }
        }
        stream.gate.withLock {
            if (stream.closing) throw NotFoundException("종료된 영상 연결입니다.")
            recorder.update(streamId, recordingId)
            val state = stream.state()
            if (state == "unsupported") {
                stream.unsupported = true
                stream.viewers.forEach { sendUnsupported(it) }
            }
            return MediaLease(created, state)
        }
    }

    suspend fun openViewer(streamId: UUID, viewerId: String) {
        val stream = streams[streamId] ?: throw NotFoundException("영상 연결을 찾을 수 없습니다.")
        stream.gate.withLock {
            if (stream.closing) throw NotFoundException("종료된 영상 연결입니다.")
            if (!stream.viewers.contains(viewerId) && stream.viewers.size >= properties.mediaMaxViewers)
                throw TooManyRequestsException("동시 영상 열람 상한에 도달했습니다.", 10)
            stream.viewers.add(viewerId)
            if (stream.state() == "unsupported") sendUnsupported(viewerId)
            else stream.latest?.let { viewers.sendFrame(viewerId, it.first, it.second) }
        }
    }

    suspend fun closeViewer(streamId: UUID, viewerId: String) { streams[streamId]?.viewers?.remove(viewerId) }

    /** 상태와 frame은 같은 순서로 처리한다. JPEG decode는 recorder thread가 맡는다. */
    suspend fun sdkSignal(streamId: UUID, rawJson: String) {
        val receivedAt = java.time.Instant.now()
        val receivedNanos = System.nanoTime()
        val stream = streams[streamId] ?: return
        if (rawJson.length > 184 * 1024) throw BadRequestException("영상 메시지 크기 상한을 넘었습니다.")
        val signal = try { mapper.readValue(rawJson, MediaSignal::class.java) }
            catch (_: Exception) { throw BadRequestException("영상 메시지 형식이 올바르지 않습니다.") }
        stream.gate.withLock {
            if (stream.closing || stream.sdkIdentity !== sessions.sessionIdentity(stream.instanceId)) return
            when (signal.type) {
                "STREAM_STATE" -> {
                    if (signal.transport == "JPEG_WS") stream.capable = true
                    if (signal.state in setOf("FAILED", "STOPPED")) stream.failed = true
                }
                "WEBRTC_OFFER", "WEBRTC_ICE" -> {
                    stream.unsupported = true
                    stream.viewers.forEach { sendUnsupported(it) }
                }
                "STREAM_FRAME" -> acceptFrame(stream, signal, receivedAt, receivedNanos)
            }
        }
    }

    private suspend fun acceptFrame(stream: Ingress, signal: MediaSignal, receivedAt: java.time.Instant, receivedNanos: Long) {
        val id = signal.frameId ?: return
        var accepted = false
        try {
            val now = System.nanoTime()
            stream.tokens = minOf(2.0, stream.tokens + (now - stream.refillNanos) / 100_000_000.0)
            stream.refillNanos = now
            if (id < 0 || id <= stream.lastFrameId || stream.tokens < 1.0 || stream.unsupported || stream.failed) return
            stream.tokens -= 1.0
            stream.lastFrameId = id
            val jpeg = signal.jpeg ?: return
            val frame = withContext(Dispatchers.Default) { JpegVideoFrame.parse(jpeg).copy(instant = receivedAt, nanoTime = receivedNanos) }
            stream.capable = true
            stream.lastFrameNanos = frame.nanoTime
            // 녹화 queue 포화는 live viewer와 SDK의 다음 frame을 막지 않는다.
            recorder.accept(stream.id, frame)
            stream.latest = id to jpeg
            stream.viewers.forEach { viewers.sendFrame(it, id, jpeg) }
            accepted = true
        } catch (_: IllegalArgumentException) {
            // 잘못된 JPEG는 수신 확인만 거절하고 다음 frame을 허용한다.
        } finally {
            sessions.sendMediaAck(stream.instanceId, mapOf("type" to "STREAM_FRAME_ACK", "streamId" to stream.id.toString(),
                "frameId" to id, "accepted" to accepted))
        }
    }

    private fun sendUnsupported(viewerId: String) {
        viewers.relayToStream(viewerId, mapper.writeValueAsString(mapOf("type" to "STREAM_ERROR", "streamId" to viewerId,
            "code" to "SDK_UPDATE_REQUIRED")))
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun viewerSignal(streamId: UUID, viewerId: String, rawJson: String) = Unit

    suspend fun closeStream(streamId: UUID) {
        val stream = streams[streamId] ?: return
        stream.gate.withLock {
            stream.closing = true
            recorder.closeStream(streamId)
            stream.latest = null
            stream.viewers.clear()
            streams.remove(streamId, stream)
        }
    }

    @PreDestroy
    fun shutdown() = runBlocking {
        admission.withLock { stopping = true }
        streams.keys.toList().forEach { closeStream(it) }
    }
}
