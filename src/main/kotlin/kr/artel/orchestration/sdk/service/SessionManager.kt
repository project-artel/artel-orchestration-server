package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdk.dto.CommandDto
import kr.artel.orchestration.sdk.dto.MessageType
import kr.artel.orchestration.sdk.dto.WebSocketEnvelope
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * 현재 연결된 웹소켓 세션들을 관리하고, 서버에서 클라이언트로 메시지(커맨드)를 전송하는 서비스
 */
@Service
class SessionManager(private val objectMapper: ObjectMapper) {
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    fun registerSession(sdkId: String, session: WebSocketSession) {
        sessions[sdkId] = session
    }

    fun removeSession(sdkId: String) {
        sessions.remove(sdkId)
    }

    fun getSession(sdkId: String): WebSocketSession? {
        return sessions[sdkId]
    }

    fun hasSession(sdkId: String): Boolean {
        return sessions.containsKey(sdkId)
    }

    fun sendCommand(sdkId: String, command: CommandDto): Mono<Void> {
        val session = sessions[sdkId] ?: return Mono.error(
            IllegalArgumentException("sdkId에 해당하는 활성화된 웹소켓 세션이 없습니다: $sdkId")
        )
        
        return Mono.fromCallable {
            val commandJson = objectMapper.writeValueAsString(command)
            val envelope = WebSocketEnvelope(type = MessageType.COMMAND, payload = commandJson)
            objectMapper.writeValueAsString(envelope)
        }.flatMap { messageJson ->
            val wsMessage = session.textMessage(messageJson)
            session.send(Mono.just(wsMessage))
        }
    }
}
