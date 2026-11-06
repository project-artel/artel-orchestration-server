package kr.artel.orchestration.settings.controller

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.settings.service.AnthropicCheck
import kr.artel.orchestration.settings.service.AnthropicKeyService
import kr.artel.orchestration.settings.service.AnthropicKeyStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `PUT /api/admin/settings/anthropic` 요청 본문. null 이나 빈 문자열이면 저장된 key 를 지운다.
 *
 * [toString] 을 덮어쓰는 이유는 [UpdateLlmKeyRequest] 와 같다.
 */
data class UpdateAnthropicKeyRequest(val apiKey: String? = null) {
    override fun toString(): String = "UpdateAnthropicKeyRequest(apiKeySet=${!apiKey.isNullOrBlank()})"
}

/**
 * 관리 화면의 Claude API(Anthropic API) key. ADMIN 만 부른다.
 *
 * 어느 응답도 key 원문을 싣지 않는다. 끝 네 글자와 확인 결과만 나간다.
 */
@RestController
@RequestMapping("/api/admin/settings/anthropic")
class AdminAnthropicSettingsController(
    private val anthropicKeyService: AnthropicKeyService,
    private val platformAccessService: PlatformAccessService
) {
    @GetMapping
    suspend fun anthropicKeyStatus(@CurrentUserId userId: Long): AnthropicKeyStatus {
        platformAccessService.requireAdmin(userId)
        return anthropicKeyService.status()
    }

    /** `sk-ant-` 로 시작하지 않으면 400 `invalid_api_key`, `ARTEL_SECRETS_KEY` 가 없으면 409 `secrets_key_missing` 이다. */
    @PutMapping
    suspend fun updateAnthropicKey(
        @CurrentUserId userId: Long,
        @RequestBody request: UpdateAnthropicKeyRequest
    ): AnthropicKeyStatus {
        platformAccessService.requireAdmin(userId)
        return anthropicKeyService.update(request.apiKey, userId)
    }

    /** `GET /v1/models` 를 한 번 불러 켜진 `claude/` model 이 목록에 있는지 본다. 결과는 다음 `GET` 의 `lastCheck` 로도 남는다. */
    @PostMapping("/check")
    suspend fun checkAnthropicKey(@CurrentUserId userId: Long): AnthropicCheck {
        platformAccessService.requireAdmin(userId)
        return anthropicKeyService.check(userId)
    }
}
