package kr.artel.orchestration.referencecontext.agent

import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * Agent Server `POST /extract` 전용 클라이언트.
 *
 * LLM 추출은 수십 초가 걸릴 수 있어 응답 타임아웃을 넉넉히 준다. 리액티브(논블로킹)라
 * 이 대기 동안 스레드를 잡지 않는다. 실제 호출을 담당하는 곳이므로, 연결/응답 실패는
 * 그대로 예외로 올려 상위(추출 코디네이터)가 parse_status=FAILED로 처리하게 둔다.
 */
@Component
class AgentExtractClient(
    @Value("\${artel.agent.base-url}") private val agentBaseUrl: String,
    @Value("\${artel.agent.extract.timeout:PT3M}") private val responseTimeout: Duration,
    @Value("\${artel.agent.extract.model:}") private val model: String?
) {
    private val logger = LoggerFactory.getLogger(AgentExtractClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(responseTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            )
        )
        .build()

    /**
     * presigned URL의 문서를 Agent가 요약해 game_context를 돌려준다.
     *
     * @param sourceUrl presigned GET URL(서명 필수)
     * @param filename 포맷 판별용 파일명
     */
    fun extractGameContext(sourceUrl: String, filename: String): Mono<ExtractResponse> {
        val targetUrl = "$agentBaseUrl/extract"
        val request = ExtractRequest(
            sourceUrl = sourceUrl,
            filename = filename,
            model = model?.ifBlank { null }
        )
        return webClient.post()
            .uri(targetUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(ExtractResponse::class.java)
            .doOnSubscribe { logger.info("Agent /extract 요청: filename={}", filename) }
            .doOnSuccess { logger.info("Agent /extract 성공: filename={}", filename) }
            .doOnError { error -> logger.error("Agent /extract 실패: filename={}, error={}", filename, error.message) }
    }
}
