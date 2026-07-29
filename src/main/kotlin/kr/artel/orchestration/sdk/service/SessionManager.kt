package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

/**
 * 현재 연결된 웹소켓 세션들을 관리하고, 서버에서 클라이언트로 메시지(액션)를 전송하는 서비스
 *
 * 키는 게임 인스턴스 id다. 자격증명인 instanceKey를 키로 삼으면 세션 맵과 로그, 에이전트가
 * 호출하는 URL까지 자격증명이 퍼진다.
 *
 * 등록과 제거가 모두 "그 세션이 맞을 때만" 동작한다. 한 인스턴스에 두 연결이 겹칠 때
 * 나중 연결이 앞 연결을 조용히 덮어쓰면, 앞 소켓은 닫히지도 않은 채 도달 불가능해지고
 * 나중에 끊길 때 살아 있는 뒤 연결의 자리를 대신 비운다. 만료 없는 자격증명으로 재연결이
 * 잦아지면 이 순서가 흔해진다.
 *
 * 나가는 메시지는 세션마다 하나뿐인 sink를 거친다. `WebSocketSession.send`를 호출할 때마다
 * 새로 구독을 붙이는 방식은 발신자가 ACTION 하나였을 때만 통했다. 화면 스트리밍이 붙으면서
 * ICE 후보가 1초 안에 여러 개씩 쏟아지고 그것이 ACTION 전달과 겹치므로, 전송을 한 줄로 모아
 * 두지 않으면 같은 세션에 동시 write가 걸린다.
 */
@Service
class SessionManager(private val objectMapper: ObjectMapper) {

    private val logger = LoggerFactory.getLogger(SessionManager::class.java)
    private val sessions = ConcurrentHashMap<String, SdkSession>()

    /**
     * 이미 다른 연결이 자리를 잡고 있으면 null이다. 호출자가 새 연결을 거절한다.
     *
     * 돌려주는 [Flux]는 이 세션으로 나갈 메시지들이다. 호출자가 반드시 `session.send`에
     * 물려야 한다. 물리지 않으면 등록은 됐는데 아무것도 나가지 않는 세션이 된다.
     */
    fun register(instanceId: String, session: WebSocketSession): Flux<String>? {
        val registered = SdkSession(session)
        return if (sessions.putIfAbsent(instanceId, registered) == null) {
            registered.outbound()
        } else {
            null
        }
    }

    /** 자기가 등록해 둔 세션일 때만 지운다. */
    fun removeSession(instanceId: String, session: WebSocketSession) {
        val registered = sessions[instanceId] ?: return
        if (registered.session !== session) {
            return
        }

        if (sessions.remove(instanceId, registered)) {
            registered.close()
        }
    }

    fun getSession(instanceId: String): WebSocketSession? = sessions[instanceId]?.session

    fun hasSession(instanceId: String): Boolean = sessions.containsKey(instanceId)

    suspend fun sendAction(instanceId: String, action: ActionResponseDto) =
        send(instanceId, action)

    /**
     * 임의의 메시지를 연결된 SDK로 보낸다.
     *
     * 실제 write는 세션의 전송 파이프라인이 한다. 이 함수가 반환됐다는 것은 "보냈다"가 아니라
     * "보낼 줄에 세웠다"는 뜻이다. 소켓에 실제로 나갔는지까지 확인하려면 SDK의 응답을 봐야
     * 하는데, 이 프로토콜에서 그 확인이 필요한 메시지는 없다.
     */
    suspend fun send(instanceId: String, payload: Any) {
        val json = objectMapper.writeValueAsString(payload)
        sendRaw(instanceId, json)
    }

    /**
     * 이미 JSON인 문자열을 그대로 보낸다.
     *
     * 시그널링 중계용이다. 브라우저가 만든 SDP/ICE를 서버가 다시 객체로 읽었다가 직렬화하면,
     * 서버가 모르는 필드가 조용히 사라진다. WebRTC 협상에서 그 손실은 "연결은 되는데 미디어가
     * 없다"처럼 원인을 찾기 어려운 모습으로 나타나므로, 중계는 원문을 건드리지 않는다.
     *
     * 세션이 없으면 예외를 던진다. 호출자(컨트롤러)가 이를 404로 옮긴다.
     */
    suspend fun sendRaw(instanceId: String, json: String) {
        val registered = sessions[instanceId]
            ?: throw IllegalArgumentException("게임 인스턴스에 해당하는 활성화된 웹소켓 세션이 없습니다: $instanceId")

        if (!registered.emit(json)) {
            logger.warn("SDK 메시지 전송 실패 [instanceId: $instanceId]: 전송 큐가 닫혔거나 가득 찼습니다.")
        }
    }

    /**
     * 세션 하나와 그 세션의 전송 큐.
     *
     * unicast인 이유는 구독자가 정확히 하나(전송 파이프라인)이기 때문이다. multicast로 두면
     * 구독이 붙기 전에 넣은 메시지가 조용히 버려진다.
     */
    private class SdkSession(val session: WebSocketSession) {
        private val sink = Sinks.many().unicast().onBackpressureBuffer<String>()

        fun outbound(): Flux<String> = sink.asFlux()

        fun emit(json: String): Boolean = sink.tryEmitNext(json) == Sinks.EmitResult.OK

        fun close() {
            sink.tryEmitComplete()
        }
    }
}
