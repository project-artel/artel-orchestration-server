package kr.artel.orchestration.userllmkey.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.llmmodel.service.hasPlatformKey
import kr.artel.orchestration.llmmodel.service.CLAUDE_MODEL_PREFIX
import kr.artel.orchestration.llmmodel.service.userKeyProviderOf
import kr.artel.orchestration.qa.service.QaRunKey
import kr.artel.orchestration.qa.service.QaSessionLlmKey
import kr.artel.orchestration.settings.service.AnthropicKeyService
import kr.artel.orchestration.settings.service.SecretCipher
import kr.artel.orchestration.settings.service.SecretsKeyMissingException
import kr.artel.orchestration.userllmkey.dto.UserLlmKeyResponse
import kr.artel.orchestration.userllmkey.entity.KeySource
import kr.artel.orchestration.userllmkey.entity.LlmKeyProvider
import kr.artel.orchestration.userllmkey.entity.UserLlmKeyEntity
import kr.artel.orchestration.userllmkey.repository.UserLlmKeyRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * key 길이 하한. 세 서비스가 발급하는 key 는 모두 40 글자를 넘는다. `keyHint` 가 앞 6 글자와 끝 4 글자를 보이므로,
 * 하한이 짧으면 hint 가 key 의 대부분을 드러낸다.
 */
private const val MIN_USER_API_KEY_LENGTH = 20

/** key 길이 상한. 관리 화면의 OpenRouter key 와 같다. */
private const val MAX_USER_API_KEY_LENGTH = 512

/**
 * 사용자가 넣은 LLM API key(`user_llm_key`)를 저장하고, 실행 요청의 `keySource` 를 확인한다.
 *
 * key 원문이 이 클래스를 떠나는 길은 [resolveForRun] 하나다. 그 값은 agent-server 의 QA 세션 개설 본문에만
 * 실리고(`llm_key`), 공개 응답에는 `keyHint` 만 나간다.
 */
@Service
class UserLlmKeyService(
    private val repository: UserLlmKeyRepository,
    private val secretCipher: SecretCipher,
    private val checkClient: UserLlmKeyCheckClient,
    private val anthropicKeyService: AnthropicKeyService,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(UserLlmKeyService::class.java)

    suspend fun list(appUserId: Long): List<UserLlmKeyResponse> =
        repository.findAllOfUser(appUserId).map { it.toResponse() }

    /** 이 사용자가 key 를 넣은 서비스와 그 `keyHint`. `/api/qa-models` 가 사용자 key 항목을 채우는 데 쓴다. */
    suspend fun keyHints(appUserId: Long): Map<LlmKeyProvider, String> =
        repository.findAllOfUser(appUserId)
            .mapNotNull { row -> LlmKeyProvider.fromWire(row.service)?.let { it to row.keyHint } }
            .toMap()

    /**
     * key 를 서비스에 한 번 확인하고, 통하면 암호화해 저장한다. 이미 있으면 바꾼다.
     *
     * 확인보다 `ARTEL_SECRETS_KEY` 를 먼저 본다. 저장할 수 없는 key 로 사용자의 서비스를 부를 이유가 없다.
     *
     * | 결과 | 응답 |
     * |---|---|
     * | 모르는 서비스, 빈 key, 접두어가 틀리거나 공백이 있거나 길이가 맞지 않음 | 400 `invalid_request` |
     * | `ARTEL_SECRETS_KEY` 없음 | 409 `secrets_key_missing` |
     * | 서비스가 401 | 400 `llm_key_invalid` |
     * | 서비스가 403 | 400 `llm_key_permission_denied` |
     * | 서비스에 닿지 못했거나 그 밖의 status | 503 `llm_key_check_unavailable` |
     */
    suspend fun register(appUserId: Long, service: String, apiKey: String?): UserLlmKeyResponse {
        val provider = parseProvider(service)
        val trimmed = apiKey?.trim().orEmpty()
        requireKeyShape(provider, trimmed)
        if (!secretCipher.configured) throw SecretsKeyMissingException()

        when (checkClient.check(provider, trimmed)) {
            UserLlmKeyCheck.ACCEPTED -> Unit
            UserLlmKeyCheck.INVALID -> throw LlmKeyInvalidException(provider)
            UserLlmKeyCheck.PERMISSION_DENIED -> throw LlmKeyPermissionDeniedException(provider)
            UserLlmKeyCheck.UNAVAILABLE -> throw LlmKeyCheckUnavailableException(provider)
        }

        // timestamptz 는 마이크로초까지 담는다. 잘라 두어야 이 응답과 다음 GET 의 `verifiedAt` 이 같다.
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        val keyHint = keyHintOf(trimmed)
        repository.upsert(
            appUserId = appUserId,
            service = provider.wire,
            encryptedKey = secretCipher.encrypt(trimmed, UserLlmKeyEntity.settingKey(appUserId, provider)),
            keyHint = keyHint,
            verifiedAt = now,
            updatedAt = now
        )
        logger.info("app_user {} saved a {} API key", appUserId, provider.wire)
        return UserLlmKeyResponse(service = provider.wire, keyHint = keyHint, verifiedAt = now)
    }

    /** 없어도 성공이다. 모르는 서비스만 400 `invalid_request` 다. */
    suspend fun delete(appUserId: Long, service: String) {
        val provider = parseProvider(service)
        if (repository.deleteOne(appUserId, provider.wire) > 0) {
            logger.info("app_user {} deleted their {} API key", appUserId, provider.wire)
        }
    }

    /**
     * QA run 과 try 요청의 `keySource` 를 확인하고, 사용자 key 로 도는 run 이면 그 key 원문을 꺼낸다.
     * `LlmModelService.requireSelectable` 이 model 을 확인한 뒤에 부른다.
     *
     * | 요청 | 결과 |
     * |---|---|
     * | `keySource` 가 `platform`, `user` 가 아님 | 400 `invalid_request` |
     * | `platform` 인데 `gpt/` model | 400 `llm_key_missing`. 공용 OpenAI key 가 없다 |
     * | `platform` 인데 `claude/` model 이고 관리 화면의 Claude API key 가 없음 | 400 `llm_key_missing` |
     * | `user` 인데 model 이 없음 | 400 `invalid_request`. 어느 서비스 key 를 쓸지 정할 수 없다 |
     * | `user` 인데 경로에 사용자 key 서비스가 없음(`bedrock/`) | 400 `llm_key_missing` |
     * | `user` 인데 그 서비스 key 가 없거나 열 수 없음 | 400 `llm_key_missing` |
     */
    suspend fun resolveForRun(appUserId: Long, modelId: String?, keySource: String?): QaRunKey {
        val source = keySource?.let {
            KeySource.fromWire(it) ?: throw BadRequestException("keySource must be one of ${KeySource.WIRE_NAMES}")
        } ?: KeySource.PLATFORM
        val model = modelId?.takeIf { it.isNotBlank() }

        if (source == KeySource.PLATFORM) {
            if (model != null) {
                // 관리 화면의 Claude API key 는 `claude/` model 일 때만 읽는다. 다른 경로와는 상관이 없다.
                val anthropicKeyStored = model.startsWith(CLAUDE_MODEL_PREFIX) && anthropicKeyService.storedKey() != null
                if (!hasPlatformKey(model, anthropicKeyStored)) {
                    throw LlmKeyMissingException("공용 key 로 부를 수 없는 model 입니다. 개인 key 로 실행하세요: $model")
                }
            }
            return QaRunKey(KeySource.PLATFORM)
        }

        if (model == null) throw BadRequestException("keySource 가 user 이면 model 을 함께 보내야 합니다.")
        val provider = userKeyProviderOf(model)
            ?: throw LlmKeyMissingException("개인 key 로 부를 수 없는 model 입니다: $model")
        val stored = repository.findOne(appUserId, provider.wire)
            ?: throw LlmKeyMissingException("${provider.wire} API key 를 먼저 등록하세요: $model")
        val apiKey = secretCipher.decrypt(stored.encryptedKey, UserLlmKeyEntity.settingKey(appUserId, provider))
        if (apiKey == null) {
            logger.warn(
                "Stored {} API key of app_user {} could not be decrypted; ARTEL_SECRETS_KEY is missing or changed.",
                provider.wire, appUserId
            )
            throw LlmKeyMissingException("${provider.wire} API key 를 다시 등록하세요: $model")
        }
        return QaRunKey(KeySource.USER, QaSessionLlmKey(service = provider.wire, apiKey = apiKey))
    }

    private fun parseProvider(service: String): LlmKeyProvider =
        LlmKeyProvider.fromWire(service)
            ?: throw BadRequestException("service must be one of ${LlmKeyProvider.WIRE_NAMES}")

    private fun requireKeyShape(provider: LlmKeyProvider, apiKey: String) {
        if (apiKey.isEmpty()) throw BadRequestException("apiKey 가 비어 있습니다.")
        if (!apiKey.startsWith(provider.keyPrefix)) {
            throw BadRequestException("${provider.wire} API key 는 ${provider.keyPrefix} 로 시작해야 합니다.")
        }
        if (apiKey.length !in MIN_USER_API_KEY_LENGTH..MAX_USER_API_KEY_LENGTH || apiKey.any(Char::isWhitespace)) {
            throw BadRequestException(
                "API key 는 공백 없이 ${MIN_USER_API_KEY_LENGTH}자 이상 ${MAX_USER_API_KEY_LENGTH}자 이하여야 합니다."
            )
        }
    }

    private fun UserLlmKeyEntity.toResponse() =
        UserLlmKeyResponse(service = service, keyHint = keyHint, verifiedAt = verifiedAt)
}

/** 앞 6 글자 + `…` + 끝 4 글자. 사람이 어느 key 를 넣었는지 알아보는 데는 이만큼이면 된다. */
internal fun keyHintOf(apiKey: String): String = apiKey.take(6) + "…" + apiKey.takeLast(4)

/** 실행 요청이 고른 key 가 없다. */
class LlmKeyMissingException(message: String) : BadRequestException(message, code = "llm_key_missing")

/** 서비스가 key 를 401 로 거절했다. */
class LlmKeyInvalidException(provider: LlmKeyProvider) :
    BadRequestException("${provider.wire} 가 이 API key 를 받지 않았습니다.", code = "llm_key_invalid")

/** 서비스가 key 를 403 으로 거절했다. key 는 있지만 그 읽기 권한이 없다. */
class LlmKeyPermissionDeniedException(provider: LlmKeyProvider) :
    BadRequestException("${provider.wire} 가 이 API key 의 권한이 부족하다고 거절했습니다.", code = "llm_key_permission_denied")

/** 서비스에 닿지 못했다. key 에 대해 아무것도 모르므로 틀렸다고 하지 않는다. */
class LlmKeyCheckUnavailableException(provider: LlmKeyProvider) :
    UpstreamUnavailableException("${provider.wire} API key check is unavailable", code = "llm_key_check_unavailable")
