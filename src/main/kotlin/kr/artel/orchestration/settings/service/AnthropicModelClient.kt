package kr.artel.orchestration.settings.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.settings.config.AnthropicProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient

/** 확인 결과의 `error` 상한. [BedrockRuntimeClient] 와 같다. */
private const val MAX_ERROR_LENGTH = 300

/** `GET /v1/models` 한 번에 받을 수 있는 최대 개수. Anthropic API 의 `limit` 상한이 1000 이다. */
private const val MODEL_PAGE_LIMIT = 1000

/** 요청마다 싣는 `anthropic-version` 헤더 값. 사용자 key 확인(`UserLlmKeyCheckClient`)도 같은 값을 싣는다. */
internal const val ANTHROPIC_VERSION = "2023-06-01"

/** Anthropic API 가 key 를 받아 준 결과. */
sealed interface AnthropicListing {
    /** key 가 통했고, 그 key 로 보이는 Anthropic model id 전부다(`claude-opus-5-5`). */
    data class Reachable(val modelIds: Set<String>) : AnthropicListing

    /** Anthropic API 가 key 를 401 또는 403 으로 거절했다. [error] 는 API 가 준 `error.message` 다. */
    data class InvalidKey(val error: String) : AnthropicListing

    /** 닿지 못했거나 Anthropic API 가 그 밖의 오류를 냈다. key 가 틀렸다는 뜻은 아니다. */
    data class Unavailable(val error: String) : AnthropicListing
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class AnthropicModelsResponse(val data: List<AnthropicModel> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
private data class AnthropicModel(val id: String)

/**
 * Claude API key 로 `GET /v1/models?limit=1000` 을 한 번 부르고, 그 key 로 보이는 model id 를 돌려준다.
 *
 * key 는 `x-api-key` 헤더에만 싣는다. WebClient 의 DEBUG 로그는 헤더를 남기지 않는다
 * (`enableLoggingRequestDetails` 가 꺼져 있다).
 */
@Component
class AnthropicModelClient(properties: AnthropicProperties, private val objectMapper: ObjectMapper) {
    private val logger = LoggerFactory.getLogger(AnthropicModelClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl(properties.baseUrl.trimEnd('/'))
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(properties.checkTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.checkTimeout.toMillis().toInt())
            )
        )
        .build()

    suspend fun list(apiKey: String): AnthropicListing {
        return try {
            val modelIds = webClient.get().uri { it.path("/v1/models").queryParam("limit", MODEL_PAGE_LIMIT).build() }
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .retrieve()
                .bodyToMono(AnthropicModelsResponse::class.java)
                .awaitSingleOrNull()
                ?.data.orEmpty()
                .mapTo(mutableSetOf()) { it.id }
            AnthropicListing.Reachable(modelIds)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: WebClientResponseException) {
            val status = error.statusCode.value()
            val message = (providerMessage(error.responseBodyAsString) ?: "Anthropic API answered $status").take(MAX_ERROR_LENGTH)
            if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
                AnthropicListing.InvalidKey(message)
            } else {
                logger.warn("Claude API key check failed with status {}", status)
                AnthropicListing.Unavailable(message)
            }
        } catch (error: Exception) {
            // 예외 메시지에는 요청 주소가 담길 뿐 key 는 없다. 그래도 로그에는 종류만 남긴다.
            logger.warn("Claude API key check could not reach the Anthropic API: {}", error.javaClass.simpleName)
            val reason = listOfNotNull(error.javaClass.simpleName, error.message).joinToString(": ")
            AnthropicListing.Unavailable(reason.take(MAX_ERROR_LENGTH))
        }
    }

    /** Anthropic API 의 오류 본문은 `{"type":"error","error":{"type":"...","message":"..."}}` 이다. */
    private fun providerMessage(body: String): String? = try {
        objectMapper.readTree(body)?.get("error")?.get("message")?.takeIf { it.isTextual }?.asText()
    } catch (error: Exception) {
        null
    }
}
