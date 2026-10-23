package kr.artel.orchestration.auth.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.cli.CliTokenService
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP access JWT 인증(ARTEL-1088, 계약 §2).
 *
 * MCP 서버의 JWKS 와 agent-server 는 이 클래스가 띄운 가짜 서버 하나다. MCP 서명 키는 테스트가 만들어 [jwks] 에
 * 넣고, 같은 키로 token 을 서명한다. [jwksFetches] 로 JWKS 를 몇 번 받아 갔는지 본다.
 *
 * 세션 쿠키·`Authorization` 의 세션 JWT·CLI 토큰이 이 설정에서도 그대로 통하는지를 같은 파일에서 본다. MCP 를 켠
 * 컨텍스트에서 기존 경로가 깨지는 것이 이 작업이 낼 수 있는 가장 넓은 회귀다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpAccessTokenIntegrationTest {

    companion object {
        private const val MCP_ISSUER = "https://mcp.artel.test"

        /** MCP 서버가 access token 에 싣는 `typ`(RFC 9068). */
        private val AT_JWT = JOSEObjectType("at+jwt")

        private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID("mcp-rsa-1").generate()
        private val ecKey: ECKey = ECKeyGenerator(Curve.P_256).keyID("mcp-ec-1").generate()

        /** JWKS 가 내는 공개키. 키를 돌리는 테스트가 바꾼다. */
        private val jwks = AtomicReference(JWKSet(listOf<JWK>(rsaKey.toPublicJWK(), ecKey.toPublicJWK())))
        private val jwksFetches = AtomicInteger()

        private val fakeMcpAndAgent: DisposableServer by lazy {
            HttpServer.create().port(0).route { routes ->
                routes.get("/.well-known/jwks.json") { _, response ->
                    jwksFetches.incrementAndGet()
                    response.header("Content-Type", "application/json")
                        .sendString(Mono.just(jwks.get().toString(true))).then()
                }
                routes.get("/internal/macros/grammar") { _, response ->
                    response.header("Content-Type", "text/plain").sendString(Mono.just("grammar")).then()
                }
                routes.post("/internal/macros/check") { request, response ->
                    request.receive().then(
                        response.header("Content-Type", "application/json").sendString(
                            Mono.just("""{"ok":true,"definition":{"name":"open_shop"},"parameters":[],"docstring":"상점을 연다"}""")
                        ).then()
                    )
                }
            }.bindNow()
        }

        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            registry.add("artel.mcp.issuer") { MCP_ISSUER }
            registry.add("artel.mcp.jwks-uri") { "http://localhost:${fakeMcpAndAgent.port()}/.well-known/jwks.json" }
            registry.add("artel.agent.base-url") { "http://localhost:${fakeMcpAndAgent.port()}/internal" }
        }
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUsers: OAuthUserService
    @Autowired private lateinit var cliTokenService: CliTokenService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository

    @Test
    fun `macro read token 이 프로젝트 목록·build 목록·cheat 목록·문법·developer macro 조회를 연다`() {
        val world = newWorld()
        val token = mint(world.userId, scope = "macro:read")

        val projectList = call(token, HttpMethod.GET, "/api/projects")
        assertThat(projectList.status).isEqualTo(200)
        assertThat(projectList.body["total"].asLong()).describedAs("sub 가 @CurrentUserId 로 풀린다").isEqualTo(1)

        assertThat(call(token, HttpMethod.GET, "${world.base}/game-builds").status).isEqualTo(200)
        val functions = call(token, HttpMethod.GET, "${world.base}/game-builds/${world.buildId}/game-functions")
        assertThat(functions.status).isEqualTo(200)
        assertThat(functions.body["revision"].asInt()).describedAs("목록을 보낸 적 없는 빌드").isEqualTo(0)
        assertThat(call(token, HttpMethod.GET, "/api/macros/grammar").status).isEqualTo(200)
        assertThat(call(token, HttpMethod.GET, "${world.base}/developer-macros").status).isEqualTo(200)
        assertThat(call(token, HttpMethod.GET, "${world.base}/developer-macros/open_shop").status)
            .describedAs("허용된 경로이고 그 이름이 없어서 404 다").isEqualTo(404)
    }

    @Test
    fun `macro write token 이 검사·등록·삭제를 열고, read 만 있으면 403 insufficient_scope 다`() {
        val world = newWorld()
        val writer = mint(world.userId, scope = "macro:read macro:write")
        val reader = mint(world.userId, scope = "macro:read")
        val body = """{"source":"def open_shop() -> None:\n    \"\"\"상점을 연다\"\"\"\n","gameBuildId":"${world.buildId}"}"""
        val checkBody = """{"name":"open_shop","source":"def open_shop() -> None: ...","gameBuildId":"${world.buildId}"}"""

        val refused = call(reader, HttpMethod.PUT, "${world.base}/developer-macros/open_shop", body)
        assertThat(refused.status).isEqualTo(403)
        assertThat(refused.body["code"].asText()).isEqualTo("insufficient_scope")
        assertThat(call(reader, HttpMethod.POST, "${world.base}/developer-macros:check", checkBody).status).isEqualTo(403)
        assertThat(call(reader, HttpMethod.DELETE, "${world.base}/developer-macros/open_shop").status).isEqualTo(403)

        assertThat(call(writer, HttpMethod.POST, "${world.base}/developer-macros:check", checkBody).status).isEqualTo(200)
        val stored = call(writer, HttpMethod.PUT, "${world.base}/developer-macros/open_shop", body)
        assertThat(stored.status).isEqualTo(200)
        assertThat(stored.body["updatedBy"]["id"].asText()).isEqualTo(world.userId.toString())
        assertThat(call(writer, HttpMethod.DELETE, "${world.base}/developer-macros/open_shop").status).isEqualTo(204)
    }

    @Test
    fun `cheat 목록은 macro read 가 없으면 403 insufficient_scope 다`() {
        val world = newWorld()
        val writeOnly = mint(world.userId, scope = "macro:write")

        val refused = call(writeOnly, HttpMethod.GET, "${world.base}/game-builds/${world.buildId}/game-functions")
        assertThat(refused.status).isEqualTo(403)
        assertThat(refused.body["code"].asText()).isEqualTo("insufficient_scope")
    }

    @Test
    fun `허용표 밖의 경로는 scope 와 상관없이 403 mcp_route_forbidden 이다`() {
        val world = newWorld()
        val token = mint(world.userId, scope = "macro:read macro:write")

        val requests = listOf(
            HttpMethod.GET to "/api/auth/me",
            HttpMethod.POST to "/api/auth/cli-tokens",
            HttpMethod.POST to "/api/mcp/handoffs",
            HttpMethod.GET to world.base,
            HttpMethod.GET to "${world.base}/game-builds/${world.buildId}/macros",
            HttpMethod.POST to "/api/projects",
        )
        for ((method, path) in requests) {
            val answer = call(token, method, path, if (method == HttpMethod.GET) null else "{}")
            assertThat(answer.status).describedAs("$method $path").isEqualTo(403)
            assertThat(answer.body["code"].asText()).describedAs("$method $path").isEqualTo("mcp_route_forbidden")
        }
    }

    @Test
    fun `멤버가 아닌 프로젝트는 404 다`() {
        val world = newWorld()
        val outsider = newWorld()
        val token = mint(outsider.userId, scope = "macro:read macro:write")

        assertThat(call(token, HttpMethod.GET, "${world.base}/developer-macros").status).isEqualTo(404)
        assertThat(call(token, HttpMethod.GET, "${world.base}/game-builds").status).isEqualTo(404)
    }

    @Test
    fun `서명·aud·iss·만료가 틀린 token 은 401 이다`() {
        val world = newWorld()
        val path = "${world.base}/developer-macros"
        val stranger = RSAKeyGenerator(2048).keyID(rsaKey.keyID).generate()

        val cases = mapOf(
            "JWKS 에 없는 키로 서명(같은 kid)" to mint(world.userId, key = stranger),
            "aud 가 다르다" to mint(world.userId, audience = "artel-home"),
            "iss 가 다르다" to mint(world.userId, issuer = "https://elsewhere.test"),
            "만료됐다" to mint(world.userId, expiresAt = Instant.now().minus(Duration.ofMinutes(5))),
            "sub 가 사용자 id 가 아니다" to mint("github:42"),
            "typ 이 JWT 다" to mint(world.userId, type = JOSEObjectType.JWT),
            "typ 이 없다" to mint(world.userId, type = null),
        )
        for ((reason, token) in cases) {
            assertThat(call(token, HttpMethod.GET, path).status).describedAs(reason).isEqualTo(401)
        }
        assertThat(call(mint(world.userId), HttpMethod.GET, path).status).describedAs("대조군").isEqualTo(200)
    }

    @Test
    fun `typ 이 application at+jwt 인 token 도 받는다`() {
        val world = newWorld()

        val token = mint(world.userId, type = JOSEObjectType("application/at+jwt"))

        assertThat(call(token, HttpMethod.GET, "${world.base}/developer-macros").status).isEqualTo(200)
    }

    @Test
    fun `ES256 로 서명한 token 도 받는다`() {
        val world = newWorld()

        assertThat(call(mint(world.userId, key = ecKey), HttpMethod.GET, "${world.base}/developer-macros").status)
            .isEqualTo(200)
    }

    @Test
    fun `모르는 kid 를 만나면 JWKS 를 한 번 다시 받는다`() {
        val world = newWorld()
        val path = "${world.base}/developer-macros"
        assertThat(call(mint(world.userId), HttpMethod.GET, path).status).isEqualTo(200)
        val fetchesBefore = jwksFetches.get()

        assertThat(call(mint(world.userId), HttpMethod.GET, path).status).isEqualTo(200)
        assertThat(jwksFetches.get()).describedAs("아는 kid 는 캐시로 푼다").isEqualTo(fetchesBefore)

        val rotated = RSAKeyGenerator(2048).keyID("mcp-rsa-2").generate()
        jwks.set(JWKSet(listOf<JWK>(rsaKey.toPublicJWK(), ecKey.toPublicJWK(), rotated.toPublicJWK())))
        try {
            assertThat(call(mint(world.userId, key = rotated), HttpMethod.GET, path).status).isEqualTo(200)
            assertThat(jwksFetches.get()).isEqualTo(fetchesBefore + 1)
        } finally {
            jwks.set(JWKSet(listOf<JWK>(rsaKey.toPublicJWK(), ecKey.toPublicJWK())))
        }
    }

    @Test
    fun `MCP 를 켜도 세션 쿠키·Authorization 세션 JWT·CLI 토큰은 그대로 통하고 경로 제한을 받지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val cliToken = cliTokenService.issue(world.userId, "노트북", 30).token

        val bySession = client().get().uri("/api/auth/me").cookie(COOKIE, world.session)
            .exchangeToMono { Mono.just(it.statusCode().value()) }.block()
        val byHeader = call(world.session, HttpMethod.GET, "/api/auth/me")
        val byCli = call(cliToken, HttpMethod.GET, "/api/auth/me")

        assertThat(bySession).isEqualTo(200)
        assertThat(byHeader.status).isEqualTo(200)
        assertThat(byCli.status).isEqualTo(200)
        assertThat(byCli.body["id"].asText()).isEqualTo(world.userId.toString())
    }

    // ---------- 픽스처 ----------

    private data class World(val userId: Long, val session: String, val base: String, val buildId: Long)

    private fun newWorld(): World = runBlocking {
        val now = Instant.now()
        val seed = UUID.randomUUID().toString().take(8)
        val user: AuthenticatedUser = oauthUsers.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "mcp-$seed",
                login = "mcp-$seed",
                displayName = "mcp-$seed",
                avatarUrl = null,
                email = "mcp-$seed@example.com",
            )
        )
        val project = projects.save(ProjectEntity(name = "mcp-$seed", genre = "ACTION", createdAt = now, updatedAt = now))
        members.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = user.userId.toLong(), role = "OWNER", createdAt = now)
        )
        val build = gameBuilds.save(
            GameBuildEntity(projectId = project.id, version = "1.0.$seed", createdAt = now, updatedAt = now)
        )
        World(user.userId.toLong(), jwtService.issue(user), "/api/projects/${project.id}", build.id!!)
    }

    private fun mint(
        subject: Any,
        scope: String = "macro:read macro:write",
        audience: String = "artel-orchestration",
        issuer: String = MCP_ISSUER,
        expiresAt: Instant = Instant.now().plus(Duration.ofMinutes(15)),
        key: JWK = rsaKey,
        type: JOSEObjectType? = AT_JWT,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(subject.toString())
            .claim("scope", scope)
            .claim("grant_id", "grant-1")
            .claim("client_id", "client-1")
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(Instant.now().minus(Duration.ofMinutes(1))))
            .expirationTime(Date.from(expiresAt))
            .build()
        val (algorithm, signer) = when (key) {
            is RSAKey -> JWSAlgorithm.RS256 to RSASSASigner(key)
            is ECKey -> JWSAlgorithm.ES256 to ECDSASigner(key)
            else -> error("지원하지 않는 키")
        }
        val header = JWSHeader.Builder(algorithm).keyID(key.keyID).type(type).build()
        return SignedJWT(header, claims).apply { sign(signer) }.serialize()
    }

    private data class Answer(val status: Int, val body: JsonNode)

    private fun call(bearer: String, method: HttpMethod, path: String, body: String? = null): Answer {
        val request = client().method(method).uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
        val withBody = if (body == null) request else request.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
        return withBody.exchangeToMono { response ->
            response.bodyToMono(String::class.java).defaultIfEmpty("null")
                // 문법 글은 JSON 이 아니다. 본문을 보는 단정은 JSON 응답에만 있다.
                .map { text ->
                    val body = runCatching { objectMapper.readTree(text) }.getOrElse { objectMapper.nodeFactory.textNode(text) }
                    Answer(response.statusCode().value(), body)
                }
        }.block()!!
    }

    private fun client() = WebClient.create("http://localhost:$port")
}

private const val COOKIE = "artel_access_token"
