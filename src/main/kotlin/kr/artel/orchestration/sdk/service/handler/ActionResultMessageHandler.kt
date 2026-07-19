package kr.artel.orchestration.sdk.service.handler

import kr.artel.orchestration.sdk.service.AgentClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

/**
 * ACTION_RESULT 메시지를 가공 없이 그대로 Agent Server로 전달하는 핸들러 전략
 */
@Component
class ActionResultMessageHandler(
    private val agentClient: AgentClient
) : SdkMessageHandler {

    private val logger = LoggerFactory.getLogger(ActionResultMessageHandler::class.java)

    override val messageType: String = "ACTION_RESULT"

    override fun handle(sdkId: String, payloadText: String, session: WebSocketSession): Mono<Void> {
        logger.info("액션 결과 수신 [sdkId: $sdkId]: $payloadText")
        
        return agentClient.sendResult(payloadText)
            .onErrorResume { err ->
                logger.error("Agent Server 결과 전송 최종 실패 처리: ${err.message}")
                Mono.empty()
            }
            .then()
    }
}
