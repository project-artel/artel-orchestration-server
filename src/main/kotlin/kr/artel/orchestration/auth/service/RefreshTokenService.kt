package kr.artel.orchestration.auth.service

import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.auth.config.AuthProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 어떤 audience의 access 토큰을 만들 수 있는지 담는 클레임. */
const val REFRESH_TARGET_CLAIM = "for"

/** 발급된 refresh 토큰. 만료 시각을 함께 주어야 SDK가 재로그인 시점을 스스로 판단할 수 있다. */
data class RefreshToken(val token: String, val expiresAt: Instant)

/**
 * refresh 토큰의 발급과 검증. 상태를 두지 않고 JWT 서명만으로 성립한다.
 *
 * access 토큰과 audience가 다르므로(`artel-refresh`) 이 토큰으로는 API를 부를 수 없다. 반대로
 * access 토큰을 refresh 경로에 내밀어도 여기서 떨어진다. 어느 쪽 access 토큰을 재발급할 수
 * 있는지는 [REFRESH_TARGET_CLAIM]이 정하며, 브라우저용 refresh 토큰으로 SDK 토큰을 받아낼 수 없다.
 *
 * ponytail: 상태가 없어 개별 폐기가 불가능하다. 만료 전 로그아웃/탈취 회수가 필요해지면
 * 저장소(테이블·Redis)에 jti를 남기고 폐기 목록을 두는 쪽으로 올린다. 그때까지는 refresh 토큰의
 * 수명이 세션의 절대 상한이다(회전하지 않는 이유이기도 하다. 회전시키면 상한이 사라진다).
 */
@Service
class RefreshTokenService(
    private val jwtEncoder: JwtEncoder,
    // 이름으로 받지 않으면 @Primary인 브라우저 access 디코더가 주입되어 refresh 토큰이 전부 떨어진다.
    @Qualifier("refreshJwtDecoder") private val decoder: NimbusReactiveJwtDecoder,
    private val properties: AuthProperties,
    private val clock: Clock
) {
    fun issue(userId: String, targetAudience: String, ttl: Duration): RefreshToken {
        val issuedAt = Instant.now(clock)
        val expiresAt = issuedAt.plus(ttl)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .audience(listOf(properties.refreshAudience))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .subject(userId)
            .claim(REFRESH_TARGET_CLAIM, targetAudience)
            .build()

        val headers = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).tokenValue
        return RefreshToken(token, expiresAt)
    }

    /**
     * 토큰이 가리키는 사용자 id. 서명·만료·issuer·audience 중 하나라도 어긋나거나 다른 대상의
     * 토큰이면 null이다.
     *
     * 실패 이유를 구분해 돌려주지 않는다. 호출부가 401 하나로 묶어야 어느 조건이 틀렸는지가
     * 단서로 새어 나가지 않는다.
     */
    suspend fun verify(token: String, targetAudience: String): Long? {
        val jwt = try {
            decoder.decode(token).awaitSingleOrNull()
        } catch (_: JwtException) {
            null
        } ?: return null

        if (jwt.getClaimAsString(REFRESH_TARGET_CLAIM) != targetAudience) {
            return null
        }
        // access 토큰과 같은 이유로 sub가 사용자 id 형식이 아니면 유효한 세션이 아니다.
        return jwt.subject?.toLongOrNull()
    }
}
