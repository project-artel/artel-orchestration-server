package kr.artel.orchestration.sdk.service.handler

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.game.service.GameFunctionCatalogService
import kr.artel.orchestration.qa.service.QaSdkBridgeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession

/**
 * SDK 가 등록한 cheat · behavior 목록을 빌드에 저장한다.
 *
 * 연결마다 `DEVICE_CONTEXT` 바로 뒤에 한 번 오고, 런타임에 목록이 바뀌면 초당 최대 한 번 다시 온다. 저장은
 * [GameFunctionCatalogService] 가 하고, 여기는 파싱과 위임만 한다. 런이 도는 중에 와도 이미 연 세션의 목록은 바뀌지
 * 않는다 — 세션이 열릴 때 거른 목록이 그 런의 허용 범위다.
 */
@Component
class GameFunctionCatalogMessageHandler(
    private val objectMapper: ObjectMapper,
    private val catalogService: GameFunctionCatalogService
) : SdkMessageHandler {

    private val logger = LoggerFactory.getLogger(GameFunctionCatalogMessageHandler::class.java)

    override val messageType: String = "GAME_FUNCTION_CATALOG"

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        val payload = objectMapper.readTree(payloadText)
        // 본문은 찍지 않는다. 항목이 수십 개이고 연결마다 오므로 크기만 남긴다.
        logger.info("game function catalog 수신 [instanceId: $instanceId]: ${payloadText.length}자")
        catalogService.record(instanceId.toLong(), payload)
    }
}

/**
 * behavior 가 끝났다는 SDK 프레임을 타임라인에 한 줄 남기고 agent 에게 알린다.
 *
 * 변환하지 않는다. 계약 4절에 따라 payload 는 SDK 객체에서 `type` 만 뺀 것이다.
 */
@Component
class BehaviorEndedMessageHandler(
    private val qaBridge: QaSdkBridgeService
) : SdkMessageHandler {

    private val logger = LoggerFactory.getLogger(BehaviorEndedMessageHandler::class.java)

    override val messageType: String = "BEHAVIOR_ENDED"

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        logger.info("behavior 종료 수신 [instanceId: $instanceId]: $payloadText")
        qaBridge.routeBehaviorEnded(instanceId.toLong(), payloadText)
    }
}
