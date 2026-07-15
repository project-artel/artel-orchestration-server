package kr.artel.orchestration.sdk.controller

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdk.dto.ClassMetadata
import kr.artel.orchestration.sdk.dto.MessageType
import kr.artel.orchestration.sdk.dto.ReportDto
import kr.artel.orchestration.sdk.dto.WebSocketEnvelope
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.sdk.service.SdkIdVerificationService
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
    private val sessionManager: SessionManager
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(SdkWebSocketHandler::class.java)

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
            .doOnNext { message ->
                val payloadText = message.payloadAsText
                try {
                    val envelope = objectMapper.readValue(payloadText, WebSocketEnvelope::class.java)
                    when (envelope.type) {
                        MessageType.SCAN -> {
                            val metadata = objectMapper.readValue(envelope.payload, ClassMetadata::class.java)
                            logger.info("메타데이터 수신 [sdkId: $sdkId]: 클래스명=${metadata.className}, 변수 수=${metadata.variables.size}, 메서드 수=${metadata.methods.size}")
                        }
                        MessageType.REPORT -> {
                            val report = objectMapper.readValue(envelope.payload, ReportDto::class.java)
                            logger.info("실행 결과 보고 수신 [sdkId: $sdkId]: 호출메서드=${report.methodName}, 변수=${report.variableName}, 실행전=${report.beforeValue}, 실행후=${report.afterValue}")
                        }
                        else -> {
                            logger.warn("정의되지 않은 메시지 타입 수신 [sdkId: $sdkId]: ${envelope.type}")
                        }
                    }
                } catch (e: Exception) {
                    logger.error("메시지 처리 에러 [sdkId: $sdkId]: ${e.message}", e)
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
