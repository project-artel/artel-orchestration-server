package kr.artel.orchestration.sdk.service.handler

import kr.artel.orchestration.qa.service.QaSdkBridgeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

/**
 * ACTION_RESULT 메시지를 가공 없이 QA 브리지로 전달하는 핸들러 전략.
 *
 * 활성 QA try가 없으면 아무 데도 보내지 않는다. 예전에는 Agent Server의 HTTP
 * 엔드포인트로 POST하는 폴백이 있었으나, 그 엔드포인트는 존재하지 않는다.
 */
@Component
class ActionResultMessageHandler(
    private val qaBridge: QaSdkBridgeService
) : SdkMessageHandler {

    private val logger = LoggerFactory.getLogger(ActionResultMessageHandler::class.java)

    override val messageType: String = "ACTION_RESULT"

    override fun handle(instanceId: String, payloadText: String, session: WebSocketSession): Mono<Void> {
        logger.info("액션 결과 수신 [instanceId: $instanceId]: $payloadText")

        return qaBridge.routeActionResult(instanceId.toLong(), payloadText).then()
    }
}
