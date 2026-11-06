package kr.artel.orchestration.auth.config

import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import java.net.URI

/** OAuth 복귀 주소는 서버가 허용한 onboarding origin만 세션에 저장한다. */
class OAuthReturnTargetFilter(private val properties: AuthProperties) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        if (path != "/oauth2/authorization/github" && path != "/login/oauth2/code/github") {
            return chain.filter(exchange)
        }
        return exchange.session.flatMap { session ->
            if (path == "/oauth2/authorization/github") {
                session.attributes.remove(SESSION_KEY)
                validatedTarget(exchange.request.queryParams.getFirst("return_to"), properties.onboardingUrl)
                    ?.let { session.attributes[SESSION_KEY] = it.toASCIIString() }
            } else {
                // callback의 query parameter는 신뢰하지 않는다. 시작 요청에서 검증한 값만 한 번 사용한다.
                val target = session.attributes.remove(SESSION_KEY) as? String
                validatedTarget(target, properties.onboardingUrl)?.let { exchange.attributes[SESSION_KEY] = it }
            }
            chain.filter(exchange)
        }
    }

    companion object {
        private const val SESSION_KEY = "artel.oauth.returnTo"

        internal fun validatedTarget(value: String?, allowedOrigin: String): URI? = runCatching {
            if (value.isNullOrBlank()) return null
            val target = URI(value)
            val allowed = URI(allowedOrigin)
            if (target.scheme !in listOf("http", "https") || target.host == null || target.rawUserInfo != null) return null
            if (target.scheme != allowed.scheme || target.host != allowed.host || target.port != allowed.port) return null
            target
        }.getOrNull()

        fun destination(exchange: ServerWebExchange, properties: AuthProperties, error: String? = null): URI {
            val target = exchange.getAttribute<URI>(SESSION_KEY)
            if (target == null) return URI.create(properties.frontendOrigin + if (error == null) "" else "/login?error=$error")
            val builder = UriComponentsBuilder.fromUri(target).replaceQueryParam("auth_error")
            if (error != null) builder.queryParam("auth_error", error)
            return builder.build(true).toUri()
        }
    }
}
