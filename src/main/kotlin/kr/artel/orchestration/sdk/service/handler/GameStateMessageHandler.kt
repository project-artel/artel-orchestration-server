package kr.artel.orchestration.sdk.service.handler

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdk.dto.SdkGameState
import kr.artel.orchestration.sdk.service.AgentClient
import kr.artel.orchestration.sdk.service.GameStateTransformer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

/**
 * GAME_STATE 메시지를 정제하여 Agent Server로 중계 전송하는 핸들러 전략
 */
@Component
class GameStateMessageHandler(
    private val objectMapper: ObjectMapper,
    private val agentClient: AgentClient
) : SdkMessageHandler {

    private val logger = LoggerFactory.getLogger(GameStateMessageHandler::class.java)

    override val messageType: String = "GAME_STATE"

    override fun handle(sdkId: String, payloadText: String, session: WebSocketSession): Mono<Void> {
        return Mono.fromCallable {
            val sdkGameState = objectMapper.readValue(payloadText, SdkGameState::class.java)
            val agentGameState = GameStateTransformer.toAgentGameState(sdkGameState)
            sdkGameState to agentGameState
        }.flatMap { (sdkGameState, agentGameState) ->
            val compactJson = objectMapper.writeValueAsString(agentGameState)
            logger.info("게임 상태 수신 및 정제 완료 [sdkId: $sdkId]: 씬=${agentGameState.scene}, observables 수=${agentGameState.observables.size}, interactables 수=${agentGameState.interactables.size}")
            logger.info("정제 결과 JSON: $compactJson")

            agentClient.sendState(agentGameState)
                .onErrorResume { err ->
                    logger.error("Agent Server 상태 전송 최종 실패 처리: ${err.message}")
                    Mono.empty()
                }
                .then()
        }
    }
}
