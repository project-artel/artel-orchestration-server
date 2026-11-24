package kr.artel.orchestration.qa.controller

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.settings.config.AgentServerProperties
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import java.net.URI
import java.time.Duration

@RestController
class QaArchOptionsController(
    properties: AgentServerProperties,
    webClientBuilder: WebClient.Builder,
) {
    // 배포 주소는 /internal까지 포함하고 로컬 기본값은 호스트만 지정한다.
    private val baseUrl = properties.baseUrl.trimEnd('/').let { base ->
        if (URI.create(base).path.isNullOrEmpty()) "$base/internal" else base
    }
    private val agent = webClientBuilder.clone().baseUrl(baseUrl).build()

    @GetMapping("/api/qa-arch-options", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun qaArchOptions(): JsonNode {
        try {
            val catalog = agent.get().uri("/qa-arch-options").accept(MediaType.APPLICATION_JSON)
                .retrieve().bodyToMono(JsonNode::class.java)
                .timeout(Duration.ofSeconds(5)).awaitSingle()
            check(catalog.path("options").isArray) { "Agent response is missing the options array" }
            return catalog
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw UpstreamUnavailableException("QA Agent options are unavailable", cause = error)
        }
    }
}
