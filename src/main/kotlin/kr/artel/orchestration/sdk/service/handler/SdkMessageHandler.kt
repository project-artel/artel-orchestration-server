package kr.artel.orchestration.sdk.service.handler

import org.springframework.web.reactive.socket.WebSocketSession

/**
 * 웹소켓으로 들어오는 특정 타입의 메시지 처리 전략을 정의하는 인터페이스
 */
interface SdkMessageHandler {
    /**
     * 핸들러가 지원하는 메시지 타입 (ex: "GAME_STATE", "ACTION_RESULT")
     */
    val messageType: String

    /**
     * 메시지를 처리하는 비동기 비즈니스 로직
     *
     * @param instanceId 메시지를 보낸 게임 인스턴스의 id
     * @param payloadText 웹소켓으로 수신한 원본 JSON 텍스트
     * @param session 현재 연결된 웹소켓 세션 객체
     */
    suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession)
}
