package kr.artel.orchestration.settings.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.llmmodel.service.CLAUDE_MODEL_PREFIX
import kr.artel.orchestration.llmmodel.service.LlmModelService
import kr.artel.orchestration.settings.repository.PlatformSettingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

/** 관리 화면에서 넣은 Claude API(Anthropic API) key. 암호화해 저장한다. */
const val ANTHROPIC_API_KEY_SETTING = "anthropic.api_key"

/** 마지막 Claude API key 확인 결과. 비밀이 아니라 평문 JSON 이다. */
const val ANTHROPIC_LAST_CHECK_SETTING = "anthropic.last_check"

/** Anthropic 이 발급하는 API key 는 모두 이 접두어로 시작한다. */
private const val ANTHROPIC_API_KEY_PREFIX = "sk-ant-"

/** Claude API key 의 길이 상한. 지금 발급되는 key 는 110자 안쪽이고, OpenRouter key 와 같은 상한을 둔다. */
private const val MAX_ANTHROPIC_API_KEY_LENGTH = 512

/** 켜진 `claude/` model 하나를 확인한 결과. */
data class AnthropicModelCheck(
    /** `llm_model.id`. `claude/` 가 붙은 값이다. */
    val id: String,
    /** key 로 받은 model 목록에 이 model 이 있는지. */
    val reachable: Boolean,
    /** 목록에 없거나 목록을 받지 못한 이유(300자까지). 닿았으면 null 이다. */
    val error: String?
)

/** Claude API key 확인 한 번의 결과. 응답이자 `anthropic.last_check` 에 남기는 값이다. key 는 담지 않는다. */
data class AnthropicCheck(
    val checkedAt: Instant,
    /** 확인할 때 저장된 key 가 있었는지. false 면 [models] 는 비어 있고 Anthropic API 를 부르지 않았다. */
    val keyConfigured: Boolean,
    /** `GET /v1/models` 가 2xx 로 답했는지. 401 · 403 이나 닿지 못한 경우는 false 다. */
    val keyValid: Boolean,
    val models: List<AnthropicModelCheck>
)

/** `GET /api/admin/settings/anthropic` 이 돌려주는 상태. key 원문은 담지 않는다. */
data class AnthropicKeyStatus(
    /** 저장된 key 가 있고 열 수 있는지. */
    val configured: Boolean,
    /** 끝 네 글자만 남긴 모양(`****abcd`). key 가 없으면 null 이다. */
    val maskedKey: String?,
    /** 저장된 key 가 마지막으로 바뀐 시각. */
    val updatedAt: Instant?,
    val lastCheck: AnthropicCheck?
)

/**
 * Claude API key 를 저장하고 확인한다. 모양은 [BedrockKeyService] 와 같다.
 *
 * key 는 관리 화면에서 넣은 값뿐이다. 저장된 key 가 없으면 agent-server 는 `claude/` model 을 부르지 못한다.
 */
@Service
class AnthropicKeyService(
    private val platformSettingRepository: PlatformSettingRepository,
    private val secretCipher: SecretCipher,
    private val anthropicModelClient: AnthropicModelClient,
    private val llmModelService: LlmModelService,
    private val agentCacheInvalidator: AgentCacheInvalidator,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(AnthropicKeyService::class.java)

    /** 저장된 key 원문. 없거나 열 수 없으면 null 이다. 내부 포트와 확인만 부른다. */
    suspend fun storedKey(): String? {
        val stored = platformSettingRepository.findById(ANTHROPIC_API_KEY_SETTING) ?: return null
        val plaintext = secretCipher.decrypt(stored.settingValue, ANTHROPIC_API_KEY_SETTING)
        if (plaintext == null) {
            logger.warn(
                "Stored Claude API key could not be decrypted; ARTEL_SECRETS_KEY is missing or changed. " +
                    "Enter the key again on the admin page."
            )
        }
        return plaintext
    }

    suspend fun status(): AnthropicKeyStatus {
        val stored = platformSettingRepository.findById(ANTHROPIC_API_KEY_SETTING)
        val apiKey = stored?.let { storedKey() }
        return AnthropicKeyStatus(
            configured = apiKey != null,
            maskedKey = apiKey?.let(::mask),
            updatedAt = stored?.updatedAt,
            lastCheck = readLastCheck()
        )
    }

    /**
     * key 를 바꾼다. null 이나 빈 문자열이면 key 와 마지막 확인 결과를 지운다.
     *
     * key 는 `sk-ant-` 로 시작해야 한다. 아니면 400 `invalid_api_key` 다. 바꿀 때도 마지막 확인 결과를 지우고,
     * 저장한 뒤에는 agent-server 의 캐시를 비우라고 요청한다. 그 요청이 실패해도 저장은 성공이다.
     */
    suspend fun update(apiKey: String?, adminUserId: Long): AnthropicKeyStatus {
        val trimmed = apiKey?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            transactionalOperator.executeAndAwait {
                platformSettingRepository.deleteById(ANTHROPIC_API_KEY_SETTING)
                platformSettingRepository.deleteById(ANTHROPIC_LAST_CHECK_SETTING)
            }
            logger.info("Claude API key cleared by app_user {}", adminUserId)
            agentCacheInvalidator.invalidateAfterChange("clearing the Claude API key")
            return status()
        }
        if (!trimmed.startsWith(ANTHROPIC_API_KEY_PREFIX)) {
            throw BadRequestException(
                "Claude API key 는 ${ANTHROPIC_API_KEY_PREFIX} 로 시작해야 합니다.",
                code = "invalid_api_key"
            )
        }
        validateApiKey(trimmed, MAX_ANTHROPIC_API_KEY_LENGTH)
        val ciphertext = secretCipher.encrypt(trimmed, ANTHROPIC_API_KEY_SETTING)
        transactionalOperator.executeAndAwait {
            platformSettingRepository.upsert(
                settingKey = ANTHROPIC_API_KEY_SETTING,
                settingValue = ciphertext,
                encrypted = true,
                updatedBy = adminUserId,
                updatedAt = Instant.now(clock)
            )
            platformSettingRepository.deleteById(ANTHROPIC_LAST_CHECK_SETTING)
        }
        logger.info("Claude API key replaced by app_user {}", adminUserId)
        agentCacheInvalidator.invalidateAfterChange("saving the Claude API key")
        return status()
    }

    /**
     * `GET /v1/models` 를 한 번 부르고, 켜진 `claude/` model 마다 그 목록에 있는지 본다. 꺼진 model 은 결과에
     * 넣지 않는다. 목록을 받지 못하면 켜진 model 전부가 닿지 않은 것으로 남고 `error` 에 이유가 담긴다.
     */
    suspend fun check(adminUserId: Long): AnthropicCheck {
        val apiKey = storedKey()
        val result = if (apiKey == null) {
            AnthropicCheck(checkedAt = Instant.now(clock), keyConfigured = false, keyValid = false, models = emptyList())
        } else {
            val ids = llmModelService.enabledClaudeModelIds()
            when (val listing = anthropicModelClient.list(apiKey)) {
                is AnthropicListing.Reachable -> AnthropicCheck(
                    checkedAt = Instant.now(clock),
                    keyConfigured = true,
                    keyValid = true,
                    models = ids.map { id -> listedModelCheck(id, listing.modelIds) }
                )
                is AnthropicListing.InvalidKey -> failedCheck(ids, listing.error)
                is AnthropicListing.Unavailable -> failedCheck(ids, listing.error)
            }
        }
        platformSettingRepository.upsert(
            settingKey = ANTHROPIC_LAST_CHECK_SETTING,
            settingValue = objectMapper.writeValueAsString(result),
            encrypted = false,
            updatedBy = adminUserId,
            updatedAt = result.checkedAt
        )
        return result
    }

    private fun listedModelCheck(id: String, listedModelIds: Set<String>): AnthropicModelCheck {
        val anthropicModelId = id.removePrefix(CLAUDE_MODEL_PREFIX)
        val reachable = anthropicModelId in listedModelIds
        return AnthropicModelCheck(
            id = id,
            reachable = reachable,
            error = if (reachable) null else "$anthropicModelId is not in the model list for this key"
        )
    }

    private fun failedCheck(ids: List<String>, error: String) = AnthropicCheck(
        checkedAt = Instant.now(clock),
        keyConfigured = true,
        keyValid = false,
        models = ids.map { id -> AnthropicModelCheck(id = id, reachable = false, error = error) }
    )

    private suspend fun readLastCheck(): AnthropicCheck? {
        val stored = platformSettingRepository.findById(ANTHROPIC_LAST_CHECK_SETTING) ?: return null
        return try {
            objectMapper.readValue(stored.settingValue, AnthropicCheck::class.java)
        } catch (error: Exception) {
            null
        }
    }
}
