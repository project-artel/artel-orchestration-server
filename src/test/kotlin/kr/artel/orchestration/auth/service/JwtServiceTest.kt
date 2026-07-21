package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.config.SecurityConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant

class JwtServiceTest {
    private val now = Instant.now()
    private val properties = AuthProperties(
        frontendUrl = "http://localhost:5173",
        issuer = "test-issuer",
        audience = "test-audience",
        jwtSecret = "test-only-secret-that-is-at-least-32-bytes-long",
        accessTokenTtl = Duration.ofMinutes(15),
        secureCookie = false
    )
    private val securityConfig = SecurityConfig()

    @Test
    fun `issues a signed token with provider-neutral identity claims`() {
        val service = JwtService(
            securityConfig.jwtEncoder(properties),
            properties,
            Clock.fixed(now, java.time.ZoneOffset.UTC)
        )

        val token = service.issue(
            OAuthIdentity(
                provider = "github",
                providerUserId = "42",
                login = "octocat",
                displayName = "The Octocat",
                avatarUrl = "https://avatars.example/octocat.png"
            )
        )
        val jwt = securityConfig.jwtDecoder(properties).decode(token).block()

        assertThat(jwt).isNotNull
        assertThat(jwt?.subject).isEqualTo("github:42")
        assertThat(jwt?.getClaimAsString("provider")).isEqualTo("github")
        assertThat(jwt?.getClaimAsString("login")).isEqualTo("octocat")
        assertThat(jwt?.expiresAt?.epochSecond)
            .isEqualTo(now.plus(Duration.ofMinutes(15)).epochSecond)
    }
}
