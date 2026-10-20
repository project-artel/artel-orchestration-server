package kr.artel.orchestration.userllmkey.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.userllmkey.dto.UpdateUserLlmKeyRequest
import kr.artel.orchestration.userllmkey.dto.UserLlmKeyResponse
import kr.artel.orchestration.userllmkey.service.UserLlmKeyService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인한 사용자가 넣는 LLM API key. `{service}` 는 `anthropic`, `openai`, `openrouter` 다.
 *
 * 어느 응답도 key 원문을 싣지 않는다. 앞 6 글자와 끝 4 글자를 남긴 `keyHint` 만 나간다.
 */
@RestController
@RequestMapping("/api/auth/me/llm-keys")
class UserLlmKeyController(
    private val userLlmKeyService: UserLlmKeyService
) {
    /** 넣은 key 만 서비스 이름 순서로 돌려준다. */
    @GetMapping
    suspend fun listLlmKeys(@CurrentUserId userId: Long): List<UserLlmKeyResponse> =
        userLlmKeyService.list(userId)

    /**
     * 서비스에 읽기 호출을 한 번 해서 key 가 통하면 저장한다. 이미 있으면 바꾼다.
     *
     * 400 `invalid_request`(모르는 서비스, 빈 key, 접두어 `sk-ant-` · `sk-` · `sk-or-` 가 틀림),
     * 400 `llm_key_invalid`(서비스가 401), 400 `llm_key_permission_denied`(서비스가 403),
     * 503 `llm_key_check_unavailable`(닿지 못했거나 그 밖의 status), 409 `secrets_key_missing` 이다.
     */
    @PutMapping("/{service}")
    suspend fun putLlmKey(
        @CurrentUserId userId: Long,
        @PathVariable service: String,
        @RequestBody request: UpdateUserLlmKeyRequest
    ): UserLlmKeyResponse = userLlmKeyService.register(userId, service, request.apiKey)

    /** 없어도 204 다. */
    @DeleteMapping("/{service}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteLlmKey(
        @CurrentUserId userId: Long,
        @PathVariable service: String
    ) {
        userLlmKeyService.delete(userId, service)
    }
}
