package kr.artel.orchestration.sdk.service.handler

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdk.dto.SdkGameState
import kr.artel.orchestration.qa.service.QaSdkBridgeService
import kr.artel.orchestration.sdk.service.GameStateTransformer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession

/**
 * GAME_STATE 메시지를 정제하여 QA 브리지로 중계하는 핸들러 전략.
 *
 * 활성 QA try가 없으면 정제 결과만 로그로 남기고 끝난다. 예전에는 Agent Server의
 * HTTP 엔드포인트로 POST하는 폴백이 있었으나, 그 엔드포인트는 존재하지 않는다.
 */
@Component
class GameStateMessageHandler(
    private val objectMapper: ObjectMapper,
    private val qaBridge: QaSdkBridgeService
) : SdkMessageHandler {

    private val logger = LoggerFactory.getLogger(GameStateMessageHandler::class.java)

    override val messageType: String = "GAME_STATE"

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        val sdkGameState = objectMapper.readValue(payloadText, SdkGameState::class.java)
        val agentGameState = GameStateTransformer.toAgentGameState(sdkGameState)

        val compactJson = objectMapper.writeValueAsString(agentGameState)
        logger.info("게임 상태 수신 및 정제 완료 [instanceId: $instanceId]: 씬=${agentGameState.scene}, observables 수=${agentGameState.observables.size}, interactables 수=${agentGameState.interactables.size}")
        logger.info("정제 결과 JSON: $compactJson")

        qaBridge.routeGameState(instanceId.toLong(), sdkGameState.id.toString(), agentGameState)
    }
}
