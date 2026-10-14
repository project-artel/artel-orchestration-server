package kr.artel.orchestration.settings.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.settings.config.BedrockProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient

/** 확인 결과의 `error` 상한. Bedrock 의 오류 문구는 길 때가 있다. */
private const val MAX_ERROR_LENGTH = 300

/** Converse 한 번의 결과. [error] 는 실패했을 때만 있다. */
data class BedrockConverseOutcome(val reachable: Boolean, val error: String?)

/**
 * Bedrock runtime 의 Converse 를 한 번 부른다. key 가 그 model 에 닿는지만 본다.
 *
 * 요청은 `maxTokens` 16 에 "ping" 한 줄이다. key 는 `Authorization: Bearer` 헤더에만 싣는다. WebClient 의 DEBUG
 * 로그는 헤더를 남기지 않는다(`enableLoggingRequestDetails` 가 꺼져 있다).
 */
@Component
class BedrockRuntimeClient(properties: BedrockProperties, private val objectMapper: ObjectMapper) {
    private val logger = LoggerFactory.getLogger(BedrockRuntimeClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl(properties.resolvedRuntimeBaseUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(properties.checkTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.checkTimeout.toMillis().toInt())
            )
        )
        .build()

    /** [modelId] 는 `bedrock/` 를 뗀 Bedrock model id 다(`us.openai.gpt-6.1-sol`). */
    suspend fun converse(modelId: String, apiKey: String): BedrockConverseOutcome {
        return try {
            webClient.post().uri("/model/{modelId}/converse", modelId)
                .header("Authorization", "Bearer $apiKey")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(PING_REQUEST)
                .retrieve().toBodilessEntity().awaitSingle()
            BedrockConverseOutcome(reachable = true, error = null)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: WebClientResponseException) {
            val message = providerMessage(error.responseBodyAsString)
                ?: "Bedrock answered ${error.statusCode.value()}"
            BedrockConverseOutcome(reachable = false, error = message.take(MAX_ERROR_LENGTH))
        } catch (error: Exception) {
            // 예외 메시지에는 요청 주소가 담길 뿐 key 는 없다. 그래도 로그에는 종류만 남긴다.
            logger.warn("Bedrock check could not reach Bedrock for {}: {}", modelId, error.javaClass.simpleName)
            val reason = listOfNotNull(error.javaClass.simpleName, error.message).joinToString(": ")
            BedrockConverseOutcome(reachable = false, error = reason.take(MAX_ERROR_LENGTH))
        }
    }

    /** Bedrock 의 오류 본문은 `{"message": "..."}` 이고, 몇몇 오류는 `Message` 로 온다. */
    private fun providerMessage(body: String): String? = try {
        val node = objectMapper.readTree(body)
        (node?.get("message") ?: node?.get("Message"))?.takeIf { it.isTextual }?.asText()
    } catch (error: Exception) {
        null
    }

    private companion object {
        val PING_REQUEST = mapOf(
            "messages" to listOf(mapOf("role" to "user", "content" to listOf(mapOf("text" to "ping")))),
            "inferenceConfig" to mapOf("maxTokens" to 16)
        )
    }
}
