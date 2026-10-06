package kr.artel.orchestration.auth.web

import kotlinx.coroutines.reactor.mono
import kr.artel.orchestration.auth.service.AccountBlock
import kr.artel.orchestration.auth.service.AccountStateService
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.Optional

/** 막힌 계정도 부를 수 있는 경로. 세션을 끝내거나 새로 여는 길이라 막으면 빠져나갈 수 없다. */
private val SESSION_PATHS = setOf(
    HttpMethod.POST to "/api/auth/logout",
    HttpMethod.POST to "/api/auth/refresh",
    HttpMethod.POST to "/api/auth/login",
    HttpMethod.POST to "/api/auth/signup",
    HttpMethod.GET to "/api/auth/providers"
)

/** 비밀번호를 바꿔야 하는 계정이 부를 수 있는 경로. 화면이 상태를 읽고 비밀번호를 바꾸는 데 필요한 둘이다. */
private val PASSWORD_CHANGE_PATHS = setOf(
    HttpMethod.GET to "/api/auth/me",
    HttpMethod.POST to "/api/auth/password"
)

/**
 * 인증된 요청을 받기 전에 계정이 막혔는지 본다. 막혔으면 403 이다.
 *
 * - `account_disabled`: ADMIN 이 막은 계정. [SESSION_PATHS] 말고는 전부 막는다.
 * - `password_change_required`: 임시 비밀번호로 들어온 계정. [SESSION_PATHS] 와 [PASSWORD_CHANGE_PATHS]
 *   말고는 전부 막는다.
 *
 * 컨트롤러마다 확인을 넣지 않고 필터 하나로 두는 이유는 빠뜨린 경로가 곧 구멍이기 때문이다. 새
 * 엔드포인트가 생겨도 이 필터를 지난다.
 *
 * **`@Bean` 으로 내지 않는다.** `SecurityConfig.cliTokenAuthenticationFilter` 와 같은 이유로, `WebFilter`
 * 빈은 security 체인 밖 전역 목록에도 들어가 security context 가 없는 자리에서 한 번 더 돈다.
 *
 * 응답을 여기서 직접 쓰는 것은 `.agents/docs/error-handling.md` 의 "타입 예외를 던진다" 에서 벗어나는
 * 자리다. `WebFilter` 에서 던진 예외는 `ApiExceptionHandler` 에 닿지 못하고 500 이 된다
 * (`InternalApiConfig.notFound` 와 같은 사정). 본문 모양은 `ApiErrorResponse` 와 같게 맞춘다.
 */
class AccountStateWebFilter(
    private val accountStateService: AccountStateService
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        ReactiveSecurityContextHolder.getContext()
            .flatMap { context ->
                val userId = (context.authentication?.principal as? Jwt)?.subject?.toLongOrNull()
                if (userId == null) Mono.empty() else mono { accountStateService.blockOf(userId) }
            }
            .filter { block -> !isAllowed(block, exchange) }
            .map { Optional.of(it) }
            .defaultIfEmpty(Optional.empty())
            .flatMap { block ->
                if (block.isPresent) writeForbidden(exchange, block.get()) else chain.filter(exchange)
            }

    private fun isAllowed(block: AccountBlock, exchange: ServerWebExchange): Boolean {
        val request = exchange.request
        val route = request.method to request.path.pathWithinApplication().value()
        if (route in SESSION_PATHS) return true
        return block == AccountBlock.PASSWORD_CHANGE_REQUIRED && route in PASSWORD_CHANGE_PATHS
    }

    private fun writeForbidden(exchange: ServerWebExchange, block: AccountBlock): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.FORBIDDEN
        response.headers.contentType = MediaType.APPLICATION_JSON
        // code 와 message 는 우리가 정한 상수라 JSON escape 가 필요한 글자가 없다.
        val body = """{"code":"${block.code}","message":"${block.message}","fields":{}}"""
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.toByteArray(Charsets.UTF_8))))
    }
}
