package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdk.dto.BaseMessage
import kr.artel.orchestration.sdk.service.handler.SdkMessageHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

/**
 * 실시간 웹소켓 연결 수립, 핸드셰이크 시점의 인증(sdkId), 메시지 수신 및 분기를 처리하는 핵심 핸들러
 */
@Component
class SdkWebSocketHandler(
    private val objectMapper: ObjectMapper,
    private val sdkIdVerificationService: SdkIdVerificationService,
    private val sessionManager: SessionManager,
    handlers: List<SdkMessageHandler>
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(SdkWebSocketHandler::class.java)
    private val handlerMap = handlers.associateBy { it.messageType }

    override fun handle(session: WebSocketSession): Mono<Void> {
        val query = session.handshakeInfo.uri.query ?: ""
        val params = query.split("&").associate {
            val parts = it.split("=")
            val key = parts.getOrNull(0) ?: ""
            val value = parts.getOrNull(1) ?: ""
            key to value
        }

        val sdkId = params["sdkId"]
        
        if (sdkId == null || !sdkIdVerificationService.isValid(sdkId)) {
            logger.warn("웹소켓 연결 거부: 누락되거나 유효하지 않은 sdkId '$sdkId'")
            return session.close(CloseStatus(4001, "Invalid or unauthorized sdkId"))
        }

        logger.info("웹소켓 연결 성공 - sdkId: $sdkId")
        sessionManager.registerSession(sdkId, session)

        return session.receive()
            .flatMap { message ->
                val payloadText = message.payloadAsText
                try {
                    val base = objectMapper.readValue(payloadText, BaseMessage::class.java)
                    val handler = handlerMap[base.type]
                    
                    if (handler != null) {
                        handler.handle(sdkId, payloadText, session)
                            .onErrorResume { err ->
                                logger.error("메시지 처리 최종 실패 [type=${base.type}, sdkId=$sdkId]: ${err.message}")
                                Mono.empty()
                            }
                    } else {
                        logger.warn("정의되지 않은 메시지 타입 수신 [sdkId: $sdkId]: ${base.type}")
                        Mono.empty()
                    }
                } catch (e: Exception) {
                    logger.error("메시지 처리 에러 [sdkId: $sdkId]: ${e.message}", e)
                    Mono.empty()
                }
            }
            .doOnError { error ->
                logger.error("웹소켓 에러 발생 [sdkId: $sdkId]: ${error.message}", error)
            }
            .doFinally {
                sessionManager.removeSession(sdkId)
                logger.info("웹소켓 연결 종료 - sdkId: $sdkId")
            }
            .then()
    }
}
