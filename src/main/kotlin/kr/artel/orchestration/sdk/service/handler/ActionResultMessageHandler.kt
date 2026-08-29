package kr.artel.orchestration.sdk.service.handler

import kr.artel.orchestration.contentmap.capture.ScreenCaptureResultRouter
import kr.artel.orchestration.contentmap.scan.ScanResultRouter
import kr.artel.orchestration.qa.service.QaSdkBridgeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession

/**
 * ACTION_RESULT 메시지를 그 결과의 주인에게 넘기는 핸들러 전략.
 *
 * 주인이 셋이다. 원래 하나(QA)였고, 근거 스캔이 붙었고, 이제 화면 `capture` 가 붙었다.
 *
 * **가르는 축이 둘로 갈린다.**
 *
 * - [ScanResultRouter] — 액션 이름. `action` 칸이 `scan_evidence` 인 프레임만 집어 간다.
 *   그 이름은 orchestration 만 보내므로 이름 하나로 충분하다
 * - [ScreenCaptureResultRouter] — 우리가 발급한 바깥 `action` 번호. `capture_screen` 은 **agent 도
 *   보내는** 이름이라 이름으로 가르면 agent 가 시킨 `capture` 의 결과를 가로챈다 (ARTEL-456)
 *
 * 둘 다 자기 것이 아닌 프레임에는 `false` 를 돌려주어 지금까지처럼 QA 브리지로 간다. QA 가 지금
 * 주고받는 모양의 프레임은 한 글자도 달라지지 않는다.
 *
 * 활성 QA try가 없으면 QA 쪽은 아무 데도 보내지 않는다. 예전에는 Agent Server의 HTTP
 * 엔드포인트로 POST하는 폴백이 있었으나, 그 엔드포인트는 존재하지 않는다.
 */
@Component
class ActionResultMessageHandler(
    private val scanResults: ScanResultRouter,
    private val screenCaptures: ScreenCaptureResultRouter,
    private val qaBridge: QaSdkBridgeService
) : SdkMessageHandler {

    private val logger = LoggerFactory.getLogger(ActionResultMessageHandler::class.java)

    override val messageType: String = "ACTION_RESULT"

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        logger.info("액션 결과 수신 [instanceId: $instanceId]: $payloadText")

        val gameInstanceId = instanceId.toLong()
        if (scanResults.handle(gameInstanceId, payloadText)) {
            return
        }

        if (screenCaptures.handle(gameInstanceId, payloadText)) {
            return
        }

        qaBridge.routeActionResult(gameInstanceId, payloadText)
    }
}
