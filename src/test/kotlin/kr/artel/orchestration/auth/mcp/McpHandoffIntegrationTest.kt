package kr.artel.orchestration.auth.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.cli.CliTokenService
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * MCP 로그인 handoff 발급과 handoff JWKS(ARTEL-1088, 계약 §1).
 *
 * handoff 서명 키는 `generate-dev-key` 로 기동마다 새로 만든다. 그래서 발급된 JWT 는 테스트가 아는 키가 아니라
 * 서버가 낸 `/.well-known/artel-handoff-jwks.json` 으로만 검증한다 — MCP 가 하는 일과 같다.
 *
 * MCP access JWT 도 켠다(가짜 JWKS 서버). MCP access JWT 로 handoff 를 만들 수 없다는 것을 보려면 그 token 이
 * 인증까지는 통과해야 한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpHandoffIntegrationTest {

    companion object {
        private const val MCP_BASE_URL = "https://mcp.artel.test"
        private const val MCP_ISSUER = "https://mcp.artel.test/issuer"
        private const val HANDOFF_ISSUER = "https://api.artel.test"

        private val mcpKey: RSAKey = RSAKeyGenerator(2048).keyID("mcp-rsa-1").generate()

        private val fakeMcpJwks: DisposableServer by lazy {
            HttpServer.create().port(0).route { routes ->
                routes.get("/.well-known/jwks.json") { _, response ->
                    response.header("Content-Type", "application/json")
                        .sendString(Mono.just(JWKSet(mcpKey.toPublicJWK()).toString(true))).then()
                }
            }.bindNow()
        }

        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            registry.add("artel.mcp.base-url") { "$MCP_BASE_URL/" }
            registry.add("artel.mcp.issuer") { MCP_ISSUER }
            registry.add("artel.mcp.jwks-uri") { "http://localhost:${fakeMcpJwks.port()}/.well-known/jwks.json" }
            registry.add("artel.mcp.handoff.issuer") { HANDOFF_ISSUER }
            registry.add("artel.mcp.handoff.generate-dev-key") { "true" }
        }
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUsers: OAuthUserService
    @Autowired private lateinit var cliTokenService: CliTokenService
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `세션 사용자가 허용하면 JWKS 로 검증되는 2분짜리 handoff JWT 를 MCP 주소에 실어 준다`() {
        val user = newUser()
        val issuedAround = Instant.now()

        val answer = post(cookie = user.session, body = """{"requestId":"req_ABC-123.~"}""")

        assertThat(answer.status).isEqualTo(200)
        val redirect = UriComponentsBuilder.fromUriString(answer.body["redirectUrl"].asText()).build()
        assertThat("${redirect.scheme}://${redirect.host}${redirect.path}")
            .describedAs("base-url 끝의 / 는 한 번만 붙는다").isEqualTo("$MCP_BASE_URL/oauth/handoff")
        val token = SignedJWT.parse(redirect.queryParams.getFirst("token"))

        val published = JWKSet.parse(get("/.well-known/artel-handoff-jwks.json").bodyText)
        val publishedKey = published.getKeyByKeyId(token.header.keyID)
        assertThat(publishedKey).describedAs("JWT 의 kid 가 JWKS 에 있다").isNotNull()
        assertThat(token.header.algorithm).isEqualTo(JWSAlgorithm.RS256)
        assertThat(token.verify(RSASSAVerifier(publishedKey.toRSAKey()))).isTrue()

        val claims = token.jwtClaimsSet
        assertThat(claims.issuer).isEqualTo(HANDOFF_ISSUER)
        assertThat(claims.audience).containsExactly("artel-macro-mcp")
        assertThat(claims.subject).isEqualTo(user.userId.toString())
        assertThat(claims.getStringClaim("request_id")).isEqualTo("req_ABC-123.~")
        assertThat(claims.jwtid).isNotBlank()
        val lifetime = Duration.between(claims.issueTime.toInstant(), claims.expirationTime.toInstant())
        assertThat(lifetime).isEqualTo(Duration.ofMinutes(2))
        assertThat(claims.expirationTime.toInstant())
            .isBetween(issuedAround.plusSeconds(110), issuedAround.plusSeconds(130))
    }

    @Test
    fun `같은 요청을 두 번 허용해도 jti 는 매번 다르다`() {
        val user = newUser()

        val jtis = (1..2).map {
            val answer = post(cookie = user.session, body = """{"requestId":"same-request"}""")
            assertThat(answer.status).isEqualTo(200)
            val redirect = UriComponentsBuilder.fromUriString(answer.body["redirectUrl"].asText()).build()
            SignedJWT.parse(redirect.queryParams.getFirst("token")).jwtClaimsSet.jwtid
        }

        assertThat(jtis[0]).isNotEqualTo(jtis[1])
    }

    @Test
    fun `Authorization 헤더의 세션 JWT 로도 발급된다`() {
        val user = newUser()

        assertThat(post(bearer = user.session, body = """{"requestId":"r1"}""").status).isEqualTo(200)
    }

    @Test
    fun `requestId 가 비었거나 길거나 URL 에 못 싣는 글자면 400 이다`() {
        val user = newUser()
        val bodies = mapOf(
            "빈 값" to """{"requestId":""}""",
            "129자" to """{"requestId":"${"a".repeat(129)}"}""",
            "공백" to """{"requestId":"a b"}""",
            "/" to """{"requestId":"a/b"}""",
            "&" to """{"requestId":"a&token=x"}""",
        )
        for ((reason, body) in bodies) {
            val answer = post(cookie = user.session, body = body)
            assertThat(answer.status).describedAs(reason).isEqualTo(400)
            assertThat(answer.body["code"].asText()).describedAs(reason).isEqualTo("invalid_request")
            assertThat(answer.body["fields"].has("requestId")).describedAs(reason).isTrue()
        }
        // 필드가 빠지면 검증 전에 역직렬화에서 막혀 다른 모든 경로와 같은 일반 400 이다.
        assertThat(post(cookie = user.session, body = "{}").status).describedAs("빠짐").isEqualTo(400)
        assertThat(post(cookie = user.session, body = """{"requestId":"${"a".repeat(128)}"}""").status)
            .describedAs("128자는 받는다").isEqualTo(200)
    }

    @Test
    fun `로그인하지 않았으면 401 이다`() {
        assertThat(post(body = """{"requestId":"r1"}""").status).isEqualTo(401)
    }

    @Test
    fun `CLI 토큰으로는 403 mcp_handoff_requires_session 이다`(): Unit = runBlocking {
        val user = newUser()
        val cliToken = cliTokenService.issue(user.userId, "노트북", 30).token

        val answer = post(bearer = cliToken, body = """{"requestId":"r1"}""")

        assertThat(answer.status).isEqualTo(403)
        assertThat(answer.body["code"].asText()).isEqualTo("mcp_handoff_requires_session")
    }

    @Test
    fun `MCP access JWT 로는 403 이다 - 허용표 밖이라 컨트롤러에 닿기 전에 막힌다`() {
        val user = newUser()

        val answer = post(bearer = mintMcpAccess(user.userId), body = """{"requestId":"r1"}""")

        assertThat(answer.status).isEqualTo(403)
        assertThat(answer.body["code"].asText()).isEqualTo("mcp_route_forbidden")
        assertThat(get("/api/projects", bearer = mintMcpAccess(user.userId)).status)
            .describedAs("대조군: 같은 token 이 허용표 안의 경로는 연다").isEqualTo(200)
    }

    @Test
    fun `handoff JWKS 는 인증 없이 읽히고 공개 성분만 싣는다`() {
        val answer = get("/.well-known/artel-handoff-jwks.json")

        assertThat(answer.status).isEqualTo(200)
        assertThat(answer.cacheControl).contains("max-age=300")
        val keys = objectMapper.readTree(answer.bodyText)["keys"]
        assertThat(keys.size()).isEqualTo(1)
        val key = keys[0]
        assertThat(key["kty"].asText()).isEqualTo("RSA")
        assertThat(key["use"].asText()).isEqualTo("sig")
        assertThat(key["alg"].asText()).isEqualTo("RS256")
        assertThat(key["kid"].asText()).isNotBlank()
        assertThat(key.has("d")).describedAs("개인키 성분이 없다").isFalse()
        assertThat(key.has("p")).isFalse()
    }

    // ---------- 픽스처 ----------

    private data class User(val userId: Long, val session: String)

    private fun newUser(): User = runBlocking {
        val seed = UUID.randomUUID().toString().take(8)
        val user: AuthenticatedUser = oauthUsers.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "handoff-$seed",
                login = "handoff-$seed",
                displayName = "handoff-$seed",
                avatarUrl = null,
                email = "handoff-$seed@example.com",
            )
        )
        User(user.userId.toLong(), jwtService.issue(user))
    }

    private fun mintMcpAccess(userId: Long): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(MCP_ISSUER)
            .audience("artel-orchestration")
            .subject(userId.toString())
            .claim("scope", "macro:read macro:write")
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now.minusSeconds(60)))
            .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(mcpKey.keyID).type(JOSEObjectType("at+jwt")).build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(mcpKey)) }.serialize()
    }

    private data class Answer(val status: Int, val bodyText: String, val cacheControl: String?) {
        val body: JsonNode get() = ObjectMapper().readTree(bodyText)
    }

    private fun post(cookie: String? = null, bearer: String? = null, body: String): Answer {
        var request = client().post().uri("/api/mcp/handoffs").contentType(MediaType.APPLICATION_JSON)
        if (cookie != null) request = request.cookie(COOKIE, cookie)
        if (bearer != null) request = request.header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
        return request.bodyValue(body).exchangeToMono(::answerOf).block()!!
    }

    private fun get(path: String, bearer: String? = null): Answer {
        var request = client().get().uri(path)
        if (bearer != null) request = request.header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
        return request.exchangeToMono(::answerOf).block()!!
    }

    private fun answerOf(response: org.springframework.web.reactive.function.client.ClientResponse): Mono<Answer> =
        response.bodyToMono(String::class.java).defaultIfEmpty("null").map { text ->
            Answer(response.statusCode().value(), text, response.headers().asHttpHeaders().cacheControl)
        }

    private fun client() = WebClient.create("http://localhost:$port")
}

private const val COOKIE = "artel_access_token"
