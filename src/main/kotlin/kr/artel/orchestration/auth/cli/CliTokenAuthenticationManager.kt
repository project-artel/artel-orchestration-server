package kr.artel.orchestration.auth.cli

import kotlinx.coroutines.reactor.mono
import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * `artel_` bearer 를 `cli_token` 조회로 해석한다. `SecurityConfig` 가 브라우저 체인에 끼운
 * `AuthenticationWebFilter` 가 이것을 부른다.
 *
 * 실패는 `InvalidBearerTokenException` 이다. 이미 `AuthenticationException` 이라 새 예외 클래스가
 * 필요 없고, 필터의 failure handler 가 그대로 401 로 옮긴다.
 */
@Component
class CliTokenAuthenticationManager(
    private val cliTokenService: CliTokenService
) : ReactiveAuthenticationManager {

    override fun authenticate(authentication: Authentication): Mono<Authentication> = mono {
        val bearer = authentication as BearerTokenAuthenticationToken
        val principal = cliTokenService.authenticate(bearer.token)
            ?: throw InvalidBearerTokenException("CLI token is not usable")
        // authorities 를 넘기는 생성자만 authenticated=true 로 만든다. 인자 하나짜리 생성자로
        // 만들면 필터가 미인증 토큰을 SecurityContext 에 넣어, 인증에 성공한 요청이 401 이 된다.
        JwtAuthenticationToken(principal, emptyList())
    }
}
