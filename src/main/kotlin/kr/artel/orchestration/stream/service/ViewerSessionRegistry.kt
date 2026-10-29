package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.common.error.TooManyRequestsException
import kr.artel.orchestration.stream.config.StreamProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** 인스턴스의 viewer들을 독립적으로 보관한다. map 변경만 원자적으로 처리한다. */
@Service
class ViewerSessionRegistry(private val objectMapper: ObjectMapper, private val properties: StreamProperties = StreamProperties()) {
    private val logger = LoggerFactory.getLogger(ViewerSessionRegistry::class.java)
    private val registryGate = Any()
    private val byInstance = HashMap<String, MutableMap<String, ViewerSession>>()
    private val byStream = ConcurrentHashMap<String, ViewerSession>()

    /** 상한을 넘는 접속만 거절하고 이미 시청 중인 viewer는 유지한다. */
    fun admit(instanceId: String, streamId: String, session: WebSocketSession): Admission = synchronized(registryGate) {
        val instance = byInstance[instanceId]
        if ((instance?.size ?: 0) >= properties.mediaMaxViewers)
            throw TooManyRequestsException("동시 영상 열람 상한에 도달했습니다.", 10)
        require(!byStream.containsKey(streamId)) { "이미 등록된 viewer stream ID입니다." }
        val viewer = ViewerSession(instanceId, streamId, session)
        byInstance.getOrPut(instanceId) { HashMap() }[streamId] = viewer
        byStream[streamId] = viewer
        Admission(viewer)
    }

    /** 해당 소켓의 등록만 해제한다. 늦은 close가 같은 ID의 새 등록을 제거하지 않는다. */
    fun release(viewer: ViewerSession): Boolean {
        val removed = synchronized(registryGate) {
            val instance = byInstance[viewer.instanceId]
            if (instance == null || instance[viewer.streamId] !== viewer) false
            else {
                instance.remove(viewer.streamId)
                if (instance.isEmpty()) byInstance.remove(viewer.instanceId)
                byStream.remove(viewer.streamId, viewer)
                true
            }
        }
        viewer.close()
        return removed
    }

    fun findByStream(streamId: String): ViewerSession? = byStream[streamId]

    fun sendFrame(streamId: String, frameId: Long, jpeg: String): Boolean {
        val viewer = byStream[streamId] ?: return false
        return viewer.frame(objectMapper.writeValueAsString(mapOf("type" to "STREAM_FRAME", "streamId" to streamId,
            "frameId" to frameId, "jpeg" to jpeg)))
    }

    /** 서버가 만든 메시지를 뷰어에게 보낸다. */
    fun send(viewer: ViewerSession, payload: Any) {
        emit(viewer, objectMapper.writeValueAsString(payload))
    }

    /** 활성 viewer에게 JSON control을 전달한다. 해제된 ID의 메시지는 버린다. */
    fun relayToStream(streamId: String, rawJson: String): Boolean {
        val viewer = byStream[streamId] ?: return false
        emit(viewer, rawJson)
        return true
    }

    private fun emit(viewer: ViewerSession, json: String) {
        if (!viewer.emit(json)) {
            logger.warn("뷰어 메시지 전송 실패 [streamId: ${viewer.streamId}]: 전송 큐가 닫혔거나 가득 찼습니다.")
        }
    }

    data class Admission(val viewer: ViewerSession)

    /** viewer별 control과 최신 frame을 제한하고 한 송신 흐름으로 합친다. */
    class ViewerSession(
        val instanceId: String,
        val streamId: String,
        val session: WebSocketSession
    ) {
        private val sink = Sinks.many().unicast().onBackpressureBuffer(ArrayBlockingQueue<String>(16))
        private val frames = LatestMediaMessages()
        private val controlBytes = AtomicInteger()

        fun outbound(): Flux<String> = Flux.merge(1,
            sink.asFlux().doOnNext { controlBytes.addAndGet(-it.toByteArray(Charsets.UTF_8).size) }, frames.flux)

        internal fun emit(json: String): Boolean {
            val size = json.toByteArray(Charsets.UTF_8).size
            if (controlBytes.addAndGet(size) > 64 * 1024) {
                controlBytes.addAndGet(-size)
                session.close(org.springframework.web.reactive.socket.CloseStatus(1013, "Viewer queue full")).subscribe()
                return false
            }
            val result = sink.tryEmitNext(json) == Sinks.EmitResult.OK
            if (!result) controlBytes.addAndGet(-size)
            return result
        }

        internal fun frame(json: String): Boolean = frames.emit(json)

        internal fun close() {
            sink.tryEmitComplete()
            frames.close()
        }
    }
}
