package kr.artel.orchestration.auth.controller

import kr.artel.orchestration.auth.dto.AuthUserResponse
import kr.artel.orchestration.auth.dto.LinkedIdentityResponse
import kr.artel.orchestration.auth.dto.UpdateLocaleRequest
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.auth.service.SessionUser
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.auth.service.UserProfile
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/** 홈 UI가 번역을 제공하는 언어. 새 번역이 추가될 때 함께 넓힌다. */
private val SUPPORTED_LOCALES = setOf("en", "ko")

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val oauthUserService: OAuthUserService,
    private val sessionUserResolver: SessionUserResolver
) {
    @GetMapping("/me")
    // principal이 Jwt가 아니면 인자 resolver가 예외 대신 null을 넘긴다. 타입을 non-null로 두면
    // 인증 실패가 401이 아니라 NPE(500)로 새어 나온다.
    fun me(@AuthenticationPrincipal jwt: Jwt?): Mono<AuthUserResponse> =
        Mono.justOrEmpty<SessionUser>(jwt?.let(sessionUserResolver::resolve))
            .flatMap { session -> oauthUserService.findProfile(session.userId) }
            .map { it.toResponse() }
            // 서명은 유효하지만 가리키는 사용자가 없는 토큰은 유효한 세션이 아니다.
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED)))

    @PutMapping("/me/locale")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateLocale(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: UpdateLocaleRequest
    ): Mono<Void> {
        if (request.locale !in SUPPORTED_LOCALES) {
            return Mono.error(ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported locale"))
        }

        return Mono.justOrEmpty<SessionUser>(sessionUserResolver.resolve(jwt))
            .flatMap { session -> oauthUserService.updateLocale(session.userId, request.locale) }
            // me()와 같은 이유: 가리키는 사용자가 없는 토큰은 유효한 세션이 아니다.
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED)))
            .then()
    }

    private fun UserProfile.toResponse() = AuthUserResponse(
        id = userId,
        displayName = displayName,
        email = email,
        locale = locale,
        identities = identities.map {
            LinkedIdentityResponse(
                provider = it.provider,
                login = it.login,
                displayName = it.displayName,
                avatarUrl = it.avatarUrl
            )
        }
    )
}
