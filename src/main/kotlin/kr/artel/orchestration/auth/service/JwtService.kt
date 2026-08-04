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

/** 사용자 본체와 연결된 모든 제공자 신원. 세션 조회 응답의 원본이다. */
data class UserProfile(
    val userId: String,
    val displayName: String,
    val email: String?,
    /** 홈 UI 표시 언어. null이면 아직 고르지 않은 것이다. */
    val locale: String?,
    val identities: List<LinkedIdentity>
)

data class LinkedIdentity(
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

    /**
     * SDK 런타임이 들고 다니는 토큰. 브라우저 세션과 audience가 다르다.
     *
     * 수명이 길어 웹 API까지 열어주면 새어나간 토큰 하나가 한 달짜리 대시보드 세션이 된다.
     * audience를 나눠 두면 SDK 경로 필터 체인만 이 토큰을 받아들인다.
     *
     * 갱신 수단은 재로그인뿐이다. refresh token은 별도 작업이다.
     */
    fun issueSdkToken(userId: String): SdkToken {
        val issuedAt = Instant.now(clock)
        val expiresAt = issuedAt.plus(properties.sdkTokenTtl)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .audience(listOf(properties.sdkAudience))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .subject(userId)
            .build()

        val headers = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).tokenValue
        return SdkToken(token, expiresAt)
    }
}

/** 발급된 SDK 토큰. 만료 시각을 함께 주어야 SDK가 재로그인 시점을 스스로 판단할 수 있다. */
data class SdkToken(val token: String, val expiresAt: Instant)
