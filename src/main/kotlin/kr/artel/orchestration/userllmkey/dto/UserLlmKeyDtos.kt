package kr.artel.orchestration.userllmkey.dto

import java.time.Instant

/** `GET /api/auth/me/llm-keys` 의 한 항목이자 `PUT /api/auth/me/llm-keys/{service}` 의 응답. key 원문은 담지 않는다. */
data class UserLlmKeyResponse(
    /** `anthropic`, `openai`, `openrouter`. */
    val service: String,
    /** 앞 6 글자 + `…` + 끝 4 글자. */
    val keyHint: String,
    /** 서비스가 마지막으로 이 key 를 받아 준 시각. */
    val verifiedAt: Instant
)

/**
 * `PUT /api/auth/me/llm-keys/{service}` 요청 본문.
 *
 * [toString] 을 덮어쓰는 이유는 WebFlux 의 Jackson decoder 가 DEBUG 로그에 읽은 객체의 `toString` 을 남기기
 * 때문이다. 이 서버는 `org.springframework.web` 을 DEBUG 로 둔다.
 */
data class UpdateUserLlmKeyRequest(val apiKey: String? = null) {
    override fun toString(): String = "UpdateUserLlmKeyRequest(apiKeySet=${!apiKey.isNullOrBlank()})"
}
