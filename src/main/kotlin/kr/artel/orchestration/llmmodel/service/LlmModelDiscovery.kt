package kr.artel.orchestration.llmmodel.service

import com.fasterxml.jackson.databind.JsonNode
import io.netty.channel.ChannelOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.settings.config.AnthropicProperties
import kr.artel.orchestration.settings.config.LlmKeyProperties
import kr.artel.orchestration.settings.config.ModelDiscoveryProperties
import kr.artel.orchestration.settings.config.OpenAiProperties
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import java.time.Duration

enum class DiscoveryProvider(val wire: String) {
    OPENROUTER("openrouter"), OPENAI("openai"), CLAUDE("claude");

    companion object {
        fun parse(value: String): DiscoveryProvider = entries.firstOrNull { it.wire == value }
            ?: throw BadRequestException("지원하는 모델 공급자가 아닙니다.")
    }
}

data class DiscoveredLlmModel(
    val id: String,
    val label: String,
    val provider: String,
    val maxInputTokens: Int,
    val inputModalities: List<String>,
    val supportsStrictJson: Boolean,
    val reasoningEfforts: List<String> = emptyList()
)

@Component
class LlmModelDiscovery(
    private val credentials: ModelDiscoveryProperties,
    openRouter: LlmKeyProperties,
    openAi: OpenAiProperties,
    anthropic: AnthropicProperties
) {
    private val openRouterClient = client(openRouter.openrouterBaseUrl, openRouter.checkTimeout)
    private val openAiClient = client(openAi.baseUrl, openAi.checkTimeout)
    private val claudeClient = client(anthropic.baseUrl, anthropic.checkTimeout)

    suspend fun list(provider: DiscoveryProvider): List<DiscoveredLlmModel> {
        val key = when (provider) {
            DiscoveryProvider.OPENROUTER -> credentials.openrouterApiKey
            DiscoveryProvider.OPENAI -> credentials.openaiApiKey
            DiscoveryProvider.CLAUDE -> credentials.claudeApiKey
        }.trim()
        if (key.isEmpty()) {
            throw ConflictException("모델 목록 조회 키가 설정되지 않았습니다.", code = "model_discovery_key_missing")
        }
        return try {
            when (provider) {
                DiscoveryProvider.OPENROUTER ->
                    openRouterClient.get().uri("/models/user").models(key).mapNotNull(::fromOpenRouter)
                DiscoveryProvider.OPENAI ->
                    openAiClient.get().uri("/v1/models").models(key).mapNotNull(::fromOpenAi)
                DiscoveryProvider.CLAUDE ->
                    listClaude(key).mapNotNull(::fromClaude)
            }.distinctBy { it.id }
        } catch (error: CancellationException) {
            throw error
        } catch (error: WebClientResponseException) {
            if (error.statusCode.value() in listOf(401, 403)) {
                throw ConflictException("공급자가 모델 목록 조회 키를 거부했습니다.", code = "model_discovery_key_rejected")
            }
            throw providerUnavailable()
        } catch (error: Exception) {
            throw providerUnavailable()
        }
    }

    suspend fun resolve(provider: DiscoveryProvider, id: String): DiscoveredLlmModel {
        if (id.length !in 1..200) throw BadRequestException("모델 ID를 확인해 주세요.")
        return list(provider).firstOrNull { it.id == id }
            ?: throw BadRequestException("공급자 목록에서 추가할 수 있는 모델을 선택해 주세요.", code = "model_not_importable")
    }

    private suspend fun listClaude(key: String): List<JsonNode> {
        val models = mutableListOf<JsonNode>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(10) {
            val page = claudeClient.get().uri { uri ->
                uri.path("/v1/models").queryParam("limit", 1000)
                cursor?.let { uri.queryParam("after_id", it) }
                uri.build()
            }.header("anthropic-version", "2026-10-21").response(key)
            models.addAll(page.modelData())
            if (!page.path("has_more").asBoolean()) return models
            cursor = page.text("last_id")?.takeIf(seen::add) ?: throw providerUnavailable()
        }
        throw providerUnavailable()
    }

    private suspend fun WebClient.RequestHeadersSpec<*>.models(key: String): List<JsonNode> = response(key).modelData()

    private suspend fun WebClient.RequestHeadersSpec<*>.response(key: String): JsonNode =
        headers { it.setBearerAuth(key) }.retrieve().bodyToMono(JsonNode::class.java).awaitSingle()

    private fun JsonNode.modelData(): List<JsonNode> =
        path("data").takeIf { it.isArray }?.toList() ?: throw providerUnavailable()

    private fun fromOpenRouter(node: JsonNode): DiscoveredLlmModel? {
        val id = node.text("id")?.takeIf { it.length <= 200 && ROUTER_ID.matches(it) } ?: return null
        val owner = id.substringBefore('/')
        if (owner in RESERVED_ROUTES || owner.length > 32) return null
        val architecture = node.path("architecture")
        val inputs = architecture.path("input_modalities").mapNotNull { it.takeIf(JsonNode::isTextual)?.asText() }
        val outputs = architecture.path("output_modalities").map(JsonNode::asText)
        val parameters = node.path("supported_parameters").map(JsonNode::asText)
        if (!inputs.containsAll(listOf("text", "image")) || "text" !in outputs || "tools" !in parameters) return null
        val limit = node.path("top_provider").path("context_length").asInt().takeIf { it > 0 }
            ?.coerceAtMost(node.path("context_length").asInt()) ?: node.path("context_length").asInt()
        val output = node.path("top_provider").path("max_completion_tokens").asInt().takeIf { it > 0 }
            ?: return null
        val inputLimit = (limit - output).takeIf { it > 0 } ?: return null
        return DiscoveredLlmModel(
            id, node.text("name")?.take(200) ?: id, owner, inputLimit,
            inputs.filter { it in QA_MODALITIES }, "structured_outputs" in parameters
        )
    }

    private fun fromOpenAi(node: JsonNode): DiscoveredLlmModel? {
        val id = node.text("id") ?: return null
        val name = OPENAI_MODELS[id] ?: return null
        val efforts = if (id in OPENAI_MODELS_WITH_NONE) STANDARD_EFFORTS + "none" else STANDARD_EFFORTS
        return DiscoveredLlmModel(
            "$GPT_MODEL_PREFIX$id", "$name (OpenAI API)", "openai", 922_000,
            QA_MODALITIES, true, efforts
        )
    }

    private fun fromClaude(node: JsonNode): DiscoveredLlmModel? {
        val id = node.text("id")?.takeIf(CLAUDE_ID::matches) ?: return null
        if (node.text("lifecycle") != "active") return null
        val capabilities = node.path("capabilities")
        if (!capabilities.supports("image_input") || !capabilities.supports("structured_outputs")) return null
        // Claude 실행 경로는 max_tokens를 128,000으로 보낸다.
        if (node.path("max_tokens").asInt() < 128_000) return null
        val inputLimit = node.path("max_input_tokens").asInt().takeIf { it > 0 } ?: return null
        val efforts = if (capabilities.path("thinking").path("types").supports("adaptive")) {
            STANDARD_EFFORTS.filter { capabilities.path("effort").supports(it) }
        } else {
            emptyList()
        }
        val inputs = if (capabilities.supports("pdf_input")) QA_MODALITIES else listOf("text", "image")
        val catalogId = "$CLAUDE_MODEL_PREFIX$id"
        return DiscoveredLlmModel(
            catalogId, node.text("display_name")?.take(200) ?: catalogId,
            "anthropic", inputLimit, inputs, true, efforts
        )
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeIf(JsonNode::isTextual)?.asText()

    private fun JsonNode.supports(name: String): Boolean = path(name).path("supported").asBoolean()

    private fun providerUnavailable() =
        UpstreamUnavailableException("공급자에서 모델 목록을 불러오지 못했습니다.", code = "model_discovery_unavailable")

    private fun client(base: String, timeout: Duration): WebClient = WebClient.builder()
        .baseUrl(base.trimEnd('/'))
        .clientConnector(ReactorClientHttpConnector(
            HttpClient.create()
                .responseTimeout(timeout)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout.toMillis().toInt())
        ))
        .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
        .build()

    private companion object {
        val ROUTER_ID = Regex("[a-zA-Z0-9._-]+/[a-zA-Z0-9._:-]+")
        val CLAUDE_ID = Regex("claude-[a-z0-9-]{1,180}")
        val RESERVED_ROUTES = setOf("gpt", "claude", "bedrock")
        val QA_MODALITIES = listOf("text", "image", "file")
        val STANDARD_EFFORTS = listOf("max", "xhigh", "high", "medium", "low")

        // OpenAI 목록은 기능과 토큰 한도를 주지 않아 공식 사양으로 확인한 모델만 가져온다.
        val OPENAI_MODELS = mapOf(
            "gpt-6-astra" to "GPT-6 Astra",
            "gpt-6.1-sol" to "GPT-6.1 Sol",
            "gpt-6-sol" to "GPT-6 Sol",
            "gpt-6.1-terra" to "GPT-6.1 Terra",
            "gpt-6-luna" to "GPT-6 Luna"
        )
        val OPENAI_MODELS_WITH_NONE = setOf("gpt-6-sol", "gpt-6.1-terra", "gpt-6-luna")
    }
}
