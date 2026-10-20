package kr.artel.orchestration.settings.controller

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.settings.service.BedrockCheck
import kr.artel.orchestration.settings.service.BedrockKeyService
import kr.artel.orchestration.settings.service.BedrockKeyStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `PUT /api/admin/settings/bedrock` 요청 본문. null 이나 빈 문자열이면 저장된 key 를 지운다.
 *
 * [toString] 을 덮어쓰는 이유는 [UpdateLlmKeyRequest] 와 같다.
 */
data class UpdateBedrockKeyRequest(val apiKey: String? = null) {
    override fun toString(): String = "UpdateBedrockKeyRequest(apiKeySet=${!apiKey.isNullOrBlank()})"
}

/**
 * 관리 화면의 Bedrock API key. ADMIN 만 부른다.
 *
 * 어느 응답도 key 원문을 싣지 않는다. 끝 네 글자와 확인 결과만 나간다.
 */
@RestController
@RequestMapping("/api/admin/settings/bedrock")
class AdminBedrockSettingsController(
    private val bedrockKeyService: BedrockKeyService,
    private val platformAccessService: PlatformAccessService
) {
    @GetMapping
    suspend fun bedrockKeyStatus(@CurrentUserId userId: Long): BedrockKeyStatus {
        platformAccessService.requireAdmin(userId)
        return bedrockKeyService.status()
    }

    /** `ARTEL_SECRETS_KEY` 가 없으면 409 `secrets_key_missing` 이다. */
    @PutMapping
    suspend fun updateBedrockKey(
        @CurrentUserId userId: Long,
        @RequestBody request: UpdateBedrockKeyRequest
    ): BedrockKeyStatus {
        platformAccessService.requireAdmin(userId)
        return bedrockKeyService.update(request.apiKey, userId)
    }

    /** 켜진 Bedrock model 마다 Converse 를 한 번 부른다. 결과는 다음 `GET` 의 `lastCheck` 로도 남는다. */
    @PostMapping("/check")
    suspend fun checkBedrockKey(@CurrentUserId userId: Long): BedrockCheck {
        platformAccessService.requireAdmin(userId)
        return bedrockKeyService.check(userId)
    }
}
