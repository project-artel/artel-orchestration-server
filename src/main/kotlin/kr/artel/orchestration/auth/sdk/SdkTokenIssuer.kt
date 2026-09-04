package kr.artel.orchestration.auth.sdk

import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.auth.service.RefreshTokenService
import org.springframework.stereotype.Service

/**
 * SDK 토큰과 그 refresh 토큰이 나오는 유일한 자리.
 *
 * 부르는 곳이 둘이다 — 브라우저 loopback 이 일회용 코드를 바꾸는 [SdkAuthController.exchange] 와,
 * 로그인한 사용자가 왕복 없이 받아 가는 [SdkTokenController.mint]. 두 곳이 각자 만들면 audience
 * 나 수명이 한쪽에서만 바뀌는 날이 오고, 그날 SDK 는 "어느 경로로 받은 토큰이냐"에 따라 다르게
 * 동작한다. 만드는 자리가 하나면 그 차이가 생길 자리가 없다.
 *
 * 토큰 자체는 그대로 [JwtService.issueSdkToken] 과 [RefreshTokenService.issue] 가 만든다. 이
 * 클래스가 더하는 것은 그 둘을 부르는 순서와 응답에 실을 프로필뿐이다.
 */
@Service
class SdkTokenIssuer(
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService,
    private val oauthUserService: OAuthUserService,
    private val properties: AuthProperties
) {
    /**
     * [userId] 의 SDK 토큰. 그 사용자가 없으면 null 이다.
     *
     * 사용자를 먼저 읽는 이유는 응답에 표시 이름이 필요해서만이 아니다. 자격증명은 유효한데
     * 가리키는 사용자가 지워진 경우에 토큰이 나가면 안 된다. 그때 무엇을 답할지는 부르는 쪽마다
     * 다르므로(코드 교환은 400, 발급은 401) 여기서는 예외를 던지지 않고 null 을 낸다.
     */
    suspend fun issueFor(userId: Long): SdkTokenResponse? {
        val profile = oauthUserService.findProfile(userId) ?: return null
        val issued = jwtService.issueSdkToken(userId.toString())
        val refresh = refreshTokenService.issue(
            userId.toString(),
            properties.sdkAudience,
            properties.sdkRefreshTokenTtl
        )
        return SdkTokenResponse(
            token = issued.token,
            expiresAt = issued.expiresAt,
            refreshToken = refresh.token,
            refreshExpiresAt = refresh.expiresAt,
            userId = profile.userId,
            displayName = profile.displayName
        )
    }
}
