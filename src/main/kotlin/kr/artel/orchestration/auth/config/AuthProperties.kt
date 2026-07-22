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
