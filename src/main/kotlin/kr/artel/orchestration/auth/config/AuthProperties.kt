package kr.artel.orchestration.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("artel.auth")
data class AuthProperties(
    val frontendUrl: String,
    val issuer: String,
    val audience: String,
    val jwtSecret: String,
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val cookieName: String = "artel_access_token",
    val secureCookie: Boolean = true,
    // refresh 토큰은 access 토큰과 audience를 나눈다. 같으면 refresh 토큰 하나로 API를 그대로
    // 부를 수 있어, 수명만 긴 access 토큰이 된다. 어느 audience의 access 토큰을 만들 수 있는지는
    // 토큰 안의 `for` 클레임이 들고 다닌다.
    val refreshAudience: String = "artel-refresh",
    val refreshTokenTtl: Duration = Duration.ofDays(14),
    val refreshCookieName: String = "artel_refresh_token",
    // SDK는 사람이 창을 띄우기 어려운 환경(빌드 머신, 에디터)에서 돌아 재로그인 비용이 크다.
    val sdkRefreshTokenTtl: Duration = Duration.ofDays(90),
    // SDK 토큰은 브라우저 세션과 audience를 나눈다. 수명이 30일이라 웹 API까지 열어주면
    // 한 번 새어나간 토큰이 한 달짜리 대시보드 세션이 된다.
    val sdkAudience: String = "artel-sdk",
    val sdkTokenTtl: Duration = Duration.ofDays(30),
    // 브라우저가 받아 SDK에 넘기는 일회용 코드의 수명. 사람이 창을 옮기는 시간이면 충분하다.
    val sdkLoginCodeTtl: Duration = Duration.ofMinutes(5),
    // CORS 허용 origin(패턴). OAuth 리다이렉트 대상인 frontendUrl(단일)과 달리, API를 호출할 수 있는
    // 브라우저 출처는 여러 개일 수 있다(stage/prod, Vercel 프리뷰 등). https://*.artel.kr 같은 와일드카드도
    // 허용되도록 CORS는 allowedOriginPatterns로 적용한다.
    val allowedOrigins: List<String> = emptyList()
) {
    /** Frontend origin without a trailing slash, safe to concatenate redirect paths onto. */
    val frontendOrigin: String
        get() = frontendUrl.trimEnd('/')

    /** CORS 허용 목록. 설정된 origin에 리다이렉트 대상(frontendOrigin)을 항상 포함한다. */
    val corsAllowedOrigins: List<String>
        get() = (allowedOrigins.map { it.trimEnd('/') } + frontendOrigin).distinct()

    init {
        require(jwtSecret.toByteArray(Charsets.UTF_8).size >= 32) {
            "ARTEL_JWT_SECRET must contain at least 32 bytes"
        }
    }
}
