package kr.artel.orchestration.tracker.service

import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.tracker.entity.TrackerProvider
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
import java.time.Instant

/** state 가 실어 나르는 것. 어느 프로젝트에, 누가, 어느 `provider` 를 붙이려 했는가. */
data class TrackerSetupState(
    val projectId: Long,
    val userId: Long,
    val provider: TrackerProvider
)

private const val PROJECT_CLAIM = "project_id"
private const val PROVIDER_CLAIM = "provider"

/**
 * GitHub App 설치 화면으로 보냈다가 돌아오는 `state` 의 발급과 검증.
 *
 * **서명이 이 조각의 존재 이유다.** state 가 그냥 프로젝트 id 였다면, 아무나 자기 브라우저로 설치를
 * 시작하면서 남의 projectId 를 적어 넣어 그 프로젝트에 자기 `installation` 을 붙일 수 있다.
 *
 * [RefreshTokenService][kr.artel.orchestration.auth.service.RefreshTokenService] 와 발급·검증 모양이
 * 겹치는 것은 의도한 것이다. 공용 서명기를 뽑아내려면 이 기능 때문에 `auth/` 의 기존 두 클래스를
 * 고쳐야 하는데, `coding-style.md` 가 말리는 것이 바로 그런 무관한 정리다. 세 번째로 필요해지면 뽑는다.
 */
@Service
class TrackerSetupStateService(
    private val jwtEncoder: JwtEncoder,
    // 이름으로 받지 않으면 @Primary인 브라우저 access 디코더가 주입되어 state가 전부 떨어진다.
    @Qualifier("trackerSetupJwtDecoder") private val decoder: NimbusReactiveJwtDecoder,
    private val properties: AuthProperties,
    private val clock: Clock
) {
    fun issue(projectId: Long, userId: Long, provider: TrackerProvider): String {
        val issuedAt = Instant.now(clock)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .audience(listOf(properties.trackerSetupAudience))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(properties.trackerSetupStateTtl))
            .subject(userId.toString())
            .claim(PROJECT_CLAIM, projectId.toString())
            .claim(PROVIDER_CLAIM, provider.name)
            .build()
        val headers = JwsHeader.with(MacAlgorithm.HS256).build()
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).tokenValue
    }

    /**
     * state 가 가리키는 프로젝트와 사람. 서명·만료·issuer·audience 중 하나라도 어긋나면 null 이다.
     *
     * 실패 이유를 구분해 돌려주지 않는다 — 어느 조건이 틀렸는지가 단서로 새어 나가지 않게, 호출부가
     * 하나의 실패로 묶어 처리한다(`RefreshTokenService.verify` 와 같은 판단).
     */
    suspend fun verify(state: String?): TrackerSetupState? {
        if (state.isNullOrBlank()) return null
        val jwt = try {
            decoder.decode(state).awaitSingleOrNull()
        } catch (_: JwtException) {
            null
        } ?: return null

        val projectId = jwt.getClaimAsString(PROJECT_CLAIM)?.toLongOrNull() ?: return null
        val userId = jwt.subject?.toLongOrNull() ?: return null
        val provider = TrackerProvider.parse(jwt.getClaimAsString(PROVIDER_CLAIM)) ?: return null
        return TrackerSetupState(projectId, userId, provider)
    }
}
