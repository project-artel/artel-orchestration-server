package kr.artel.orchestration.auth.sdk

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * 로그인 코드가 어느 흐름에서 발급됐는지. 코드에 함께 저장되고, 교환 endpoint는 저마다 자기
 * 값만 받는다.
 *
 * 두 흐름이 같은 왕복을 쓰고 끝에서 서로 다른 자격증명을 낸다. 이 값이 없으면 CLI용으로 낸
 * 코드를 `/api/auth/sdk/token`에서 30일짜리 SDK JWT로 바꿀 수 있고, 반대로 SDK용 코드가
 * `/api/auth/cli-tokens/exchange`에서 CLI 토큰이 된다.
 *
 * 전송 값은 소문자다. `@JsonProperty`가 enum 상수 이름을 대신하므로 `"SDK"`는 들어오지 않는다.
 */
enum class SdkLoginCodeKind(val wireValue: String) {
    @JsonProperty("sdk")
    SDK("sdk"),

    @JsonProperty("cli")
    CLI("cli")
}

/**
 * 브라우저가 보내는 코드 발급 요청. 쿠키 세션으로 인증한다.
 *
 * @property codeChallenge SDK가 만든 code_verifier의 base64url(SHA-256). 브라우저는 이 값을
 * 해석하지 않고 SDK가 준 그대로 전달한다
 * @property kind 이 코드를 무엇으로 바꿀 것인지. 키를 빠뜨리면 [SdkLoginCodeKind.SDK]다 —
 * 이미 배포된 SDK와 중계 페이지는 이 키를 보내지 않으므로, 기본값이 그 둘의 계약을 그대로 둔다
 */
data class SdkLoginCodeRequest(
    @field:NotBlank
    @field:Size(max = 128)
    val codeChallenge: String,

    // springdoc 은 Kotlin 의 non-null 타입을 전부 required 로 적는다. 기본값이 있다는 것을 모르므로,
    // 이 표시가 없으면 계약 문서가 이미 배포된 SDK 의 요청을 규격 위반으로 적게 된다.
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, defaultValue = "sdk")
    val kind: SdkLoginCodeKind = SdkLoginCodeKind.SDK
)

/** 발급된 일회용 코드. 브라우저가 loopback 주소로 되돌려 보낸다. */
data class SdkLoginCodeResponse(val code: String)

/**
 * SDK가 보내는 토큰 교환 요청. 이 요청에는 세션이 없다. 코드와 verifier가 자격증명이다.
 */
data class SdkTokenRequest(
    @field:NotBlank
    @field:Size(max = 128)
    val code: String,

    @field:NotBlank
    @field:Size(min = 43, max = 128)
    val codeVerifier: String
)

/**
 * SDK 토큰과 그 주인.
 *
 * 사용자 정보를 함께 주는 이유는 SDK 오버레이가 "누구로 로그인했는지"를 바로 보여줘야 하기
 * 때문이다. 이것 때문에 SDK가 토큰을 파싱하게 두면 클레임 구조가 SDK에 새어 나간다.
 *
 * refresh 토큰은 access 토큰이 만료된 뒤 재로그인 없이 다시 받기 위한 것이다. 만료 시각이
 * 둘 다 실리므로 SDK는 언제 갱신하고 언제 다시 로그인해야 하는지 스스로 판단할 수 있다.
 */
data class SdkTokenResponse(
    val token: String,
    val expiresAt: Instant,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val userId: String,
    val displayName: String
)

/** SDK가 보내는 재발급 요청. refresh 토큰이 유일한 자격증명이다. */
data class SdkRefreshRequest(
    @field:NotBlank
    @field:Size(max = 4096)
    val refreshToken: String
)

/**
 * 재발급된 access 토큰. refresh 토큰은 함께 주지 않는다. 회전시키지 않으므로 SDK가 처음 받은
 * 것을 그대로 들고 있으면 되고, 그 수명이 세션의 절대 상한이 된다.
 */
data class SdkAccessTokenResponse(val token: String, val expiresAt: Instant)
