package kr.artel.orchestration.settings.service

import kr.artel.orchestration.llmmodel.repository.LlmModelRepository
import kr.artel.orchestration.llmmodel.service.BEDROCK_MODEL_PREFIX
import kr.artel.orchestration.llmmodel.service.CLAUDE_MODEL_PREFIX
import kr.artel.orchestration.llmmodel.service.GPT_MODEL_PREFIX
import org.springframework.stereotype.Component

/** 공용 key 로 부를 수 있는 provider. 선언 순서가 기본 model 을 고르는 순서다. */
enum class PlatformProvider {
    OPENROUTER,
    ANTHROPIC,
    BEDROCK;

    fun routes(modelId: String): Boolean = when (this) {
        ANTHROPIC -> modelId.startsWith(CLAUDE_MODEL_PREFIX)
        BEDROCK -> modelId.startsWith(BEDROCK_MODEL_PREFIX)
        OPENROUTER -> !modelId.startsWith(CLAUDE_MODEL_PREFIX) &&
            !modelId.startsWith(BEDROCK_MODEL_PREFIX) &&
            !modelId.startsWith(GPT_MODEL_PREFIX)
    }
}

/** 기능별 model 설정이 비었을 때 쓰는 model. 고를 provider 가 없으면 두 값 모두 null 이다. */
data class ProviderDefaults(
    /** 기본 model 을 고른 provider. 공용 key 가 유효한 provider 가 없으면 null 이다. */
    val provider: PlatformProvider?,
    val scenarioAuthoringModel: String?,
    val decisionModel: String?
)

/**
 * 기능별 model 설정이 비었을 때 공용 key 가 유효한 provider 의 model 을 고른다(ARTEL-1237).
 *
 * 설정이 비면 시나리오 저작은 agent-server 기본값 `openai/gpt-6-luna` 로 갔다. OpenRouter key 가 없고 Anthropic
 * key 만 있는 설치(2026-10-22 로컬 스택)에서는 그 호출이 403 으로 실패했다. 그래서 provider 를 OpenRouter →
 * Anthropic → Bedrock 순서로 보고, 공용 key 가 유효한 첫 provider 의 model 을 쓴다.
 *
 * key 가 유효하다는 것은 저장된 key 를 열 수 있고, 마지막 확인이 있으면 그 확인이 실패하지 않았다는 뜻이다.
 * 확인을 한 번도 하지 않은 key 는 유효하다고 본다 — 관리자가 key 를 넣고 확인 버튼을 누르지 않는 경우가 많다.
 * Bedrock 은 저장된 key 가 있을 때만 본다. key 없이 쓰는 AWS 기본 credential chain 은 이 서버가 알 수 없다.
 *
 * provider 안에서는 [PREFERRED_MODELS] 의 model 이 켜져 있으면 그것을, 아니면 그 provider 의 켜진 model 중
 * `sort_order` 가 가장 앞인 것을 쓴다. 켜진 model 이 하나도 없는 provider 는 건너뛴다.
 */
@Component
class ProviderDefaultModels(
    private val llmKeyService: LlmKeyService,
    private val anthropicKeyService: AnthropicKeyService,
    private val bedrockKeyService: BedrockKeyService,
    private val llmModelRepository: LlmModelRepository
) {
    suspend fun pick(): ProviderDefaults {
        val enabledModelIds = llmModelRepository.findAllByEnabledTrueOrderBySortOrderAsc().map { it.id }
        for (provider in PlatformProvider.entries) {
            if (!hasValidKey(provider)) continue
            val model = modelOf(provider, enabledModelIds) ?: continue
            // Jev 는 OpenRouter 의 decisions endpoint 다. OpenRouter key 가 있으면 지금까지처럼 Jev 가 답한다.
            val decision = if (provider == PlatformProvider.OPENROUTER) JEV_MODEL_ID else model
            return ProviderDefaults(provider, model, decision)
        }
        return ProviderDefaults(null, null, null)
    }

    private suspend fun hasValidKey(provider: PlatformProvider): Boolean = when (provider) {
        PlatformProvider.OPENROUTER -> {
            val status = llmKeyService.status()
            status.configured && status.lastCheck?.keyValid != false
        }
        PlatformProvider.ANTHROPIC -> {
            val status = anthropicKeyService.status()
            status.configured && status.lastCheck?.keyValid != false
        }
        PlatformProvider.BEDROCK -> {
            val status = bedrockKeyService.status()
            val lastCheck = status.lastCheck
            status.configured && (lastCheck == null || lastCheck.models.isEmpty() || lastCheck.models.any { it.reachable })
        }
    }

    private fun modelOf(provider: PlatformProvider, enabledModelIds: List<String>): String? {
        val candidates = enabledModelIds.filter(provider::routes)
        return PREFERRED_MODELS[provider]?.takeIf { it in candidates } ?: candidates.firstOrNull()
    }

    private companion object {
        /**
         * provider 마다 먼저 고르는 model. OpenRouter 는 agent-server 의 시나리오 저작 기본값과 같다. Bedrock 은
         * 계정마다 열린 model 이 달라 정하지 않고 `sort_order` 를 따른다.
         */
        val PREFERRED_MODELS = mapOf(
            PlatformProvider.OPENROUTER to "openai/gpt-6-luna",
            PlatformProvider.ANTHROPIC to "claude/claude-sonnet-5-5"
        )
    }
}
