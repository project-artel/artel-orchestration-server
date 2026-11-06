package kr.artel.orchestration.common.embedding.agent

import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import kr.artel.orchestration.settings.config.AgentServerProperties
import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient

/**
 * [EmbeddingClient]의 HTTP 구현(`POST {agent}/embed`). `AgentExtractClient`와 같은 자리·같은 방식이다.
 *
 * 실패를 여기서 삼키지 않고 그대로 올린다. 무엇을 재시도로 볼지, 배치를 쪼갤지는 호출자(백필 워커·
 * 검색 서비스)가 정할 일이다.
 */
@Component
class AgentEmbeddingClient(
    properties: AgentServerProperties,
    backfill: KnowledgeBackfillProperties
) : EmbeddingClient {
    private val agentBaseUrl = properties.baseUrl
    private val responseTimeout = properties.embedTimeout ?: backfill.timeout

    private val logger = LoggerFactory.getLogger(AgentEmbeddingClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(responseTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            )
        )
        // 임베딩 응답은 배치당 (건수 × 차원)개의 float라 기본 256KB 코덱 버퍼를 쉽게 넘긴다
        // (예: 64건 × 1024차원 ≈ 1MB+). 넉넉히 32MB로 올리지 않으면 200 응답을 디코딩하지 못해
        // 백필이 매 배치 통째로 실패한다.
        .codecs { it.defaultCodecs().maxInMemorySize(32 * 1024 * 1024) }
        .build()

    override suspend fun embed(texts: List<String>): EmbedResponse {
        logger.debug("Agent /embed 요청: {}건", texts.size)
        return webClient.post()
            .uri("$agentBaseUrl/embed")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(EmbedRequest(texts))
            .retrieve()
            .awaitBody()
    }
}
