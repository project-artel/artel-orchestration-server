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

data class OAuthIdentity(
    val provider: String,
    val providerUserId: String,
    val login: String,
    val displayName: String,
    val avatarUrl: String?,
    val email: String? = null
) {
    val subject: String
        get() = "$provider:$providerUserId"
}

@Service
class JwtService(
    private val jwtEncoder: JwtEncoder,
    private val properties: AuthProperties,
    private val clock: Clock
) {
    fun issue(identity: OAuthIdentity): String {
        val issuedAt = Instant.now(clock)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .audience(listOf(properties.audience))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(properties.accessTokenTtl))
            .subject(identity.subject)
            .claim("provider", identity.provider)
            .claim("login", identity.login)
            .claim("name", identity.displayName)
            .apply { identity.avatarUrl?.let { claim("avatar_url", it) } }
            .build()

        val headers = JwsHeader.with(MacAlgorithm.HS256).build()
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).tokenValue
    }
}
