package kr.artel.orchestration.auth.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kr.artel.orchestration.auth.oauth.OAuthIdentityResolver
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.auth.service.RefreshTokenService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher
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

    private val logger = LoggerFactory.getLogger(SecurityConfig::class.java)

    /**
     * SDK 런타임 전용 체인. 브라우저 체인보다 먼저 잡아야 SDK 경로가 쿠키 세션으로
     * 통과하지 못한다.
     *
     * 체인을 나누는 이유는 audience다. SDK 토큰은 수명이 30일이라 같은 체인에 두면 한 번
     * 새어나간 토큰이 한 달짜리 대시보드 세션이 된다. 여기서는 `aud=artel-sdk`만 받고,
     * 브라우저 체인은 `aud=artel-home`만 받는다.
     *
     * 자격증명은 Authorization 헤더뿐이다. 쿠키 컨버터를 붙이지 않으므로 브라우저 세션으로는
     * 이 경로에 들어올 수 없다.
     */
    @Bean
    @Order(1)
    fun sdkSecurityWebFilterChain(
        http: ServerHttpSecurity,
        // 이름만으로는 @Primary인 브라우저 디코더가 주입된다. 그러면 SDK 토큰이 전부 401이 된다.
        @Qualifier("sdkJwtDecoder") sdkJwtDecoder: NimbusReactiveJwtDecoder
    ): SecurityWebFilterChain = http
        .securityMatcher(PathPatternParserServerWebExchangeMatcher("/api/sdk/**"))
        .csrf { it.disable() }
        .cors { }
        .httpBasic { it.disable() }
        .formLogin { it.disable() }
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .authorizeExchange { it.anyExchange().authenticated() }
        .oauth2ResourceServer {
            it.jwt { jwt -> jwt.jwtDecoder(sdkJwtDecoder) }
            it.authenticationEntryPoint(jsonAuthenticationEntryPoint())
        }
        .exceptionHandling {
            it.authenticationEntryPoint(jsonAuthenticationEntryPoint())
        }
        .build()

    /**
     * GitHub App 설치 복귀 경로 전용 체인.
     *
     * 경로는 여전히 **인증 대상**이다(`project.md` 규칙 2 — 무인증 라우트를 `/api/` 아래 두지 않는다).
     * 이 체인이 따로 있는 이유는 인증 **실패의 모양** 때문이다: 이 요청을 여는 것은 GitHub 에서 돌아온
     * 브라우저인데, 기본 체인의 jsonAuthenticationEntryPoint 는 `{"code":"unauthorized"}` 라는 JSON을
     * 화면에 그대로 뱉는다. access 쿠키 수명이 15분이고 App 설치는 조직 승인까지 그보다 오래 걸릴 수
     * 있어, 실제로 밟게 되는 경로다. 그래서 여기서는 home 으로 302 를 준다.
     */
    @Bean
    @Order(0)
    fun trackerSetupSecurityWebFilterChain(
        http: ServerHttpSecurity,
        properties: AuthProperties
    ): SecurityWebFilterChain = http
        .securityMatcher(PathPatternParserServerWebExchangeMatcher("/api/tracker/github/setup"))
        .csrf { it.disable() }
        .cors { }
        .httpBasic { it.disable() }
        .formLogin { it.disable() }
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .authorizeExchange { it.anyExchange().authenticated() }
        .oauth2ResourceServer {
            it.bearerTokenConverter(cookieTokenConverter(properties))
            it.jwt { }
            it.authenticationEntryPoint(trackerSetupEntryPoint(properties))
        }
        .exceptionHandling { it.authenticationEntryPoint(trackerSetupEntryPoint(properties)) }
        .build()

    /** 세션이 없거나 만료됐을 때 JSON 대신 home 으로 되돌린다. 어느 프로젝트인지는 아직 모른다. */
    private fun trackerSetupEntryPoint(properties: AuthProperties) =
        ServerAuthenticationEntryPoint { exchange, _ ->
            exchange.response.statusCode = HttpStatus.FOUND
            exchange.response.headers.location =
                URI.create("${properties.frontendOrigin}/projects?tracker=failed")
            exchange.response.setComplete()
        }

    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        properties: AuthProperties,
        jwtService: JwtService,
        refreshTokenService: RefreshTokenService,
        authCookies: AuthCookies,
        identityResolver: OAuthIdentityResolver,
        oauthUserService: OAuthUserService
    ): SecurityWebFilterChain = http
        .csrf { it.disable() }
        .cors { }
        .httpBasic { it.disable() }
        .formLogin { it.disable() }
        // 인증 주체는 JWT 쿠키 하나뿐이다. 기본값(WebSession 저장)을 두면 oauth2Login 성공 시점의
        // OAuth2AuthenticationToken이 세션에 남아, JWT 쿠키 없이도 요청이 인증을 통과한다.
        // 그 경우 principal이 Jwt가 아니라 OAuth2User라 컨트롤러가 401 대신 500을 낸다.
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .authorizeExchange {
            it.pathMatchers(
                "/oauth2/**",
                "/login/oauth2/**",
                "/v3/api-docs/**",
                "/swagger-ui.html",
                "/swagger-ui/**",
                // 핸드셰이크가 쿼리 파라미터로 SDK 토큰을 싣는다. 헤더가 아니라 여기서는
                // 걸러낼 수 없어, 검증은 SdkWebSocketHandler가 직접 한다.
                "/ws/sdk",
                // SDK가 코드를 토큰으로 바꾸는 경로. 이 요청에는 아직 세션도 토큰도 없다.
                "/api/auth/sdk/token",
                // 재발급 경로. access 토큰이 만료된 뒤에 부르는 것이 목적이라 인증을 걸면 쓸 수 없다.
                // 자격증명은 refresh 토큰이며 컨트롤러가 직접 검증한다.
                "/api/auth/refresh",
                "/api/auth/sdk/token/refresh",
                // 내부 서버-투-서버 경로 전부. 보내는 주체가 사람이 아니라 Agent 서버라
                // 엔드유저 JWT가 없다. 이 접두사가 곧 신뢰 경계이므로, 새 내부 엔드포인트는
                // 여기에 줄을 더하는 것이 아니라 `/internal/` 아래에 두어야 한다.
                // 이름이 비슷한 /api/projects/{id}/test-case-spec/download는 엔드유저용이라
                // 이 접두사 밖에 있고 인증 대상으로 남는다.
                "/internal/**"
            ).permitAll()
            it.pathMatchers("/api/auth/**").authenticated()
            // test-scenario는 외부(FE) 요청이므로 JWT 인증/인가 대상이다.
            it.pathMatchers("/api/test-scenario/**").authenticated()
            // QA 실행/로그/SSE도 프로젝트 멤버만 접근하는 엔드유저 API다.
            it.pathMatchers("/api/qa-tries/**").authenticated()
            // 런(TR) 단위 QA 실행/조회도 같은 엔드유저 API(ARTEL-259).
            it.pathMatchers("/api/qa-runs/**").authenticated()
            it.anyExchange().authenticated()
        }
        .oauth2Login {
            it.authenticationSuccessHandler(
                oauthSuccessHandler(
                    properties,
                    jwtService,
                    refreshTokenService,
                    authCookies,
                    identityResolver,
                    oauthUserService
                )
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
            it.logoutSuccessHandler(cookieLogoutHandler(authCookies))
        }
        .build()

    @Bean
    fun jwtEncoder(properties: AuthProperties): JwtEncoder {
        val key = SecretKeySpec(properties.jwtSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        return NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key))
    }

    // 두 디코더가 같은 타입이라, 리소스 서버가 타입으로 찾아 쓰는 브라우저 쪽을 @Primary로 둔다.
    // SDK 체인은 sdkJwtDecoder를 이름으로 받아 명시적으로 지정한다.
    @Bean
    @Primary
    fun jwtDecoder(properties: AuthProperties): NimbusReactiveJwtDecoder =
        decoderFor(properties, properties.audience)

    /** SDK 토큰 전용 디코더. audience가 다른 토큰(브라우저 세션)은 여기서 떨어진다. */
    @Bean
    fun sdkJwtDecoder(properties: AuthProperties): NimbusReactiveJwtDecoder =
        decoderFor(properties, properties.sdkAudience)

    /** refresh 토큰 전용 디코더. access 토큰을 재발급 경로에 내밀면 여기서 떨어진다. */
    @Bean
    fun refreshJwtDecoder(properties: AuthProperties): NimbusReactiveJwtDecoder =
        decoderFor(properties, properties.refreshAudience)

    /**
     * tracker 설치 `state` 전용 디코더. 세션 토큰을 state 자리에 내밀면 audience가 달라 떨어진다.
     *
     * 이 디코더는 필터 체인에 붙지 않는다 — state는 Authorization 자격증명이 아니라 쿼리 파라미터로
     * 실려 오는 값이라, 검증은 TrackerSetupStateService가 직접 한다.
     */
    @Bean
    fun trackerSetupJwtDecoder(properties: AuthProperties): NimbusReactiveJwtDecoder =
        decoderFor(properties, properties.trackerSetupAudience)

    private fun decoderFor(properties: AuthProperties, audience: String): NimbusReactiveJwtDecoder {
        val key = SecretKeySpec(properties.jwtSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
        // createDefaultWithIssuer bundles the timestamp (exp/nbf) and issuer validators;
        // setJwtValidator replaces the decoder default, so the timestamp check must be
        // included here explicitly or expired tokens would pass.
        val validators: OAuth2TokenValidator<Jwt> = DelegatingOAuth2TokenValidator(
            JwtValidators.createDefaultWithIssuer(properties.issuer),
            JwtClaimValidator<List<String>?>("aud") { it != null && it.contains(audience) }
        )
        decoder.setJwtValidator(validators)
        return decoder
    }

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun corsConfigurationSource(properties: AuthProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            // allowCredentials=true에서는 allowedOrigins="*"가 금지되므로, 와일드카드(https://*.artel.kr)까지
            // 지원하도록 allowedOriginPatterns를 사용한다. stage/prod/프리뷰 등 복수 출처를 설정에서 주입한다.
            allowedOriginPatterns = properties.corsAllowedOrigins
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
        refreshTokenService: RefreshTokenService,
        authCookies: AuthCookies,
        identityResolver: OAuthIdentityResolver,
        oauthUserService: OAuthUserService
    ) = ServerAuthenticationSuccessHandler { webFilterExchange, authentication ->
        // Spring Security의 리액티브 콜백은 Mono<Void>를 요구한다. upsert가 suspend라 mono {}로 브리지한다.
        mono {
            val identity = identityResolver.resolve(authentication as org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken)
            val persistedIdentity = oauthUserService.upsert(identity)
            val token = jwtService.issue(persistedIdentity)
            val refresh = refreshTokenService.issue(
                persistedIdentity.userId,
                properties.audience,
                properties.refreshTokenTtl
            )
            val response = webFilterExchange.exchange.response
            response.addCookie(authCookies.access(token))
            response.addCookie(authCookies.refresh(refresh.token))
            response.statusCode = HttpStatus.FOUND
            response.headers.location = URI.create(properties.frontendOrigin)
            response.setComplete().awaitSingleOrNull()
        }
            .onErrorResume { error ->
                // 여기서 실패하면 JWT 쿠키가 발급되지 않아 로그인이 조용히 깨진다.
                // 원인을 남기지 않으면 프런트에는 error=server만 보이고 서버에는 아무 흔적이 없다.
                logger.error("OAuth 로그인 후처리 실패", error)
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

    private fun cookieLogoutHandler(authCookies: AuthCookies) = ServerLogoutSuccessHandler { exchange, _ ->
        authCookies.cleared().forEach(exchange.exchange.response::addCookie)
        exchange.exchange.response.statusCode = HttpStatus.NO_CONTENT
        exchange.exchange.response.setComplete()
    }
}
