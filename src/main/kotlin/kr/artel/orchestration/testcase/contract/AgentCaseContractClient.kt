package kr.artel.orchestration.testcase.contract

import io.netty.channel.ChannelOption
import kr.artel.orchestration.settings.config.AgentServerProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * agent `/case-contracts` 의 HTTP 구현. `AgentKnowledgeEmbeddingClient` 와 같은 자리 · 같은 방식이다.
 *
 * 응답 상한을 넉넉히 둔다 — 용어집은 묶음 순서대로 키워서 TC 가 많으면 수 분이 걸린다. 실패는 삼키지
 * 않고 올린다. 계약을 못 뽑았다고 저작을 막을지는 부르는 쪽이 정한다.
 */
@Component
open class AgentCaseContractClient(properties: AgentServerProperties) {
    private val agentBaseUrl = properties.baseUrl
    private val logger = LoggerFactory.getLogger(AgentCaseContractClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .codecs { it.defaultCodecs().maxInMemorySize(32 * 1024 * 1024) }
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(Duration.ofMinutes(10))
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            )
        )
        .build()

    open suspend fun extract(request: CaseContractRequest): CaseContractResponse {
        logger.info("Agent /case-contracts 요청: TC {}건 [projectId={}]", request.cases.size, request.projectId)
        return webClient.post()
            .uri("$agentBaseUrl/case-contracts")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .awaitBody()
    }

    open suspend fun statement(request: StatementRequest): StatementResponse =
        webClient.post()
            .uri("$agentBaseUrl/case-contracts/statement")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .awaitBody()
}
