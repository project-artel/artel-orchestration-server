package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 같은 인스턴스의 새 연결에 자리를 넘기며 앞 세션을 닫는 코드. SDK 는 4001 이 아닌 닫힘에서는 다시 연결한다. */
internal val SESSION_REPLACED = CloseStatus(4009, "Replaced by a new connection")

/**
 * 연결 등록 결과.
 *
 * @property replaced 같은 인스턴스의 앞 세션이 ping 에 답하지 않아 그 자리를 넘겨받았는지
 */
data class SdkRegistration(val outbound: Flux<String>, val replaced: Boolean)

/**
 * 현재 연결된 웹소켓 세션들을 관리하고, 서버에서 클라이언트로 메시지(액션)를 전송하는 서비스
 *
 * 키는 게임 인스턴스 id다. 자격증명(SDK 토큰)을 키로 삼으면 세션 맵과 로그, 에이전트가
 * 호출하는 URL까지 자격증명이 퍼진다.
 *
 * 등록과 제거가 모두 "그 세션이 맞을 때만" 동작한다. 한 인스턴스에 두 연결이 겹칠 때
 * 나중 연결이 앞 연결을 조용히 덮어쓰면, 앞 소켓은 닫히지도 않은 채 도달 불가능해지고
 * 나중에 끊길 때 살아 있는 뒤 연결의 자리를 대신 비운다. 개발 중 재실행이 잦으면 이 순서가
 * 흔해진다.
 *
 * 나가는 메시지는 세션마다 하나뿐인 sink를 거친다. `WebSocketSession.send`를 호출할 때마다
 * 새로 구독을 붙이는 방식은 발신자가 ACTION 하나였을 때만 통했다. 화면 스트리밍이 붙으면서
 * ICE 후보가 1초 안에 여러 개씩 쏟아지고 그것이 ACTION 전달과 겹치므로, 전송을 한 줄로 모아
 * 두지 않으면 같은 세션에 동시 write가 걸린다.
 */
@Service
class SessionManager(private val objectMapper: ObjectMapper,
    private val demonstrationGuard: kr.artel.orchestration.demonstration.DemonstrationGuard? = null,
    private val demonstrationRuntime: kr.artel.orchestration.demonstration.DemonstrationRuntime? = null) {

    private val logger = LoggerFactory.getLogger(SessionManager::class.java)
    private val sessions = ConcurrentHashMap<String, SdkSession>()

    fun sessionIdentity(instanceId: String): Any? = sessions[instanceId]?.session

    /** frame ACK는 응답을 읽지 않는 SDK 앞에서도 최신 하나만 남긴다. */
    fun sendMediaAck(instanceId: String, payload: Any) {
        sessions[instanceId]?.ack(objectMapper.writeValueAsString(payload))
    }

    /**
     * 이미 다른 연결이 자리를 잡고 있으면 null이다. 호출자가 새 연결을 거절한다.
     *
     * 돌려주는 [Flux]는 이 세션으로 나갈 메시지들이다. 호출자가 반드시 `session.send`에
     * 물려야 한다. 물리지 않으면 등록은 됐는데 아무것도 나가지 않는 세션이 된다.
     */
    fun register(instanceId: String, session: WebSocketSession): Flux<String>? {
        val registered = SdkSession(session, liveness = null)
        return if (sessions.putIfAbsent(instanceId, registered) == null) {
            registered.outbound()
        } else {
            null
        }
    }

    /**
     * [register] 와 같되, 자리에 앞 세션이 있으면 그 세션에 ping 을 한 번 보낸다(ARTEL-1234). [probeTimeout] 안에
     * 답이 오면 지금처럼 null 이다 — 진행 중인 QA 세션이 우연한 두 번째 실행에 소켓을 빼앗기지 않는다. 답이 없으면
     * 앞 세션을 [SESSION_REPLACED] 로 닫고 새 연결이 자리를 잡는다.
     *
     * 게임 쪽 소켓이 close frame 없이 끊기면(Windows 와 WSL 사이 `An existing connection was forcibly closed`)
     * heartbeat 가 앞 세션을 닫기까지 최대 60초가 걸렸고, 그동안 SDK 의 재연결은 모두 4002 로 거절됐다.
     *
     * [liveness] 가 없는 앞 세션([register] 로 들어온 것)에는 물을 수 없으므로 살아 있다고 보고 거절한다.
     */
    suspend fun registerReplacingSilent(
        instanceId: String,
        session: WebSocketSession,
        liveness: SdkLiveness,
        probeTimeout: Duration
    ): SdkRegistration? {
        val candidate = SdkSession(session, liveness)
        val incumbent = sessions.putIfAbsent(instanceId, candidate)
            ?: return SdkRegistration(candidate.outbound(), replaced = false)
        val incumbentLiveness = incumbent.liveness ?: return null
        if (incumbentLiveness.answersPing(probeTimeout)) return null

        if (!sessions.replace(instanceId, incumbent, candidate)) {
            // 기다리는 사이 앞 세션이 스스로 빠졌거나 다른 연결이 먼저 자리를 넘겨받았다.
            return if (sessions.putIfAbsent(instanceId, candidate) == null) {
                SdkRegistration(candidate.outbound(), replaced = false)
            } else {
                null
            }
        }
        logger.warn("SDK 세션 교체 [instanceId: $instanceId]: 앞 세션이 ${probeTimeout.toMillis()}ms 안에 ping 에 답하지 않음")
        incumbent.close()
        incumbent.session.close(SESSION_REPLACED).subscribe(
            {},
            { error -> logger.warn("교체된 SDK 세션 닫기 실패 [instanceId: $instanceId]: ${error.message}") }
        )
        return SdkRegistration(candidate.outbound(), replaced = true)
    }

    /** 자기가 등록해 둔 세션일 때만 지운다. */
    fun removeSession(instanceId: String, session: WebSocketSession) {
        val registered = sessions[instanceId] ?: return
        if (registered.session !== session) {
            return
        }

        if (sessions.remove(instanceId, registered)) {
            demonstrationRuntime?.disconnected(instanceId, session.id)
            registered.close()
        }
    }

    fun getSession(instanceId: String): WebSocketSession? = sessions[instanceId]?.session

    /**
     * 전송 큐에 들어갔지만 아직 전송 파이프라인이 꺼내 가지 않은 메시지 수. 세션이 없으면 null 이다.
     *
     * 큐는 크기 제한이 없어 가득 차서 거절하는 일은 없다. 이 값이 계속 늘면 소켓 write 가 막혀 있다는 뜻이다(ARTEL-1235).
     */
    fun pendingOutbound(instanceId: String): Int? = sessions[instanceId]?.pending()

    fun hasSession(instanceId: String): Boolean = sessions.containsKey(instanceId)

    suspend fun sendAction(instanceId: String, action: ActionResponseDto): Boolean {
        val guard = demonstrationGuard
        return if (guard == null) send(instanceId, action)
        else guard.dispatch(instanceId.toLong(), action.demonstration) { send(instanceId, action) }
    }

    /**
     * 임의의 메시지를 연결된 SDK로 보낸다.
     *
     * 실제 write는 세션의 전송 파이프라인이 한다. 이 함수가 반환됐다는 것은 "보냈다"가 아니라
     * "보낼 줄에 세웠다"는 뜻이다. 소켓에 실제로 나갔는지까지 확인하려면 SDK의 응답을 봐야
     * 하는데, 이 프로토콜에서 그 확인이 필요한 메시지는 없다.
     */
    suspend fun send(instanceId: String, payload: Any): Boolean {
        val json = objectMapper.writeValueAsString(payload)
        return sendRaw(instanceId, json)
    }

    /**
     * 이미 JSON인 문자열을 그대로 보낸다.
     *
     * 시그널링 중계용이다. 브라우저가 만든 SDP/ICE를 서버가 다시 객체로 읽었다가 직렬화하면,
     * 서버가 모르는 필드가 조용히 사라진다. WebRTC 협상에서 그 손실은 "연결은 되는데 미디어가
     * 없다"처럼 원인을 찾기 어려운 모습으로 나타나므로, 중계는 원문을 건드리지 않는다.
     *
     * 세션이 없으면 예외를 던진다. 호출자(컨트롤러)가 이를 404로 옮긴다.
     *
     * @return 전송 큐에 넣었으면 true. 세션은 있는데 전송 파이프라인이 이미 끝나 큐가 닫혔으면 false 다 — 소켓
     *   write 가 실패해 전송 쪽만 끝나고 수신 쪽은 살아 있는 세션이 그렇다. 그 세션은 SDK 가 다시 연결하면
     *   교체된다(ARTEL-1234). 이 값을 보지 않는 호출자는 지금처럼 경고 로그만 남는다
     */
    suspend fun sendRaw(instanceId: String, json: String): Boolean {
        val registered = sessions[instanceId]
            ?: throw IllegalArgumentException("게임 인스턴스에 해당하는 활성화된 웹소켓 세션이 없습니다: $instanceId")

        val result = registered.emit(json)
        if (result != Sinks.EmitResult.OK) {
            logger.warn("SDK 메시지 전송 실패 [instanceId: $instanceId]: 전송 큐가 닫혔습니다 ($result).")
        }
        return result == Sinks.EmitResult.OK
    }

    /**
     * 세션 하나와 그 세션의 전송 큐.
     *
     * unicast인 이유는 구독자가 정확히 하나(전송 파이프라인)이기 때문이다. multicast로 두면
     * 구독이 붙기 전에 넣은 메시지가 조용히 버려진다.
     */
    private class SdkSession(val session: WebSocketSession, val liveness: SdkLiveness?) {
        private val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        private val pending = AtomicInteger()
        private val mediaAcks = kr.artel.orchestration.stream.service.LatestMediaMessages()

        fun outbound(): Flux<String> = Flux.merge(1, sink.asFlux().doOnNext { pending.decrementAndGet() }, mediaAcks.flux)

        fun ack(json: String) { mediaAcks.emit(json) }

        /**
         * 발신자가 여럿이다 — agent 의 ACTION, `RUN_STATUS`, 판독 켜기, 화면 스트리밍 시그널링. unicast sink 의
         * `tryEmitNext` 는 두 thread 가 동시에 부르면 한쪽에 `FAIL_NON_SERIALIZED` 를 돌려주고 그 메시지를 버린다.
         * 그래서 넣는 것을 한 줄로 세운다.
         */
        fun emit(json: String): Sinks.EmitResult = synchronized(sink) {
            sink.tryEmitNext(json).also { result -> if (result == Sinks.EmitResult.OK) pending.incrementAndGet() }
        }

        fun pending(): Int = pending.get()

        fun close() {
            synchronized(sink) { sink.tryEmitComplete() }
            mediaAcks.close()
        }
    }
}
