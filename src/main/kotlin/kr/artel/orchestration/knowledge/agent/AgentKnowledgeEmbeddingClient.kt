package kr.artel.orchestration.knowledge.agent

import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * [KnowledgeEmbeddingAgent]의 HTTP 구현. `AgentExtractClient`와 같은 자리·같은 방식이다.
 *
 * 실패를 여기서 삼키지 않고 그대로 올린다. 무엇을 재시도로 볼지, 배치를 쪼갤지는 호출자(백필 워커)가
 * 큐 상태를 보며 정할 일이고, 클라이언트가 미리 판단하면 워커가 실패를 기록할 기회를 잃는다.
 */
@Component
class AgentKnowledgeEmbeddingClient(
    @Value("\${artel.agent.base-url}") private val agentBaseUrl: String,
    @Value("\${artel.knowledge.backfill.timeout:PT2M}") private val responseTimeout: Duration
) : KnowledgeEmbeddingAgent {

    private val logger = LoggerFactory.getLogger(AgentKnowledgeEmbeddingClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(responseTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            )
        )
        .build()

    override suspend fun generateQueries(items: List<KnowledgeQueryItem>): KnowledgeQueriesResponse {
        logger.debug("Agent /knowledge-queries 요청: {}건", items.size)
        return webClient.post()
            .uri("$agentBaseUrl/knowledge-queries")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(KnowledgeQueriesRequest(items))
            .retrieve()
            .awaitBody()
    }
}
