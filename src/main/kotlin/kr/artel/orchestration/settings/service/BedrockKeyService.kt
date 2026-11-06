package kr.artel.orchestration.settings.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kr.artel.orchestration.llmmodel.service.BEDROCK_MODEL_PREFIX
import kr.artel.orchestration.llmmodel.service.LlmModelService
import kr.artel.orchestration.settings.config.BedrockProperties
import kr.artel.orchestration.settings.repository.PlatformSettingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

/** 관리 화면에서 넣은 Bedrock API key(bearer token). 암호화해 저장한다. */
const val BEDROCK_API_KEY_SETTING = "bedrock.api_key"

/** 마지막 Bedrock key 확인 결과. 비밀이 아니라 평문 JSON 이다. */
const val BEDROCK_LAST_CHECK_SETTING = "bedrock.last_check"

/**
 * Bedrock API key 의 길이 상한. 장기 key 는 200자 안쪽이지만 단기 key 는 서명된 URL 을 담아 1,000자를 넘는다.
 */
private const val MAX_BEDROCK_API_KEY_LENGTH = 4096

/** 켜진 Bedrock model 하나를 확인한 결과. */
data class BedrockModelCheck(
    /** `llm_model.id`. `bedrock/` 가 붙은 값이다. */
    val id: String,
    /** Converse 가 2xx 로 답했는지. */
    val reachable: Boolean,
    /** Bedrock 이 준 `message`(300자까지) 또는 닿지 못한 이유. 닿았으면 null 이다. */
    val error: String?
)

/** Bedrock key 확인 한 번의 결과. 응답이자 `bedrock.last_check` 에 남기는 값이다. key 는 담지 않는다. */
data class BedrockCheck(
    val checkedAt: Instant,
    /** 확인할 때 저장된 key 가 있었는지. false 면 [models] 는 비어 있고 Bedrock 을 부르지 않았다. */
    val keyConfigured: Boolean,
    val models: List<BedrockModelCheck>
)

/** `GET /api/admin/settings/bedrock` 이 돌려주는 상태. key 원문은 담지 않는다. */
data class BedrockKeyStatus(
    /** 저장된 key 가 있고 열 수 있는지. */
    val configured: Boolean,
    /** 끝 네 글자만 남긴 모양(`****abcd`). key 가 없으면 null 이다. */
    val maskedKey: String?,
    /** 저장된 key 가 마지막으로 바뀐 시각. */
    val updatedAt: Instant?,
    /** 확인 호출이 가는 region(`BEDROCK_REGION`). */
    val region: String,
    val lastCheck: BedrockCheck?
)

/**
 * Bedrock API key 를 저장하고 확인한다.
 *
 * key 는 관리 화면에서 넣은 값뿐이다. 저장된 key 가 없으면 agent-server 는 AWS 기본 credential chain(IAM role
 * 등)으로 Bedrock 을 부른다. 그 chain 은 이 서버가 알 수 없어서, key 가 없을 때 확인은 아무것도 부르지 않는다.
 */
@Service
class BedrockKeyService(
    private val platformSettingRepository: PlatformSettingRepository,
    private val secretCipher: SecretCipher,
    private val bedrockRuntimeClient: BedrockRuntimeClient,
    private val llmModelService: LlmModelService,
    private val agentCacheInvalidator: AgentCacheInvalidator,
    private val properties: BedrockProperties,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(BedrockKeyService::class.java)

    /** 저장된 key 원문. 없거나 열 수 없으면 null 이다. 내부 포트와 확인만 부른다. */
    suspend fun storedKey(): String? {
        val stored = platformSettingRepository.findById(BEDROCK_API_KEY_SETTING) ?: return null
        val plaintext = secretCipher.decrypt(stored.settingValue, BEDROCK_API_KEY_SETTING)
        if (plaintext == null) {
            logger.warn(
                "Stored Bedrock API key could not be decrypted; ARTEL_SECRETS_KEY is missing or changed. " +
                    "Enter the key again on the admin page."
            )
        }
        return plaintext
    }

    suspend fun status(): BedrockKeyStatus {
        val stored = platformSettingRepository.findById(BEDROCK_API_KEY_SETTING)
        val apiKey = stored?.let { storedKey() }
        return BedrockKeyStatus(
            configured = apiKey != null,
            maskedKey = apiKey?.let(::mask),
            updatedAt = stored?.updatedAt,
            region = properties.region,
            lastCheck = readLastCheck()
        )
    }

    /**
     * key 를 바꾼다. null 이나 빈 문자열이면 key 와 마지막 확인 결과를 지운다.
     *
     * 바꿀 때도 마지막 확인 결과를 지운다. 다른 key 에 대한 결과가 새 key 의 것처럼 보이면 안 된다. 저장한
     * 뒤에는 agent-server 의 캐시를 비우라고 요청한다. 그 요청이 실패해도 저장은 성공이다.
     */
    suspend fun update(apiKey: String?, adminUserId: Long): BedrockKeyStatus {
        val trimmed = apiKey?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            transactionalOperator.executeAndAwait {
                platformSettingRepository.deleteById(BEDROCK_API_KEY_SETTING)
                platformSettingRepository.deleteById(BEDROCK_LAST_CHECK_SETTING)
            }
            logger.info("Bedrock API key cleared by app_user {}", adminUserId)
            agentCacheInvalidator.invalidateAfterChange("clearing the Bedrock API key")
            return status()
        }
        validateApiKey(trimmed, MAX_BEDROCK_API_KEY_LENGTH)
        val ciphertext = secretCipher.encrypt(trimmed, BEDROCK_API_KEY_SETTING)
        transactionalOperator.executeAndAwait {
            platformSettingRepository.upsert(
                settingKey = BEDROCK_API_KEY_SETTING,
                settingValue = ciphertext,
                encrypted = true,
                updatedBy = adminUserId,
                updatedAt = Instant.now(clock)
            )
            platformSettingRepository.deleteById(BEDROCK_LAST_CHECK_SETTING)
        }
        logger.info("Bedrock API key replaced by app_user {}", adminUserId)
        agentCacheInvalidator.invalidateAfterChange("saving the Bedrock API key")
        return status()
    }

    /**
     * 켜진 Bedrock model 마다 Converse 를 한 번씩 부르고, 결과를 남긴 뒤 돌려준다. 꺼진 model 은 부르지 않는다.
     * 호출은 동시에 나간다.
     */
    suspend fun check(adminUserId: Long): BedrockCheck {
        val apiKey = storedKey()
        val models = if (apiKey == null) {
            emptyList()
        } else {
            val ids = llmModelService.enabledBedrockModelIds()
            coroutineScope {
                ids.map { id ->
                    async {
                        val outcome = bedrockRuntimeClient.converse(id.removePrefix(BEDROCK_MODEL_PREFIX), apiKey)
                        BedrockModelCheck(id = id, reachable = outcome.reachable, error = outcome.error)
                    }
                }.awaitAll()
            }
        }
        val result = BedrockCheck(checkedAt = Instant.now(clock), keyConfigured = apiKey != null, models = models)
        platformSettingRepository.upsert(
            settingKey = BEDROCK_LAST_CHECK_SETTING,
            settingValue = objectMapper.writeValueAsString(result),
            encrypted = false,
            updatedBy = adminUserId,
            updatedAt = result.checkedAt
        )
        return result
    }

    private suspend fun readLastCheck(): BedrockCheck? {
        val stored = platformSettingRepository.findById(BEDROCK_LAST_CHECK_SETTING) ?: return null
        return try {
            objectMapper.readValue(stored.settingValue, BedrockCheck::class.java)
        } catch (error: Exception) {
            null
        }
    }
}
