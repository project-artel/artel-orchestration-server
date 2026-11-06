package kr.artel.orchestration.settings.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.settings.config.LlmKeyProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient

/** OpenRouter 가 key 를 받아 준 결과. */
sealed interface OpenRouterListing {
    /** key 가 통했고, 그 key 로 보이는 model id 전부다. */
    data class Reachable(val modelIds: Set<String>) : OpenRouterListing

    /** OpenRouter 가 key 를 401 또는 403 으로 거절했다. */
    data object InvalidKey : OpenRouterListing

    /** 닿지 못했거나 OpenRouter 가 5xx 를 냈다. key 가 틀렸다는 뜻은 아니다. */
    data object Unavailable : OpenRouterListing
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OpenRouterModelsResponse(val data: List<OpenRouterModel> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OpenRouterModel(val id: String)

/**
 * OpenRouter 에 key 가 통하는지와 그 key 로 어떤 model 이 보이는지 묻는다.
 *
 * 세 번 부른다.
 * 1. `GET /key` — key 자체가 유효한지. `GET /models` 는 key 없이도 200 을 주므로 그것만으로는 틀린 key 를
 *    가려내지 못한다. 응답 본문은 읽지 않는다 — 그 안의 `label` 이 key 의 앞뒤 몇 글자라, 디코드하면
 *    DEBUG 로그에 남는다.
 * 2. `GET /models` — chat model 목록.
 * 3. `GET /embeddings/models` — embedding model 목록. 이 경로가 없거나 실패해도 확인은 이어 간다.
 *    그때 embedding model 은 2 의 목록에 있을 때만 닿는 것으로 본다.
 *
 * key 는 `Authorization` 헤더에만 싣는다. WebClient 의 DEBUG 로그는 헤더를 남기지 않는다
 * (`enableLoggingRequestDetails` 가 꺼져 있다).
 */
@Component
class OpenRouterModelClient(properties: LlmKeyProperties) {
    private val logger = LoggerFactory.getLogger(OpenRouterModelClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl(properties.openrouterBaseUrl.trimEnd('/'))
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(properties.checkTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.checkTimeout.toMillis().toInt())
            )
        )
        // 모델 목록은 수백 개라 기본 256KB 를 넘길 수 있다.
        .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
        .build()

    suspend fun list(apiKey: String): OpenRouterListing {
        return try {
            webClient.get().uri("/key")
                .header("Authorization", "Bearer $apiKey")
                .retrieve().toBodilessEntity().awaitSingle()
            val chatModelIds = modelIds("/models", apiKey)
            val embeddingModelIds = try {
                modelIds("/embeddings/models", apiKey)
            } catch (error: WebClientResponseException) {
                emptySet()
            }
            OpenRouterListing.Reachable(chatModelIds + embeddingModelIds)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: WebClientResponseException) {
            if (error.statusCode.value() == HttpStatus.UNAUTHORIZED.value() ||
                error.statusCode.value() == HttpStatus.FORBIDDEN.value()
            ) {
                OpenRouterListing.InvalidKey
            } else {
                logger.warn("OpenRouter key check failed with status {}", error.statusCode.value())
                OpenRouterListing.Unavailable
            }
        } catch (error: Exception) {
            // 메시지에 요청 URL 말고는 담기지 않지만, 혹시 몰라 예외 종류만 남긴다.
            logger.warn("OpenRouter key check could not reach OpenRouter: {}", error.javaClass.simpleName)
            OpenRouterListing.Unavailable
        }
    }

    private suspend fun modelIds(path: String, apiKey: String): Set<String> =
        webClient.get().uri(path)
            .header("Authorization", "Bearer $apiKey")
            .retrieve()
            .bodyToMono(OpenRouterModelsResponse::class.java)
            .awaitSingleOrNull()
            ?.data.orEmpty()
            .mapTo(mutableSetOf()) { it.id }
}
