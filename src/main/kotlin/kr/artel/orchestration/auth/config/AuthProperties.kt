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
    val secureCookie: Boolean = true
) {
    init {
        require(jwtSecret.toByteArray(Charsets.UTF_8).size >= 32) {
            "ARTEL_JWT_SECRET must contain at least 32 bytes"
        }
    }
}
