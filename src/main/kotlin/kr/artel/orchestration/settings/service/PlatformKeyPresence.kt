package kr.artel.orchestration.settings.service

import kr.artel.orchestration.llmmodel.service.BEDROCK_MODEL_PREFIX
import kr.artel.orchestration.llmmodel.service.CLAUDE_MODEL_PREFIX
import kr.artel.orchestration.llmmodel.service.GPT_MODEL_PREFIX
import org.springframework.stereotype.Component

/**
 * 관리자가 넣은 공용 key 로 그 model 을 지금 부를 수 있는지 본다.
 *
 * `hasPlatformKey` 와 달리 OpenRouter 경로도 key 가 실제로 저장돼 있는지 본다. 선택 목록은 OpenRouter key 가
 * 없어도 model 을 보여 주지만, 관리 화면의 경고(`keyMissing`)와 프로젝트 설정은 key 가 없으면 그 model 이
 * 돌지 않는다는 것을 알려야 한다.
 *
 * | 경로 | 공용 key |
 * |---|---|
 * | `claude/` | 관리 화면의 Claude API key 가 있고 열 수 있을 때 |
 * | `gpt/` | 없음 |
 * | `bedrock/` | 있다고 본다. 호출은 agent-server 가 막는다 |
 * | 그 밖(OpenRouter) | 관리 화면의 OpenRouter key 가 있고 열 수 있을 때 |
 */
@Component
class PlatformKeyPresence(
    private val anthropicKeyService: AnthropicKeyService,
    private val llmKeyService: LlmKeyService
) {
    suspend fun has(modelId: String): Boolean = when {
        modelId.startsWith(GPT_MODEL_PREFIX) -> false
        modelId.startsWith(CLAUDE_MODEL_PREFIX) -> anthropicKeyService.storedKey() != null
        modelId.startsWith(BEDROCK_MODEL_PREFIX) -> true
        else -> llmKeyService.effectiveKey().apiKey != null
    }

    /** [modelIds] 중 공용 key 가 없는 것. 순서를 지키고 겹치는 id 는 한 번만 싣는다. */
    suspend fun missingAmong(modelIds: List<String>): List<String> =
        modelIds.distinct().filter { !has(it) }
}
