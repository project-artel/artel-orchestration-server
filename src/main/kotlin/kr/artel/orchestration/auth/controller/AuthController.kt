package kr.artel.orchestration.auth.controller

import kr.artel.orchestration.auth.dto.AuthUserResponse
import kr.artel.orchestration.auth.dto.LinkedIdentityResponse
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.auth.service.SessionUser
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.auth.service.UserProfile
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val oauthUserService: OAuthUserService,
    private val sessionUserResolver: SessionUserResolver
) {
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): Mono<AuthUserResponse> =
        Mono.justOrEmpty<SessionUser>(sessionUserResolver.resolve(jwt))
            // JPA는 블로킹이므로 Netty 이벤트 루프에서 밀어낸다.
            .flatMap { session ->
                Mono.fromCallable { oauthUserService.findProfile(session.userId) }
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap { profile -> Mono.justOrEmpty<UserProfile>(profile) }
            }
            .map { it.toResponse() }
            // 서명은 유효하지만 가리키는 사용자가 없는 토큰은 유효한 세션이 아니다.
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED)))

    private fun UserProfile.toResponse() = AuthUserResponse(
        id = userId,
        displayName = displayName,
        email = email,
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
