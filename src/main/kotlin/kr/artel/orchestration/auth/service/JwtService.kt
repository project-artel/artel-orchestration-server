package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.config.AuthProperties
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 제공자 응답을 정규화한 값. 아직 Artel 사용자와 연결되기 전 단계라 사용자 식별자를 갖지 않는다.
 */
data class OAuthIdentity(
    val provider: String,
    val providerUserId: String,
    val login: String,
    val displayName: String,
    val avatarUrl: String?,
    val email: String? = null
)

/**
 * 로그인이 완료되어 Artel 사용자와 연결된 신원. [userId]는 연결된 제공자와 무관하게 안정적이다.
 */
data class AuthenticatedUser(
    val userId: String,
    val provider: String,
    val login: String,
    val displayName: String,
    val avatarUrl: String?
)

@Service
class JwtService(
    private val jwtEncoder: JwtEncoder,
    private val properties: AuthProperties,
    private val clock: Clock
) {
    fun issue(user: AuthenticatedUser): String {
        val issuedAt = Instant.now(clock)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .audience(listOf(properties.audience))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(properties.accessTokenTtl))
            // sub는 Artel 사용자 id다. 제공자를 추가로 연결해도 값이 바뀌지 않는다.
            .subject(user.userId)
            .claim("provider", user.provider)
            .claim("login", user.login)
            .claim("name", user.displayName)
            .apply { user.avatarUrl?.let { claim("avatar_url", it) } }
            .build()

        val headers = JwsHeader.with(MacAlgorithm.HS256).build()
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).tokenValue
    }
}
