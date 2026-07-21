package kr.artel.orchestration.auth.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

class SessionUserResolverTest {
    private val resolver = SessionUserResolver()

    private fun jwtWithSubject(subject: String): Jwt = Jwt.withTokenValue("token")
        .header("alg", "HS256")
        .subject(subject)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(900))
        .build()

    @Test
    fun `resolves the user id from the subject`() {
        assertThat(resolver.resolve(jwtWithSubject("1042"))).isEqualTo(SessionUser(1042))
    }

    @Test
    fun `rejects a pre-migration provider-namespaced subject`() {
        // 식별자 형식 변경 전에 발급되어 브라우저에 남아 있는 토큰.
        assertThat(resolver.resolve(jwtWithSubject("github:42"))).isNull()
    }

    @Test
    fun `rejects a non-numeric subject`() {
        assertThat(resolver.resolve(jwtWithSubject("octocat"))).isNull()
    }
}
