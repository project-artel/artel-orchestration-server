package kr.artel.orchestration.settings.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.netty.channel.ChannelOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.artel.orchestration.settings.config.AgentModelsProperties
import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Clock
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
private data class RequiredModelsResponse(val slugs: List<String>? = null)

/**
 * OpenRouter key 하나로 닿아야 하는 model slug 전부를 `artel-agent-server` 에 묻는다.
 * (`GET {artel.agent.base-url}/models/required`)
 *
 * 카탈로그는 agent 가 쥐고 있어서 이 서버는 목록을 따로 들지 않는다. 성공한 응답만
 * [AgentModelsProperties.requiredModelsTtl] 동안 들고 있고, 실패는 들지 않아 다음 호출이 다시 묻는다.
 */
@Component
class RequiredModelsClient(
    private val properties: AgentModelsProperties,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(RequiredModelsClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl(properties.baseUrl.trimEnd('/'))
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(properties.requiredModelsTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.requiredModelsTimeout.toMillis().toInt())
            )
        )
        .build()

    private val refresh = Mutex()
    @Volatile private var cached: CachedSlugs? = null

    /** 닿아야 하는 slug 목록. agent 에 닿지 못했거나 slug 목록이 아닌 응답을 받으면 null 이다. */
    suspend fun slugs(): List<String>? {
        fresh()?.let { return it }
        return refresh.withLock {
            fresh() ?: fetch()?.also {
                cached = CachedSlugs(it, Instant.now(clock).plus(properties.requiredModelsTtl))
            }
        }
    }

    private fun fresh(): List<String>? =
        cached?.takeIf { it.expiresAt.isAfter(Instant.now(clock)) }?.slugs

    private suspend fun fetch(): List<String>? =
        try {
            val slugs = webClient.get().uri("/models/required")
                .retrieve()
                .bodyToMono(RequiredModelsResponse::class.java)
                .awaitSingleOrNull()
                ?.slugs
            if (slugs.isNullOrEmpty() || slugs.any { it.isBlank() }) {
                logger.warn("Agent Server answered /models/required without a slug list")
                null
            } else {
                slugs.distinct()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 요청 URL 말고는 담기지 않지만, 예외 종류만 남긴다.
            logger.warn("Agent Server /models/required could not be read: {}", error.javaClass.simpleName)
            null
        }

    private data class CachedSlugs(val slugs: List<String>, val expiresAt: Instant)
}
