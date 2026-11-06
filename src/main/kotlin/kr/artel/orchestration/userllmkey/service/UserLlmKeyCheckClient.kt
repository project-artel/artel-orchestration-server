package kr.artel.orchestration.userllmkey.service

import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.settings.config.AnthropicProperties
import kr.artel.orchestration.settings.config.LlmKeyProperties
import kr.artel.orchestration.settings.config.OpenAiProperties
import kr.artel.orchestration.settings.service.ANTHROPIC_VERSION
import kr.artel.orchestration.userllmkey.entity.LlmKeyProvider
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.time.Duration

/** 서비스가 key 를 어떻게 받았는지. */
enum class UserLlmKeyCheck {
    /** 200, 또는 429. 429 는 key 를 알아본 뒤 요청 수를 막은 것이라 key 는 유효하다. */
    ACCEPTED,

    /** 401. */
    INVALID,

    /** 403. key 는 있지만 그 읽기 권한이 없다. */
    PERMISSION_DENIED,

    /** 닿지 못했거나, 시간을 넘겼거나, 그 밖의 status. key 에 대해 아무것도 모른다. */
    UNAVAILABLE
}

/**
 * 사용자가 넣은 key 로 서비스에 읽기 호출을 한 번 한다. model 을 부르지 않아 사용자에게 비용이 들지 않는다.
 * 대신 크레딧이 없는 key 도 통과한다.
 *
 * | 서비스 | 호출 |
 * |---|---|
 * | `anthropic` | `GET {artel.anthropic.base-url}/v1/models` (`x-api-key`, `anthropic-version`) |
 * | `openai` | `GET {artel.openai.base-url}/v1/models` (`Authorization: Bearer`) |
 * | `openrouter` | `GET {artel.llm.openrouter-base-url}/key` (`Authorization: Bearer`) |
 *
 * OpenRouter 는 `/models` 가 아니라 `/key` 다. `/models` 는 공개라 아무 key 에나 200 을 준다. `/key` 의 응답
 * 본문은 읽지 않는다 — 그 안의 `label` 이 key 의 앞뒤 몇 글자라 디코드하면 DEBUG 로그에 남는다. 다른 두 서비스의
 * 본문도 status 만 보면 되므로 읽지 않는다.
 *
 * key 는 헤더에만 싣는다. WebClient 의 DEBUG 로그는 헤더를 남기지 않는다(`enableLoggingRequestDetails` 가
 * 꺼져 있다). 이 클래스의 로그에는 서비스와 status, 예외 종류만 남긴다.
 */
@Component
class UserLlmKeyCheckClient(
    anthropicProperties: AnthropicProperties,
    openAiProperties: OpenAiProperties,
    llmKeyProperties: LlmKeyProperties
) {
    private val logger = LoggerFactory.getLogger(UserLlmKeyCheckClient::class.java)

    private val anthropicClient = webClient(anthropicProperties.baseUrl, anthropicProperties.checkTimeout)
    private val openAiClient = webClient(openAiProperties.baseUrl, openAiProperties.checkTimeout)
    private val openRouterClient = webClient(llmKeyProperties.openrouterBaseUrl, llmKeyProperties.checkTimeout)

    suspend fun check(provider: LlmKeyProvider, apiKey: String): UserLlmKeyCheck {
        val request = when (provider) {
            LlmKeyProvider.ANTHROPIC -> anthropicClient.get().uri("/v1/models")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
            LlmKeyProvider.OPENAI -> openAiClient.get().uri("/v1/models")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
            LlmKeyProvider.OPENROUTER -> openRouterClient.get().uri("/key")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
        }
        val status = try {
            // 본문을 읽지 않으면 exchangeToMono 가 연결을 돌려주기 전에 비운다.
            request.exchangeToMono { response -> Mono.just(response.statusCode().value()) }.awaitSingle()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // 예외 메시지에는 요청 주소가 담길 뿐 key 는 없다. 그래도 로그에는 종류만 남긴다.
            logger.warn("User {} key check could not reach the service: {}", provider.wire, error.javaClass.simpleName)
            return UserLlmKeyCheck.UNAVAILABLE
        }
        return when (status) {
            200, 429 -> UserLlmKeyCheck.ACCEPTED
            401 -> UserLlmKeyCheck.INVALID
            403 -> UserLlmKeyCheck.PERMISSION_DENIED
            else -> {
                logger.warn("User {} key check answered status {}", provider.wire, status)
                UserLlmKeyCheck.UNAVAILABLE
            }
        }
    }

    private fun webClient(baseUrl: String, timeout: Duration): WebClient = WebClient.builder()
        .baseUrl(baseUrl.trimEnd('/'))
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(timeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout.toMillis().toInt())
            )
        )
        .build()
}
