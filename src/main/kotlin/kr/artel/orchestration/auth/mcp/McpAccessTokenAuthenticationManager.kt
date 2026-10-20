package kr.artel.orchestration.auth.mcp

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier
import io.netty.channel.ChannelOption
import kr.artel.orchestration.auth.cli.CREDENTIAL_CLAIM
import kr.artel.orchestration.auth.cli.CREDENTIAL_MCP
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient

/**
 * MCP access JWT 가 헤더에 싣는 `typ`(RFC 9068 §2.1). `application/` 을 붙인 꼴도 RFC 가 허용하므로 받는다.
 */
private val MCP_ACCESS_TOKEN_TYPES = arrayOf(JOSEObjectType("at+jwt"), JOSEObjectType("application/at+jwt"))

/** MCP access JWT 의 scope claim 이름. 값은 공백으로 나눈 문자열이다(`macro:read macro:write`). */
const val MCP_SCOPE_CLAIM = "scope"

/**
 * MCP access JWT 를 검증한다(ARTEL-1088, 계약 §2). `SecurityConfig` 가 브라우저 체인에 끼운 `AuthenticationWebFilter`
 * 가 [McpAccessTokenDetector] 가 고른 요청에 대해서만 이것을 부른다.
 *
 * 검증하는 것: 헤더 `typ` = `at+jwt`(RFC 9068), 서명(MCP JWKS, RS256·ES256), `iss` = `artel.mcp.issuer`, `aud` 에
 * `artel.mcp.audience`, `exp`·`nbf`.
 *
 * **`typ` 은 `at+jwt` 만 받는다.** MCP 서버가 access token 을 이 `typ` 으로만 발급한다. Nimbus 의 기본 검사는 `JWT` 나
 * `typ` 없음만 받아서 그대로 두면 MCP 의 token 이 전부 401 이다. 반대로 `JWT` 를 더 받지 않는 이유는 같은 키로 서명한
 * 다른 종류의 JWT 가 access token 자리에 들어오는 것을 `typ` 으로 막기 위해서다.
 *
 * **JWKS 캐시.** `NimbusReactiveJwtDecoder.withJwkSetUri` 의 `ReactiveRemoteJWKSource` 가 한 번 받은 JWK set 을 들고
 * 있다가, token 의 `kid` 가 그 안에 없을 때만 다시 받는다. MCP 가 키를 돌리면 새 `kid` 를 처음 만난 요청이 한 번
 * 더 받아 온다. 대가: 모르는 `kid` 를 실은 token 마다 JWKS 를 다시 부르므로, 그런 token 을 쏟아붓는 요청이 MCP
 * JWKS 주소로 그대로 증폭된다. 받는 쪽이 정적 JSON 이라 지금은 감수한다.
 *
 * 꺼져 있으면(`McpProperties.accessTokenEnabled` false) decoder 를 만들지 않는다 — JWKS 주소가 없어 만들 수도
 * 없다. 그때는 [McpAccessTokenDetector] 가 아무 요청도 고르지 않으므로 [authenticate] 가 불리지 않는다.
 *
 * **빈으로 내지 않는다.** `ServerHttpSecurityConfiguration` 이 `ReactiveAuthenticationManager` 빈이 하나일 때 그것을
 * 체인의 기본 manager 로 집어 가는데, 지금 그 하나가 `CliTokenAuthenticationManager` 다. 둘이 되면 기동이 멈춘다.
 * `SecurityConfig` 가 필터를 만들 때 직접 만든다.
 */
class McpAccessTokenAuthenticationManager(
    properties: McpProperties,
) : ReactiveAuthenticationManager {

    private val delegate: JwtReactiveAuthenticationManager? =
        if (properties.accessTokenEnabled) {
            JwtReactiveAuthenticationManager(decoderFor(properties)).apply {
                setJwtAuthenticationConverter { jwt ->
                    // authorities 를 넘기는 생성자만 authenticated=true 다(CliTokenAuthenticationManager 와 같은 함정).
                    Mono.just(JwtAuthenticationToken(mcpAccessPrincipal(jwt), emptyList()))
                }
            }
        } else {
            null
        }

    override fun authenticate(authentication: Authentication): Mono<Authentication> =
        delegate?.authenticate(authentication)
            ?: Mono.error(InvalidBearerTokenException("MCP access token is not accepted"))

    private fun decoderFor(properties: McpProperties): NimbusReactiveJwtDecoder {
        val timeoutMillis = properties.jwksTimeout.toMillis().toInt()
        val jwksClient = WebClient.builder()
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create()
                        .responseTimeout(properties.jwksTimeout)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis)
                )
            )
            .build()
        val decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwksUri)
            .jwsAlgorithms { algorithms ->
                algorithms.add(SignatureAlgorithm.RS256)
                algorithms.add(SignatureAlgorithm.ES256)
            }
            .webClient(jwksClient)
            .jwtProcessorCustomizer { processor ->
                processor.jwsTypeVerifier = DefaultJOSEObjectTypeVerifier(*MCP_ACCESS_TOKEN_TYPES)
            }
            .build()
        // setJwtValidator 는 기본 검증기를 갈아 끼우므로 exp/nbf 검사가 든 createDefaultWithIssuer 를 함께 넣는다
        // (SecurityConfig.decoderFor 와 같은 이유).
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(properties.issuer),
                JwtClaimValidator<List<String>?>("aud") { it != null && properties.audience in it },
            )
        )
        return decoder
    }
}

/**
 * 검증된 MCP access JWT 를 컨트롤러가 받는 principal 로 바꾼다.
 *
 * claims 는 그대로 두고 [CREDENTIAL_CLAIM] = [CREDENTIAL_MCP] 만 더한다. `sub` 가 `app_user.id` 라
 * `@CurrentUserId` 와 `AccountStateWebFilter` 가 손대지 않고 그대로 돈다(`cliTokenPrincipal` 과 같은 방식).
 * 자격증명 종류를 봐야 하는 자리 — scope 허용표(`McpScopeWebFilter`)와 handoff 발급 — 는 이 claim 을 본다.
 *
 * `tokenValue` 에 원문을 넣지 않는다. principal 은 로그와 오류 속성을 타고 어디로든 나가는 값이다.
 */
fun mcpAccessPrincipal(verified: Jwt): Jwt =
    Jwt.withTokenValue("mcp-access:${verified.id ?: verified.getClaimAsString("grant_id") ?: "-"}")
        .headers { headers -> headers.putAll(verified.headers) }
        .claims { claims ->
            claims.putAll(verified.claims)
            claims[CREDENTIAL_CLAIM] = CREDENTIAL_MCP
        }
        .build()

/** principal 이 MCP access JWT 에서 온 것인지. */
fun Jwt.isMcpAccess(): Boolean = getClaimAsString(CREDENTIAL_CLAIM) == CREDENTIAL_MCP

/** MCP access JWT 가 허락받은 scope. claim 이 문자열이면 공백으로 나누고, 배열이면 원소를 그대로 쓴다. */
fun Jwt.mcpScopes(): Set<String> =
    when (val scope = claims[MCP_SCOPE_CLAIM]) {
        is String -> scope.split(' ').filter { it.isNotBlank() }.toSet()
        is Collection<*> -> scope.mapNotNull { it?.toString() }.toSet()
        else -> emptySet()
    }
