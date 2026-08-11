package kr.artel.orchestration.auth.service

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.config.SecurityConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RefreshTokenServiceTest {
    private val now = Instant.now()
    private val properties = AuthProperties(
        frontendUrl = "http://localhost:5173",
        issuer = "test-issuer",
        audience = "test-audience",
        jwtSecret = "test-only-secret-that-is-at-least-32-bytes-long",
        secureCookie = false,
        refreshAudience = "test-refresh-audience"
    )
    private val securityConfig = SecurityConfig()

    private fun serviceAt(instant: Instant) = RefreshTokenService(
        securityConfig.jwtEncoder(properties),
        securityConfig.refreshJwtDecoder(properties),
        properties,
        Clock.fixed(instant, ZoneOffset.UTC)
    )

    @Test
    fun `verifies a token it issued for the same target`(): Unit = runBlocking {
        val service = serviceAt(now)

        val issued = service.issue("1042", properties.audience, Duration.ofDays(14))

        assertThat(service.verify(issued.token, properties.audience)).isEqualTo(1042L)
        assertThat(issued.expiresAt.epochSecond)
            .isEqualTo(now.plus(Duration.ofDays(14)).epochSecond)
    }

    @Test
    fun `rejects a token issued for another target`(): Unit = runBlocking {
        // SDK용 refresh 토큰으로 브라우저 세션을 재발급받을 수 있으면 audience 분리가 무의미해진다.
        val service = serviceAt(now)

        val issued = service.issue("1042", properties.sdkAudience, Duration.ofDays(90))

        assertThat(service.verify(issued.token, properties.audience)).isNull()
    }

    @Test
    fun `rejects an expired token`(): Unit = runBlocking {
        val issued = serviceAt(now.minus(Duration.ofDays(15)))
            .issue("1042", properties.audience, Duration.ofDays(14))

        assertThat(serviceAt(now).verify(issued.token, properties.audience)).isNull()
    }

    @Test
    fun `rejects an access token presented as a refresh token`(): Unit = runBlocking {
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val accessToken = JwtService(securityConfig.jwtEncoder(properties), properties, clock).issue(
            AuthenticatedUser(
                userId = "1042",
                provider = "github",
                login = "octocat",
                displayName = "The Octocat",
                avatarUrl = null
            )
        )

        assertThat(serviceAt(now).verify(accessToken, properties.audience)).isNull()
    }

    /**
     * 변조는 [tamperSignature]로 만든다. 서명 문자열의 마지막 글자를 바꾸는 방식은 그 자리의
     * 하위 2비트가 base64url 패딩이라 약 6% 확률로 바이트가 그대로 남고, 그때 토큰이 검증을
     * 통과해 이 테스트가 간헐 실패한다. 이유는 [tamperSignature]의 KDoc에 있다.
     */
    @Test
    fun `rejects a token whose signature was tampered with`(): Unit = runBlocking {
        val issued = serviceAt(now).issue("1042", properties.audience, Duration.ofDays(14))

        val tampered = tamperSignature(issued.token)

        assertThat(serviceAt(now).verify(tampered, properties.audience)).isNull()
    }
}
