package kr.artel.orchestration.sdk.service.handler

import kr.artel.orchestration.contentmap.scan.ScanResultRouter
import kr.artel.orchestration.qa.service.QaSdkBridgeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession

/**
 * ACTION_RESULT 메시지를 그 결과의 주인에게 넘기는 핸들러 전략.
 *
 * 주인이 둘이다. 원래 하나(QA)였고, 이제 근거 스캔이 하나 더 붙었다.
 *
 * **가르는 축은 액션 이름이다.** [ScanResultRouter] 가 `action` 칸이 `scan_evidence` 인 프레임만
 * 집어 가고, 그 밖에는 전부 `false` 를 돌려주어 지금까지처럼 QA 브리지로 간다. `action` 칸이
 * 없는 프레임(= QA 가 지금 주고받는 모양)은 한 글자도 달라지지 않는다.
 *
 * id 로 가르지 않는 이유는 [ScanResultRouter] 의 KDoc 에 있다 — 우리에게 `qa_log` 같은 id
 * 발급처가 없어, 별도 카운터를 두면 값이 겹쳐 QA 결과가 스캔으로 샐 수 있다.
 *
 * 활성 QA try가 없으면 QA 쪽은 아무 데도 보내지 않는다. 예전에는 Agent Server의 HTTP
 * 엔드포인트로 POST하는 폴백이 있었으나, 그 엔드포인트는 존재하지 않는다.
 */
@Component
class ActionResultMessageHandler(
    private val scanResults: ScanResultRouter,
    private val qaBridge: QaSdkBridgeService
) : SdkMessageHandler {

    private val logger = LoggerFactory.getLogger(ActionResultMessageHandler::class.java)

    override val messageType: String = "ACTION_RESULT"

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        logger.info("액션 결과 수신 [instanceId: $instanceId]: $payloadText")

        if (scanResults.handle(instanceId.toLong(), payloadText)) {
            return
        }

        qaBridge.routeActionResult(instanceId.toLong(), payloadText)
    }
}
