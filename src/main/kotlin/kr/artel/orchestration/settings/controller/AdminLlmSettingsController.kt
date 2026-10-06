package kr.artel.orchestration.settings.controller

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.settings.service.LlmKeyCheckResult
import kr.artel.orchestration.settings.service.LlmKeyService
import kr.artel.orchestration.settings.service.LlmKeyStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `PUT /api/admin/settings/llm` 요청 본문. null 이나 빈 문자열이면 저장된 key 를 지운다.
 *
 * key 가 로그로 나가지 않도록 [toString] 을 덮어쓴다. `logging.level.org.springframework.web: DEBUG` 에서
 * Spring 이 디코드한 본문을 `toString` 으로 남긴다.
 */
data class UpdateLlmKeyRequest(val apiKey: String? = null) {
    override fun toString(): String = "UpdateLlmKeyRequest(apiKeySet=${!apiKey.isNullOrBlank()})"
}

/**
 * 관리 화면의 OpenRouter API key. ADMIN 만 부른다.
 *
 * 어느 응답도 key 원문을 싣지 않는다. 끝 네 글자와 확인 결과만 나간다.
 */
@RestController
@RequestMapping("/api/admin/settings/llm")
class AdminLlmSettingsController(
    private val llmKeyService: LlmKeyService,
    private val platformAccessService: PlatformAccessService
) {
    @GetMapping
    suspend fun llmKeyStatus(@CurrentUserId userId: Long): LlmKeyStatus {
        platformAccessService.requireAdmin(userId)
        return llmKeyService.status()
    }

    @PutMapping
    suspend fun updateLlmKey(@CurrentUserId userId: Long, @RequestBody request: UpdateLlmKeyRequest): LlmKeyStatus {
        platformAccessService.requireAdmin(userId)
        return llmKeyService.updateAdminKey(request.apiKey, userId)
    }

    /** 지금 쓰이는 key 로 OpenRouter 에 묻는다. 결과는 다음 `GET` 의 `lastCheck` 로도 남는다. */
    @PostMapping("/check")
    suspend fun checkLlmKey(@CurrentUserId userId: Long): LlmKeyCheckResult {
        platformAccessService.requireAdmin(userId)
        return llmKeyService.check(userId)
    }
}
