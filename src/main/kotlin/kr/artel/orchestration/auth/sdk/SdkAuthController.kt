package kr.artel.orchestration.auth.sdk

import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.common.error.BadRequestException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.auth.service.RefreshTokenService
import kr.artel.orchestration.auth.service.SessionUserResolver
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * SDK 브라우저 로그인의 서버 쪽 절반.
 *
 * 흐름은 이렇다. SDK가 loopback 서버를 띄우고 브라우저로 홈의 중계 페이지를 연다. 중계
 * 페이지는 이미 있는 쿠키 세션으로 [issueCode]를 불러 코드를 받고, 그 코드를 loopback
 * 주소로 되돌려준다. SDK는 받은 코드를 [exchange]로 토큰과 바꾼다.
 *
 * 기존 OAuth 성공 핸들러는 건드리지 않는다. 로그인 자체는 홈에서 하던 그대로 일어나고,
 * 여기는 "이미 로그인된 세션을 SDK로 옮기는" 단계만 맡는다.
 */
@Tag(name = "SDK auth", description = "SDK 브라우저 로그인")
@RestController
@RequestMapping("/api/auth/sdk")
class SdkAuthController(
    private val codeStore: SdkLoginCodeStore,
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService,
    private val properties: AuthProperties,
    private val oauthUserService: OAuthUserService,
    private val sessionUserResolver: SessionUserResolver,
    private val sdkTokenIssuer: SdkTokenIssuer
) {
    /**
     * 브라우저가 부른다. 쿠키 세션이 유일한 인증 수단이다.
     *
     * principal을 non-null로 두면 인증 실패가 401이 아니라 NPE로 새어 나간다. `/api/auth/me`와
     * 같은 이유로 nullable로 받고 직접 401을 던진다.
     */
    @Operation(
        summary = "SDK 로그인 코드 발급",
        description = "로그인된 브라우저 세션을 일회용 코드로 바꾼다. kind 를 빠뜨리면 sdk 다."
    )
    @PostMapping("/codes")
    suspend fun issueCode(
        @AuthenticationPrincipal jwt: Jwt?,
        @Valid @RequestBody request: SdkLoginCodeRequest
    ): SdkLoginCodeResponse {
        val session = jwt?.let(sessionUserResolver::resolve)
            ?: throw UnauthorizedException()
        // 서명은 유효하지만 가리키는 사용자가 없는 토큰으로는 SDK 토큰을 만들 수 없다.
        oauthUserService.findProfile(session.userId)
            ?: throw UnauthorizedException()
        return SdkLoginCodeResponse(
            codeStore.issue(session.userId, request.codeChallenge, request.kind)
        )
    }

    /**
     * SDK가 부른다. 세션이 없는 공개 경로다.
     *
     * 코드가 없거나, 만료됐거나, verifier가 맞지 않거나, CLI용으로 발급된 코드인 경우를 400
     * 하나로 묶는다. 어느 쪽인지 알려주면 코드만 훔친 쪽이 verifier를 좁혀갈 단서가 된다.
     *
     * [SdkLoginCodeKind.SDK]만 받으므로, CLI 로그인이 낸 코드로 30일짜리 SDK 토큰을 받아낼 수 없다.
     *
     * 토큰은 [SdkTokenIssuer]가 만든다. 로그인한 사용자가 왕복 없이 받아 가는
     * `POST /api/auth/sdk-tokens`와 같은 코드다 — 두 경로가 낸 토큰이 서로 달라질 자리를 두지 않는다.
     */
    @Operation(summary = "SDK 토큰 교환", description = "일회용 코드와 code_verifier를 SDK 토큰으로 바꾼다.")
    @PostMapping("/token")
    suspend fun exchange(@Valid @RequestBody request: SdkTokenRequest): SdkTokenResponse {
        val userId = codeStore.consume(request.code, request.codeVerifier, SdkLoginCodeKind.SDK)
            ?: throw BadRequestException("유효하지 않은 로그인 코드입니다.")
        // 코드는 맞지만 가리키는 사용자가 지워진 경우도 같은 400이다. 둘을 가르면 어느 코드가
        // 실재했는지가 단서로 새어 나간다.
        return sdkTokenIssuer.issueFor(userId)
            ?: throw BadRequestException("유효하지 않은 로그인 코드입니다.")
    }

    /**
     * SDK가 부른다. 세션이 없는 공개 경로이며 refresh 토큰이 유일한 자격증명이다.
     *
     * `for` 클레임이 `artel-sdk`인 토큰만 통과하므로, 브라우저 refresh 토큰을 여기로 가져와도
     * 30일짜리 SDK 토큰을 받아낼 수 없다.
     *
     * 실패는 401 하나로 묶는다. 만료인지 위조인지 알려주면 어느 조건을 맞추면 되는지가 단서가 된다.
     */
    @Operation(summary = "SDK 토큰 재발급", description = "refresh 토큰으로 SDK access 토큰을 다시 받는다.")
    @PostMapping("/token/refresh")
    suspend fun refresh(@Valid @RequestBody request: SdkRefreshRequest): SdkAccessTokenResponse {
        val userId = refreshTokenService.verify(request.refreshToken, properties.sdkAudience)
            ?: throw UnauthorizedException()
        // 토큰은 유효해도 사용자가 지워졌을 수 있다. 재발급도 로그인과 같은 조건을 지킨다.
        oauthUserService.findProfile(userId) ?: throw UnauthorizedException()
        val issued = jwtService.issueSdkToken(userId.toString())
        return SdkAccessTokenResponse(issued.token, issued.expiresAt)
    }
}
