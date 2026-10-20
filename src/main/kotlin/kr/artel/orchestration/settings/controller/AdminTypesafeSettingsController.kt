package kr.artel.orchestration.settings.controller

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.settings.service.TypesafeKeyService
import kr.artel.orchestration.settings.service.TypesafeKeyStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `PUT /api/admin/settings/typesafe` 요청 본문. null 이나 빈 문자열이면 저장된 key 를 지운다.
 *
 * [toString] 을 덮어쓰는 이유는 [UpdateLlmKeyRequest] 와 같다.
 */
data class UpdateTypesafeKeyRequest(val apiKey: String? = null) {
    override fun toString(): String = "UpdateTypesafeKeyRequest(apiKeySet=${!apiKey.isNullOrBlank()})"
}

/** 관리 화면의 TypeSafe API key. ADMIN 만 부른다. 어느 응답도 key 원문을 싣지 않는다. */
@RestController
@RequestMapping("/api/admin/settings/typesafe")
class AdminTypesafeSettingsController(
    private val typesafeKeyService: TypesafeKeyService,
    private val platformAccessService: PlatformAccessService
) {
    @GetMapping
    suspend fun typesafeKeyStatus(@CurrentUserId userId: Long): TypesafeKeyStatus {
        platformAccessService.requireAdmin(userId)
        return typesafeKeyService.status()
    }

    /** `ARTEL_SECRETS_KEY` 가 없으면 409 `secrets_key_missing` 이다. */
    @PutMapping
    suspend fun updateTypesafeKey(
        @CurrentUserId userId: Long,
        @RequestBody request: UpdateTypesafeKeyRequest
    ): TypesafeKeyStatus {
        platformAccessService.requireAdmin(userId)
        return typesafeKeyService.update(request.apiKey, userId)
    }
}
