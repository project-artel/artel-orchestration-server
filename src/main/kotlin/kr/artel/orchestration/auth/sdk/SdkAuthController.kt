package kr.artel.orchestration.auth.sdk

import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.common.error.BadRequestException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthUserService
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
    private val oauthUserService: OAuthUserService,
    private val sessionUserResolver: SessionUserResolver
) {
    /**
     * 브라우저가 부른다. 쿠키 세션이 유일한 인증 수단이다.
     *
     * principal을 non-null로 두면 인증 실패가 401이 아니라 NPE로 새어 나간다. `/api/auth/me`와
     * 같은 이유로 nullable로 받고 직접 401을 던진다.
     */
    @Operation(summary = "SDK 로그인 코드 발급", description = "로그인된 브라우저 세션을 일회용 코드로 바꾼다.")
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
        return SdkLoginCodeResponse(codeStore.issue(session.userId, request.codeChallenge))
    }

    /**
     * SDK가 부른다. 세션이 없는 공개 경로다.
     *
     * 코드가 없거나, 만료됐거나, verifier가 맞지 않는 경우를 400 하나로 묶는다. 어느 쪽인지
     * 알려주면 코드만 훔친 쪽이 verifier를 좁혀갈 단서가 된다.
     */
    @Operation(summary = "SDK 토큰 교환", description = "일회용 코드와 code_verifier를 SDK 토큰으로 바꾼다.")
    @PostMapping("/token")
    suspend fun exchange(@Valid @RequestBody request: SdkTokenRequest): SdkTokenResponse {
        val userId = codeStore.consume(request.code, request.codeVerifier)
            ?: throw BadRequestException("유효하지 않은 로그인 코드입니다.")
        val profile = oauthUserService.findProfile(userId)
            ?: throw BadRequestException("유효하지 않은 로그인 코드입니다.")
        val issued = jwtService.issueSdkToken(userId.toString())
        return SdkTokenResponse(
            token = issued.token,
            expiresAt = issued.expiresAt,
            userId = profile.userId,
            displayName = profile.displayName
        )
    }
}
