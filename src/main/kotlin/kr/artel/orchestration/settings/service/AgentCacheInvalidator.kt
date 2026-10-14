package kr.artel.orchestration.settings.service

import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.settings.config.AgentServerProperties
import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import java.util.concurrent.TimeoutException

/** `POST /api/admin/settings/llm/invalidate-cache` 응답이자 [AgentCacheInvalidator.invalidate] 의 결과. */
data class AgentCacheInvalidationResult(
    /** agent-server 가 2xx 로 답했는지. */
    val agentServerInvalidated: Boolean,
    /** 실패한 이유. 성공이면 null 이다. */
    val error: String?
)

/**
 * agent-server 가 들고 있는 OpenRouter key, Bedrock key, model 카탈로그 캐시를 비우라고 요청한다.
 *
 * agent-server 는 셋을 30초씩 들고 있다. 관리자가 key 를 바꾸거나 model 을 끄면 이 호출로 다음 model 호출이
 * 새 값을 읽게 한다. 실패해도 예외를 던지지 않는다 — 캐시는 30초 뒤에 저절로 만료되고, 그 30초 때문에 저장이
 * 실패하면 안 된다.
 *
 * `artel.agent.base-url` 한 주소만 부른다. agent-server replica 가 여럿이면 응답한 replica 하나만 비운다.
 * 그 주소는 `/internal` 까지 포함하므로(`http://agent-server:8000/internal`) 경로는 `/llm-cache/invalidate` 만 붙인다.
 */
@Component
class AgentCacheInvalidator(properties: AgentServerProperties) {
    private val logger = LoggerFactory.getLogger(AgentCacheInvalidator::class.java)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl(properties.baseUrl.trimEnd('/'))
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(properties.cacheInvalidateTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.cacheInvalidateTimeout.toMillis().toInt())
            )
        )
        .build()

    suspend fun invalidate(): AgentCacheInvalidationResult {
        return try {
            webClient.post().uri("/llm-cache/invalidate")
                .retrieve().toBodilessEntity().awaitSingle()
            AgentCacheInvalidationResult(agentServerInvalidated = true, error = null)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: WebClientResponseException) {
            failed("agent-server answered ${error.statusCode.value()}")
        } catch (error: Exception) {
            val reason = if (error.hasCause<TimeoutException>() || error.javaClass.simpleName.contains("Timeout")) {
                "agent-server did not answer in time"
            } else {
                "agent-server could not be reached (${error.javaClass.simpleName})"
            }
            failed(reason)
        }
    }

    /**
     * 저장 뒤에 부르는 쪽. 결과를 warn 로그로만 남긴다. 저장 응답은 이 결과와 상관없이 성공이다.
     */
    suspend fun invalidateAfterChange(change: String) {
        val result = invalidate()
        if (!result.agentServerInvalidated) {
            logger.warn("agent-server cache was not invalidated after {}: {}", change, result.error)
        }
    }

    private fun failed(reason: String) = AgentCacheInvalidationResult(agentServerInvalidated = false, error = reason)

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is T }
}
