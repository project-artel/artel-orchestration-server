package kr.artel.orchestration.settings.service

import kr.artel.orchestration.settings.repository.PlatformSettingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

/** 관리 화면에서 넣은 TypeSafe API key(Jev 직접 호출용). 암호화해 저장한다. */
const val TYPESAFE_API_KEY_SETTING = "typesafe.api_key"

private const val MAX_TYPESAFE_API_KEY_LENGTH = 512

/** `GET /api/admin/settings/typesafe` 가 돌려주는 상태. key 원문은 담지 않는다. */
data class TypesafeKeyStatus(
    /** 저장된 key 가 있고 열 수 있는지. */
    val configured: Boolean,
    /** 끝 네 글자만 남긴 모양(`****abcd`). key 가 없으면 null 이다. */
    val maskedKey: String?,
    /** 저장된 key 가 마지막으로 바뀐 시각. */
    val updatedAt: Instant?
)

/**
 * TypeSafe API key 를 저장한다. 모양은 [AnthropicKeyService] 와 같고 확인(`/check`)은 없다.
 *
 * key 가 있으면 agent-server 는 Jev 를 OpenRouter 가 아니라 TypeSafe 에 직접 부른다.
 */
@Service
class TypesafeKeyService(
    private val platformSettingRepository: PlatformSettingRepository,
    private val secretCipher: SecretCipher,
    private val agentCacheInvalidator: AgentCacheInvalidator,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(TypesafeKeyService::class.java)

    /** 저장된 key 원문. 없거나 열 수 없으면 null 이다. 내부 포트만 부른다. */
    suspend fun storedKey(): String? {
        val stored = platformSettingRepository.findById(TYPESAFE_API_KEY_SETTING) ?: return null
        val plaintext = secretCipher.decrypt(stored.settingValue, TYPESAFE_API_KEY_SETTING)
        if (plaintext == null) {
            logger.warn(
                "Stored TypeSafe API key could not be decrypted; ARTEL_SECRETS_KEY is missing or changed. " +
                    "Enter the key again on the admin page."
            )
        }
        return plaintext
    }

    suspend fun status(): TypesafeKeyStatus {
        val stored = platformSettingRepository.findById(TYPESAFE_API_KEY_SETTING)
        val apiKey = stored?.let { storedKey() }
        return TypesafeKeyStatus(configured = apiKey != null, maskedKey = apiKey?.let(::mask), updatedAt = stored?.updatedAt)
    }

    /**
     * key 를 바꾼다. null 이나 빈 문자열이면 지운다. 공백이 있거나 길이가 맞지 않으면 400 `invalid_api_key` 다.
     * 저장한 뒤에는 agent-server 의 캐시를 비우라고 요청한다. 그 요청이 실패해도 저장은 성공이다.
     */
    suspend fun update(apiKey: String?, adminUserId: Long): TypesafeKeyStatus {
        val trimmed = apiKey?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            transactionalOperator.executeAndAwait {
                platformSettingRepository.deleteById(TYPESAFE_API_KEY_SETTING)
            }
            logger.info("TypeSafe API key cleared by app_user {}", adminUserId)
            agentCacheInvalidator.invalidateAfterChange("clearing the TypeSafe API key")
            return status()
        }
        validateApiKey(trimmed, MAX_TYPESAFE_API_KEY_LENGTH)
        val ciphertext = secretCipher.encrypt(trimmed, TYPESAFE_API_KEY_SETTING)
        platformSettingRepository.upsert(
            settingKey = TYPESAFE_API_KEY_SETTING,
            settingValue = ciphertext,
            encrypted = true,
            updatedBy = adminUserId,
            updatedAt = Instant.now(clock)
        )
        logger.info("TypeSafe API key replaced by app_user {}", adminUserId)
        agentCacheInvalidator.invalidateAfterChange("saving the TypeSafe API key")
        return status()
    }
}
