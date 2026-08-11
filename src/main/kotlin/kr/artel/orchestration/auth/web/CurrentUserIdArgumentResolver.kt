package kr.artel.orchestration.auth.web

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.UnauthorizedException
import org.springframework.core.MethodParameter
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.reactive.BindingContext
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * [CurrentUserId]가 붙은 파라미터를 세션 사용자 id로 채운다.
 *
 * WebFlux도 MVC와 같은 인자 리졸버 확장점을 갖는다. 이름은 같지만 타입이 다르다
 * (`org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver`,
 * 반환은 `Mono`). 등록은 `WebFluxArgumentResolverConfig`가 한다.
 *
 * 값을 만들지 못하면 401을 던진다. 실제로 그 경로를 타는 것은 **서명은 유효하지만 `sub`가
 * 사용자 id 형식이 아닌 토큰**이다(식별자 규칙 변경 전에 발급되어 브라우저에 남아 있는
 * `github:42` 같은 토큰). 인증 자체가 없는 요청은 `SecurityConfig`가 컨트롤러 이전에 끊는다.
 */
@Component
class CurrentUserIdArgumentResolver(
    private val sessionUserResolver: SessionUserResolver
) : HandlerMethodArgumentResolver {

    /**
     * 타입은 보지 않는다. Kotlin의 non-null `Long`은 JVM 원시 `long`으로 컴파일되어
     * `Long::class.java`와 같지 않다 — 타입까지 비교하면 아무 파라미터도 매치되지 않는다.
     */
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUserId::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        bindingContext: BindingContext,
        exchange: ServerWebExchange
    ): Mono<Any> =
        ReactiveSecurityContextHolder.getContext()
            // principal이 Jwt가 아닌 경우(세션에 남은 OAuth2User 등)도 값이 없는 것으로 본다.
            // 그 상태는 인증이 아니라 사고이므로 500이 아니라 401로 답한다.
            .handle<Any> { context, sink ->
                val userId = (context.authentication?.principal as? Jwt)
                    ?.let(sessionUserResolver::resolve)
                    ?.userId
                if (userId == null) sink.error(UnauthorizedException()) else sink.next(userId)
            }
            // SecurityContext 자체가 비어 있는 경우(필터를 거치지 않은 요청).
            .switchIfEmpty(Mono.error(UnauthorizedException()))
}
