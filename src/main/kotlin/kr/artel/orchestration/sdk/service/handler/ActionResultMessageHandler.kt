package kr.artel.orchestration.sdk.service.handler

import kr.artel.orchestration.sdk.service.AgentClient
import kr.artel.orchestration.qa.service.QaSdkBridgeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

/**
 * ACTION_RESULT 메시지를 가공 없이 그대로 Agent Server로 전달하는 핸들러 전략
 */
@Component
class ActionResultMessageHandler(
    private val agentClient: AgentClient,
    private val qaBridge: QaSdkBridgeService
) : SdkMessageHandler {

    private val logger = LoggerFactory.getLogger(ActionResultMessageHandler::class.java)

    override val messageType: String = "ACTION_RESULT"

    override fun handle(instanceId: String, payloadText: String, session: WebSocketSession): Mono<Void> {
        logger.info("액션 결과 수신 [instanceId: $instanceId]: $payloadText")
        
        return qaBridge.routeActionResult(instanceId.toLong(), payloadText)
            .flatMap { handledByQa ->
                if (handledByQa) Mono.empty()
                else agentClient.sendResult(payloadText).then()
            }
    }
}
