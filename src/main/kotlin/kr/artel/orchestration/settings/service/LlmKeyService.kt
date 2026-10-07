package kr.artel.orchestration.settings.service

import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.settings.config.LlmKeyProperties
import kr.artel.orchestration.settings.repository.PlatformSettingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

/** 관리 화면에서 넣은 OpenRouter API key. 암호화해 저장한다. */
const val OPENROUTER_API_KEY_SETTING = "llm.openrouter.api_key"

/** 마지막 key 확인 결과. 비밀이 아니라 평문 JSON 이다. */
const val OPENROUTER_LAST_CHECK_SETTING = "llm.openrouter.last_check"

private const val MIN_API_KEY_LENGTH = 8
private const val MAX_API_KEY_LENGTH = 512

/** 지금 쓰이는 key 가 어디서 왔는지. */
enum class LlmKeySource(@get:JsonValue val wireName: String) {
    /** 관리 화면에서 넣은 값(`platform_setting`). */
    ADMIN("admin"),

    /** `OPENROUTER_API_KEY` 환경 변수. */
    ENVIRONMENT("environment"),

    /** 둘 다 없다. */
    NONE("none")
}

/** 지금 쓰이는 key. [apiKey] 원문이 로그로 나가지 않도록 [toString] 을 덮어쓴다. */
data class EffectiveLlmKey(val apiKey: String?, val source: LlmKeySource) {
    override fun toString(): String = "EffectiveLlmKey(source=$source)"
}

/** key 확인이 실패한 이유. 성공이면 null 이다. */
enum class LlmKeyCheckError(@get:JsonValue val wireName: String) {
    NO_KEY("no_key"),
    INVALID_KEY("invalid_key"),
    UPSTREAM_UNAVAILABLE("upstream_unavailable")
}

/** key 확인 한 번의 결과. 응답이자 `platform_setting` 에 남기는 값이다. key 는 담지 않는다. */
data class LlmKeyCheckResult(
    val checkedAt: Instant,
    /** 확인에 쓴 key 의 출처. */
    val source: LlmKeySource,
    /** 확인에 쓴 key 의 끝 네 글자를 가린 모양. key 가 없었으면 null 이다. */
    val maskedKey: String?,
    val keyValid: Boolean,
    /** 닿아야 하는 model 전부. `artel-agent-server` 의 `GET /internal/models/required` 가 준 목록이다. 그 목록을 받지 못했으면 비어 있다. */
    val requiredModels: List<String>,
    val reachableModels: List<String>,
    val missingModels: List<String>,
    val error: LlmKeyCheckError?
)

/** `GET /api/admin/settings/llm` 이 돌려주는 상태. key 원문은 담지 않는다. */
data class LlmKeyStatus(
    /** 지금 쓰이는 key 가 있는지. */
    val configured: Boolean,
    val source: LlmKeySource,
    /** 지금 쓰이는 key 의 끝 네 글자만 남긴 모양(`****abcd`). key 가 없으면 null 이다. */
    val maskedKey: String?,
    /** 관리 화면에서 넣은 값이 있는지. 열지 못해 쓰이지 않는 경우도 true 다. */
    val adminKeyStored: Boolean,
    /** 관리 화면에서 넣은 값을 열지 못했는지. `ARTEL_SECRETS_KEY` 가 바뀌었거나 빠졌을 때 true 다. */
    val adminKeyUnreadable: Boolean,
    /** 관리 화면에서 넣은 값이 마지막으로 바뀐 시각. */
    val adminKeyUpdatedAt: Instant?,
    /** `OPENROUTER_API_KEY` 가 있는지. */
    val environmentKeyPresent: Boolean,
    /** `ARTEL_SECRETS_KEY` 가 있는지. false 면 관리 화면에서 key 를 저장할 수 없다. */
    val secretsKeyConfigured: Boolean,
    val lastCheck: LlmKeyCheckResult?
)

/**
 * OpenRouter API key 를 저장하고, 지금 쓰일 key 를 정하고, 확인한다.
 *
 * 순서는 관리 화면에서 넣은 값, 그다음 `OPENROUTER_API_KEY` 다. key 원문은 [effectiveKey] 로만 나가고,
 * 그것을 부르는 곳은 내부 포트의 `GET /internal/settings/llm` 과 key 확인뿐이다. 공개 API 는 끝 네
 * 글자만 본다.
 */
@Service
class LlmKeyService(
    private val platformSettingRepository: PlatformSettingRepository,
    private val secretCipher: SecretCipher,
    private val openRouterModelClient: OpenRouterModelClient,
    private val requiredModelsClient: RequiredModelsClient,
    private val properties: LlmKeyProperties,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(LlmKeyService::class.java)

    suspend fun effectiveKey(): EffectiveLlmKey {
        val adminKey = readAdminKey()
        if (adminKey != null) return EffectiveLlmKey(adminKey, LlmKeySource.ADMIN)
        val environmentKey = properties.openrouterApiKey.trim()
        if (environmentKey.isNotEmpty()) return EffectiveLlmKey(environmentKey, LlmKeySource.ENVIRONMENT)
        return EffectiveLlmKey(null, LlmKeySource.NONE)
    }

    suspend fun status(): LlmKeyStatus {
        val stored = platformSettingRepository.findById(OPENROUTER_API_KEY_SETTING)
        val effective = effectiveKey()
        return LlmKeyStatus(
            configured = effective.apiKey != null,
            source = effective.source,
            maskedKey = effective.apiKey?.let(::mask),
            adminKeyStored = stored != null,
            adminKeyUnreadable = stored != null && effective.source != LlmKeySource.ADMIN,
            adminKeyUpdatedAt = stored?.updatedAt,
            environmentKeyPresent = properties.openrouterApiKey.isNotBlank(),
            secretsKeyConfigured = secretCipher.configured,
            lastCheck = readLastCheck()
        )
    }

    /**
     * 관리 화면의 key 를 바꾼다. null 이나 빈 문자열이면 지운다 — 그 뒤로는 `OPENROUTER_API_KEY` 가 쓰인다.
     *
     * 마지막 확인 결과도 함께 지운다. 다른 key 에 대한 결과가 새 key 의 것처럼 보이면 안 된다.
     */
    suspend fun updateAdminKey(apiKey: String?, adminUserId: Long): LlmKeyStatus {
        val trimmed = apiKey?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            transactionalOperator.executeAndAwait {
                platformSettingRepository.deleteById(OPENROUTER_API_KEY_SETTING)
                platformSettingRepository.deleteById(OPENROUTER_LAST_CHECK_SETTING)
            }
            logger.info("OpenRouter API key cleared by app_user {}", adminUserId)
            return status()
        }
        validateApiKey(trimmed)
        val ciphertext = secretCipher.encrypt(trimmed, OPENROUTER_API_KEY_SETTING)
        transactionalOperator.executeAndAwait {
            platformSettingRepository.upsert(
                settingKey = OPENROUTER_API_KEY_SETTING,
                settingValue = ciphertext,
                encrypted = true,
                updatedBy = adminUserId,
                updatedAt = Instant.now(clock)
            )
            platformSettingRepository.deleteById(OPENROUTER_LAST_CHECK_SETTING)
        }
        logger.info("OpenRouter API key replaced by app_user {}", adminUserId)
        return status()
    }

    /** 지금 쓰이는 key 로 OpenRouter 에 묻고, 결과를 남긴 뒤 돌려준다. */
    suspend fun check(adminUserId: Long): LlmKeyCheckResult {
        val effective = effectiveKey()
        val apiKey = effective.apiKey
        val result = if (apiKey == null) {
            checkResult(effective, emptyList(), keyValid = false, reachable = emptySet(), error = LlmKeyCheckError.NO_KEY)
        } else {
            val required = requiredModelsClient.slugs()
            if (required == null) {
                checkResult(effective, emptyList(), keyValid = false, reachable = emptySet(), error = LlmKeyCheckError.UPSTREAM_UNAVAILABLE)
            } else {
                when (val listing = openRouterModelClient.list(apiKey)) {
                    is OpenRouterListing.Reachable ->
                        checkResult(effective, required, keyValid = true, reachable = listing.modelIds, error = null)
                    OpenRouterListing.InvalidKey ->
                        checkResult(effective, required, keyValid = false, reachable = emptySet(), error = LlmKeyCheckError.INVALID_KEY)
                    OpenRouterListing.Unavailable ->
                        checkResult(effective, required, keyValid = false, reachable = emptySet(), error = LlmKeyCheckError.UPSTREAM_UNAVAILABLE)
                }
            }
        }
        platformSettingRepository.upsert(
            settingKey = OPENROUTER_LAST_CHECK_SETTING,
            settingValue = objectMapper.writeValueAsString(result),
            encrypted = false,
            updatedBy = adminUserId,
            updatedAt = result.checkedAt
        )
        return result
    }

    private fun checkResult(
        effective: EffectiveLlmKey,
        required: List<String>,
        keyValid: Boolean,
        reachable: Set<String>,
        error: LlmKeyCheckError?
    ) = LlmKeyCheckResult(
        checkedAt = Instant.now(clock),
        source = effective.source,
        maskedKey = effective.apiKey?.let(::mask),
        keyValid = keyValid,
        requiredModels = required,
        reachableModels = required.filter { it in reachable },
        missingModels = required.filterNot { it in reachable },
        error = error
    )

    private suspend fun readAdminKey(): String? {
        val stored = platformSettingRepository.findById(OPENROUTER_API_KEY_SETTING) ?: return null
        val plaintext = secretCipher.decrypt(stored.settingValue, OPENROUTER_API_KEY_SETTING)
        if (plaintext == null) {
            logger.warn(
                "Stored OpenRouter API key could not be decrypted; ARTEL_SECRETS_KEY is missing or changed. " +
                    "Falling back to OPENROUTER_API_KEY."
            )
        }
        return plaintext
    }

    private suspend fun readLastCheck(): LlmKeyCheckResult? {
        val stored = platformSettingRepository.findById(OPENROUTER_LAST_CHECK_SETTING) ?: return null
        return try {
            objectMapper.readValue(stored.settingValue, LlmKeyCheckResult::class.java)
        } catch (error: Exception) {
            null
        }
    }

    private fun validateApiKey(apiKey: String) {
        if (apiKey.length !in MIN_API_KEY_LENGTH..MAX_API_KEY_LENGTH || apiKey.any(Char::isWhitespace)) {
            throw BadRequestException(
                "API key 는 공백 없이 ${MIN_API_KEY_LENGTH}자 이상 ${MAX_API_KEY_LENGTH}자 이하여야 합니다.",
                code = "invalid_api_key"
            )
        }
    }
}

/**
 * 끝 네 글자만 남긴다. 여덟 글자보다 짧은 key 는 네 글자를 보이면 절반이 드러나므로 전부 가린다.
 * 어느 key 인지 사람이 알아보는 데는 끝 네 글자면 충분하다.
 */
fun mask(apiKey: String): String = if (apiKey.length < 8) "****" else "****" + apiKey.takeLast(4)
