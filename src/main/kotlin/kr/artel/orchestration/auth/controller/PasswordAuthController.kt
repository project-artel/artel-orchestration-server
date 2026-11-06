package kr.artel.orchestration.auth.controller

import kr.artel.orchestration.auth.config.AuthCookies
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.config.GitHubOAuthProperties
import kr.artel.orchestration.auth.dto.AuthProvidersResponse
import kr.artel.orchestration.auth.dto.AuthUserResponse
import kr.artel.orchestration.auth.dto.ChangePasswordRequest
import kr.artel.orchestration.auth.dto.LoginRequest
import kr.artel.orchestration.auth.dto.SignupRequest
import kr.artel.orchestration.auth.service.AuthUserResponseReader
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.LoginAttemptLimiter
import kr.artel.orchestration.auth.service.PasswordAccountService
import kr.artel.orchestration.auth.service.RefreshTokenService
import kr.artel.orchestration.auth.service.UNKNOWN_CLIENT_ADDRESS
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.UnauthorizedException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * 이메일과 비밀번호 로그인. GitHub OAuth 없이 직접 설치한 서버를 쓰는 길이다.
 *
 * 가입과 로그인은 OAuth 성공 핸들러와 같은 두 쿠키(access, refresh)를 준다. 그 뒤로는 어느 길로
 * 들어왔든 세션이 같다.
 *
 * `AuthController` 에 얹지 않는 이유는 `CliTokenController` 와 같다 — 그 클래스는 이미 의존이 많고, 이쪽은
 * 자기 서비스와 DTO 를 가진 별개 흐름이다.
 */
@RestController
@RequestMapping("/api/auth")
class PasswordAuthController(
    private val passwordAccountService: PasswordAccountService,
    private val authUserResponseReader: AuthUserResponseReader,
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService,
    private val authCookies: AuthCookies,
    private val authProperties: AuthProperties,
    private val gitHubOAuthProperties: GitHubOAuthProperties,
    private val loginAttemptLimiter: LoginAttemptLimiter
) {
    /** 세션 없이 부른다. 로그인 화면이 GitHub 버튼과 가입 링크를 보일지 고른다. */
    @GetMapping("/providers")
    suspend fun providers(): AuthProvidersResponse = AuthProvidersResponse(
        password = true,
        github = gitHubOAuthProperties.enabled,
        signupOpen = passwordAccountService.signupOpen()
    )

    /** 가입하고 바로 로그인시킨다. 설치의 첫 사용자면 `platformRole` 이 `ADMIN` 이다. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun signup(@RequestBody request: SignupRequest, exchange: ServerWebExchange): AuthUserResponse {
        val user = passwordAccountService.signup(request.email, request.password, request.name)
        return startSession(user, exchange)
    }

    /** 실패가 쌓이면 429 `too_many_attempts` 다. 이메일 한도와 주소 한도의 차이는 [LoginAttemptLimiter] 에 있다. */
    @PostMapping("/login")
    suspend fun login(@RequestBody request: LoginRequest, exchange: ServerWebExchange): AuthUserResponse {
        val user = loginAttemptLimiter.guard(request.email, clientAddressOf(exchange)) {
            passwordAccountService.login(request.email, request.password)
        }
        return startSession(user, exchange)
    }

    /**
     * 요청 주소. `X-Forwarded-For` 가 있으면 `ForwardedHeaderTransformer` 가 그 맨 왼쪽 값으로 바꿔 둔 것이다.
     *
     * `hostString` 을 읽는다. 그 주소는 이름을 풀지 않은 `InetSocketAddress` 라 `address` 가 null 이고,
     * 처음 구현이 `address` 를 읽어 프록시 뒤에서는 주소 한도가 한 번도 걸리지 않았다. 주소가 아예 없으면
     * 한도를 건너뛰지 않고 [UNKNOWN_CLIENT_ADDRESS] 한 묶음으로 센다.
     */
    private fun clientAddressOf(exchange: ServerWebExchange): String =
        exchange.request.remoteAddress?.hostString?.takeIf { it.isNotBlank() } ?: UNKNOWN_CLIENT_ADDRESS

    /**
     * 자기 비밀번호를 바꾼다. `mustChangePassword` 가 true 인 계정도 부를 수 있는 두 경로 중 하나다.
     * 세션은 그대로 둔다 — 다음 요청부터 막힘이 풀린다.
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun changePassword(@CurrentUserId userId: Long, @RequestBody request: ChangePasswordRequest) {
        passwordAccountService.changePassword(userId, request.currentPassword, request.newPassword)
    }

    private suspend fun startSession(user: AuthenticatedUser, exchange: ServerWebExchange): AuthUserResponse {
        val refresh = refreshTokenService.issue(user.userId, authProperties.audience, authProperties.refreshTokenTtl)
        exchange.response.addCookie(authCookies.access(jwtService.issue(user)))
        exchange.response.addCookie(authCookies.refresh(refresh.token))
        return authUserResponseReader.read(user.userId.toLong()) ?: throw UnauthorizedException()
    }
}
