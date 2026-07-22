package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
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
 */
@Service
class SessionManager(private val objectMapper: ObjectMapper) {
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    /** 이미 다른 연결이 자리를 잡고 있으면 false다. 호출자가 새 연결을 거절한다. */
    fun registerSession(instanceId: String, session: WebSocketSession): Boolean {
        return sessions.putIfAbsent(instanceId, session) == null
    }

    /** 자기가 등록해 둔 세션일 때만 지운다. */
    fun removeSession(instanceId: String, session: WebSocketSession) {
        sessions.remove(instanceId, session)
    }

    fun getSession(instanceId: String): WebSocketSession? {
        return sessions[instanceId]
    }

    fun hasSession(instanceId: String): Boolean {
        return sessions.containsKey(instanceId)
    }

    fun sendAction(instanceId: String, action: ActionResponseDto): Mono<Void> {
        val session = sessions[instanceId] ?: return Mono.error(
            IllegalArgumentException("게임 인스턴스에 해당하는 활성화된 웹소켓 세션이 없습니다: $instanceId")
        )

        return Mono.fromCallable {
            objectMapper.writeValueAsString(action)
        }.flatMap { messageJson ->
            val wsMessage = session.textMessage(messageJson)
            session.send(Mono.just(wsMessage))
        }
    }
}
