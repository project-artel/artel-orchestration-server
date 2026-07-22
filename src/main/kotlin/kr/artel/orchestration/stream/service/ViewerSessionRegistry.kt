package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

/**
 * 인스턴스마다 화면을 보고 있는 브라우저 하나를 들고 있다.
 *
 * 새 연결이 들어오면 앞 연결을 밀어낸다. 이것은 `/ws/sdk`가 중복 SDK 연결을 거절하는 것과
 * 반대인데, 밀려나는 쪽의 성격이 다르기 때문이다. SDK 자리의 주인은 실행 중인 게임이라 잃으면
 * 비싸다. 뷰어 자리의 주인은 브라우저 탭이고, 흔한 상황은 같은 사람이 새로고침하거나 페이지를
 * 다시 여는 것이다. 자기 게임을 보려고 자기가 남긴 임대가 만료되기를 기다리게 만드는 쪽이 더
 * 나쁜 실패다.
 *
 * 그래서 [release]가 "자기 자리일 때만" 지우는 것이 여기서는 필수다. 밀려난 세션의 종료
 * 처리는 자기를 대신한 세션이 이미 등록된 뒤에 돈다. 그냥 지우면 살아 있는 뷰어의 자리를
 * 비우고 나가서, 연결은 되어 있는데 서버가 찾지 못하는 브라우저가 남는다.
 */
@Service
class ViewerSessionRegistry(private val objectMapper: ObjectMapper) {

    private val logger = LoggerFactory.getLogger(ViewerSessionRegistry::class.java)

    private val byInstance = ConcurrentHashMap<String, ViewerSession>()
    private val byStream = ConcurrentHashMap<String, ViewerSession>()

    /**
     * 새 뷰어를 자리에 앉히고, 밀려난 앞 뷰어가 있으면 함께 돌려준다. 밀려난 세션을 닫는 것은
     * 호출자의 몫이다. 여기서 닫아 버리면 등록부가 소켓 수명까지 쥐게 된다.
     */
    fun admit(instanceId: String, streamId: String, session: WebSocketSession): Admission {
        val viewer = ViewerSession(instanceId, streamId, session)
        val displaced = byInstance.put(instanceId, viewer)
        byStream[streamId] = viewer

        if (displaced != null) {
            byStream.remove(displaced.streamId, displaced)
        }

        return Admission(viewer, displaced)
    }

    /**
     * 자기가 잡고 있던 자리일 때만 비운다.
     *
     * 돌려주는 값은 "이 세션이 방금까지 현재 뷰어였는가"다. 호출자는 이 값으로 SDK에 스트림
     * 중단을 알릴지 정한다. 밀려난 세션이 뒤늦게 정리되면서 중단을 보내면, 방금 시작한 새
     * 스트림이 꺼진다.
     */
    fun release(viewer: ViewerSession): Boolean {
        byStream.remove(viewer.streamId, viewer)
        val wasCurrent = byInstance.remove(viewer.instanceId, viewer)
        viewer.close()
        return wasCurrent
    }

    fun findByStream(streamId: String): ViewerSession? = byStream[streamId]

    /** 서버가 만든 메시지를 뷰어에게 보낸다. */
    fun send(viewer: ViewerSession, payload: Any) {
        emit(viewer, objectMapper.writeValueAsString(payload))
    }

    /**
     * SDK가 보낸 시그널링을 원문 그대로 뷰어에게 넘긴다. 아직 살아 있는 스트림의 것이 아니면
     * 버린다. 밀려난 세션의 ICE 후보가 뒤늦게 도착해 새 협상에 섞이는 것을 여기서 막는다.
     */
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

    data class Admission(val viewer: ViewerSession, val displaced: ViewerSession?)

    /**
     * 뷰어 연결 하나와 그 연결의 전송 큐.
     *
     * SDK 세션과 같은 이유로 큐를 둔다. 시그널링은 ICE 후보가 짧은 시간에 몰려 나오는 통신이라,
     * 발신마다 새로 write를 거는 방식으로는 같은 소켓에 동시 write가 걸린다.
     */
    class ViewerSession(
        val instanceId: String,
        val streamId: String,
        val session: WebSocketSession
    ) {
        private val sink = Sinks.many().unicast().onBackpressureBuffer<String>()

        fun outbound(): Flux<String> = sink.asFlux()

        internal fun emit(json: String): Boolean = sink.tryEmitNext(json) == Sinks.EmitResult.OK

        internal fun close() {
            sink.tryEmitComplete()
        }
    }
}
