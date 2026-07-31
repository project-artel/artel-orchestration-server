package kr.artel.orchestration.qa.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.qa.dto.QaModelResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class QaModelCatalogService(
    @Value("\${artel.agent.base-url:http://localhost:8000}") baseUrl: String,
    @Value("\${artel.agent.model-catalog-ttl:PT5M}") private val ttl: Duration,
    private val clock: Clock
) {
    private val client = WebClient.create(baseUrl)
    private val refresh = Mutex()
    @Volatile private var cached: CachedCatalog? = null

    suspend fun list(): List<QaModelResponse> {
        cached?.takeIf { it.expiresAt.isAfter(Instant.now(clock)) }?.let { return it.models }
        return refresh.withLock {
            cached?.takeIf { it.expiresAt.isAfter(Instant.now(clock)) }?.models
                ?: fetch().also {
                    cached = CachedCatalog(it, Instant.now(clock).plus(ttl))
                }
        }
    }

    private suspend fun fetch(): List<QaModelResponse> =
        try {
            client.get()
                .uri("/models")
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<QaModelResponse>>() {})
                .awaitSingle()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw UpstreamUnavailableException(
                "Agent Server model catalog is unavailable.",
                cause = error
            )
        }

    private data class CachedCatalog(
        val models: List<QaModelResponse>,
        val expiresAt: Instant
    )
}
