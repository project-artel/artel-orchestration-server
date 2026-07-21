package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.config.SecurityConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

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
            AuthenticatedUser(
                userId = "1042",
                provider = "github",
                login = "octocat",
                displayName = "The Octocat",
                avatarUrl = "https://avatars.example/octocat.png"
            )
        )
        val jwt = securityConfig.jwtDecoder(properties).decode(token).block()

        assertThat(jwt).isNotNull
        // sub는 제공자와 무관한 Artel 사용자 id다.
        assertThat(jwt?.subject).isEqualTo("1042")
        assertThat(jwt?.getClaimAsString("provider")).isEqualTo("github")
        assertThat(jwt?.getClaimAsString("login")).isEqualTo("octocat")
        assertThat(jwt?.expiresAt?.epochSecond)
            .isEqualTo(now.plus(Duration.ofMinutes(15)).epochSecond)
    }

    private val user = AuthenticatedUser(
        userId = "1042",
        provider = "github",
        login = "octocat",
        displayName = "The Octocat",
        avatarUrl = null
    )

    private fun issueWith(props: AuthProperties, clock: Clock): String =
        JwtService(securityConfig.jwtEncoder(props), props, clock).issue(user)

    @Test
    fun `rejects an expired token`() {
        // Issued 20 minutes ago with a 15-minute TTL, so it is already expired now.
        val pastClock = Clock.fixed(now.minus(Duration.ofMinutes(20)), ZoneOffset.UTC)
        val token = issueWith(properties, pastClock)

        assertThatThrownBy { securityConfig.jwtDecoder(properties).decode(token).block() }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `rejects a token whose signature was tampered with`() {
        val token = issueWith(properties, Clock.fixed(now, ZoneOffset.UTC))
        val (header, payload, signature) = token.split(".")
        val tampered = "$header.$payload.${signature.dropLast(1)}${if (signature.last() == 'A') 'B' else 'A'}"

        assertThatThrownBy { securityConfig.jwtDecoder(properties).decode(tampered).block() }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `rejects a token from an unexpected issuer`() {
        val token = issueWith(properties.copy(issuer = "evil-issuer"), Clock.fixed(now, ZoneOffset.UTC))

        assertThatThrownBy { securityConfig.jwtDecoder(properties).decode(token).block() }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `rejects a token for an unexpected audience`() {
        val token = issueWith(properties.copy(audience = "evil-audience"), Clock.fixed(now, ZoneOffset.UTC))

        assertThatThrownBy { securityConfig.jwtDecoder(properties).decode(token).block() }
            .isInstanceOf(JwtException::class.java)
    }
}
