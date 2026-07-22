package kr.artel.orchestration.auth.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import kr.artel.orchestration.auth.oauth.OAuthIdentityResolver
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthUserService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Clock
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(AuthProperties::class)
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        properties: AuthProperties,
        jwtService: JwtService,
        identityResolver: OAuthIdentityResolver,
        oauthUserService: OAuthUserService
    ): SecurityWebFilterChain = http
        .csrf { it.disable() }
        .cors { }
        .httpBasic { it.disable() }
        .formLogin { it.disable() }
        .authorizeExchange {
            it.pathMatchers(
                "/oauth2/**",
                "/login/oauth2/**",
                "/v3/api-docs/**",
                "/swagger-ui.html",
                "/swagger-ui/**",
                "/ws/sdk",
                // SDK가 instanceKey로 스스로를 등록하는 경로다. 게임을 실행하는 쪽에는
                // 로그인 세션이 없으므로 엔드유저 JWT로 막을 수 없다.
                "/api/sdk/registrations",
                "/api/orchestration/**",
                // SDK/Agent 경로와 같은 신뢰 경계에 있다. 엔드유저 JWT 보호 대상이 아니다.
                "/api/test-scenario/**"
            ).permitAll()
            it.pathMatchers("/api/auth/**").authenticated()
            it.anyExchange().authenticated()
        }
        .oauth2Login {
            it.authenticationSuccessHandler(
                oauthSuccessHandler(properties, jwtService, identityResolver, oauthUserService)
            )
            it.authenticationFailureHandler { webFilterExchange, _ ->
                webFilterExchange.exchange.response.statusCode = HttpStatus.FOUND
                webFilterExchange.exchange.response.headers.location =
                    URI.create("${properties.frontendOrigin}/login?error=oauth")
                webFilterExchange.exchange.response.setComplete()
            }
        }
        .oauth2ResourceServer {
            it.bearerTokenConverter(cookieTokenConverter(properties))
            it.jwt { }
            it.authenticationEntryPoint(jsonAuthenticationEntryPoint())
        }
        .exceptionHandling {
            it.authenticationEntryPoint(jsonAuthenticationEntryPoint())
        }
        .logout {
            it.logoutUrl("/api/auth/logout")
            it.logoutSuccessHandler(cookieLogoutHandler(properties))
        }
        .build()

    @Bean
    fun jwtEncoder(properties: AuthProperties): JwtEncoder {
        val key = SecretKeySpec(properties.jwtSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        return NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key))
    }

    @Bean
    fun jwtDecoder(properties: AuthProperties): NimbusReactiveJwtDecoder {
        val key = SecretKeySpec(properties.jwtSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
        // createDefaultWithIssuer bundles the timestamp (exp/nbf) and issuer validators;
        // setJwtValidator replaces the decoder default, so the timestamp check must be
        // included here explicitly or expired tokens would pass.
        val validators: OAuth2TokenValidator<Jwt> = DelegatingOAuth2TokenValidator(
            JwtValidators.createDefaultWithIssuer(properties.issuer),
            JwtClaimValidator<List<String>?>("aud") { it != null && it.contains(properties.audience) }
        )
        decoder.setJwtValidator(validators)
        return decoder
    }

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun corsConfigurationSource(properties: AuthProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = listOf(properties.frontendOrigin)
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf(HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION)
            allowCredentials = true
            maxAge = 3600L
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }

    private fun oauthSuccessHandler(
        properties: AuthProperties,
        jwtService: JwtService,
        identityResolver: OAuthIdentityResolver,
        oauthUserService: OAuthUserService
    ) = ServerAuthenticationSuccessHandler { webFilterExchange, authentication ->
        val identity = identityResolver.resolve(authentication as org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken)
        oauthUserService.upsert(identity)
            .flatMap { persistedIdentity ->
                val token = jwtService.issue(persistedIdentity)
                val cookie = ResponseCookie.from(properties.cookieName, token)
                    .httpOnly(true)
                    .secure(properties.secureCookie)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(properties.accessTokenTtl)
                    .build()
                val response = webFilterExchange.exchange.response
                response.addCookie(cookie)
                response.statusCode = HttpStatus.FOUND
                response.headers.location = URI.create(properties.frontendOrigin)
                response.setComplete()
            }
            .onErrorResume {
                val response = webFilterExchange.exchange.response
                response.statusCode = HttpStatus.FOUND
                response.headers.location =
                    URI.create("${properties.frontendOrigin}/login?error=server")
                response.setComplete()
            }
    }

    private fun cookieTokenConverter(properties: AuthProperties): ServerAuthenticationConverter {
        val headerConverter = ServerBearerTokenAuthenticationConverter()
        return ServerAuthenticationConverter { exchange ->
            headerConverter.convert(exchange).switchIfEmpty(Mono.defer {
                val token = exchange.request.cookies.getFirst(properties.cookieName)?.value
                if (token.isNullOrBlank()) Mono.empty<Authentication>()
                else Mono.just<Authentication>(BearerTokenAuthenticationToken(token))
            })
        }
    }

    private fun jsonAuthenticationEntryPoint() = ServerAuthenticationEntryPoint { exchange, _ ->
        exchange.response.statusCode = HttpStatus.UNAUTHORIZED
        exchange.response.headers.contentType = org.springframework.http.MediaType.APPLICATION_JSON
        val body = exchange.response.bufferFactory()
            .wrap("{\"code\":\"unauthorized\",\"message\":\"Authentication is required\"}".toByteArray())
        exchange.response.writeWith(Mono.just(body))
    }

    private fun cookieLogoutHandler(properties: AuthProperties) = ServerLogoutSuccessHandler { exchange, _ ->
        exchange.exchange.response.addCookie(
            ResponseCookie.from(properties.cookieName, "")
                .httpOnly(true)
                .secure(properties.secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(java.time.Duration.ZERO)
                .build()
        )
        exchange.exchange.response.statusCode = HttpStatus.NO_CONTENT
        exchange.exchange.response.setComplete()
    }
}
