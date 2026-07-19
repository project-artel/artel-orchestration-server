package kr.artel.orchestration.sdk.service

import kr.artel.orchestration.sdk.dto.AgentGameState
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * 정제된 게임 상태(AgentGameState)를 Agent Server로 HTTP POST 전송하는 단일 서비스 클라이언트
 */
@Service
class AgentClient(
    @Value("\${artel.agent.base-url}") private val agentBaseUrl: String
) {
    private val logger = LoggerFactory.getLogger(AgentClient::class.java)
    private val webClient = WebClient.create()

    fun sendState(agentGameState: AgentGameState): Mono<String> {
        val targetUrl = "$agentBaseUrl/gamestate/scene"
        logger.info("Agent Server로 상태 전송 시도: url=$targetUrl")
        return webClient.post()
            .uri(targetUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(agentGameState)
            .retrieve()
            .bodyToMono(String::class.java)
            .doOnSuccess { response ->
                logger.info("Agent Server 전송 완료 응답: $response")
            }
            .doOnError { error ->
                logger.error("Agent Server 전송 에러 발생: ${error.message}")
            }
    }

    fun sendResult(actionResultJson: String): Mono<String> {
        val targetUrl = "$agentBaseUrl/gamestate/result"
        logger.info("Agent Server로 액션 결과 전송 시도: url=$targetUrl")
        return webClient.post()
            .uri(targetUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(actionResultJson)
            .retrieve()
            .bodyToMono(String::class.java)
            .doOnSuccess { response ->
                logger.info("Agent Server 결과 전송 완료 응답: $response")
            }
            .doOnError { error ->
                logger.error("Agent Server 결과 전송 에러 발생: ${error.message}")
            }
    }
}
