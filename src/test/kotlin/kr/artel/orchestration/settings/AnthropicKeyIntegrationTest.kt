package kr.artel.orchestration.settings

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.entity.PlatformRole
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.config.InternalApiServer
import kr.artel.orchestration.support.testAppUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 관리 화면의 Claude API key(저장, 지우기, 확인, 내부 포트로 건네기).
 *
 * Anthropic API 와 agent-server 는 이 클래스가 띄운 가짜 서버다. 가짜 Anthropic API 는 [VALID_KEY] 에만 model 목록을
 * 주고, 그 목록에는 Opus 5.5, Sonnet 5.5, Haiku 5.5 만 있다(Fable 5.1 은 없다). 다른 key 에는 실제 API 처럼
 * `authentication_error` 401 을 낸다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
class AnthropicKeyIntegrationTest {

    companion object {
        private const val VALID_KEY = "sk-ant-api03-admin-page-key-0123456789abcdef-wxyz"
        private const val REVOKED_KEY = "sk-ant-api03-revoked-key-000000000000000000-abcd"
        private const val OPUS = "claude/claude-opus-5-5"
        private const val SONNET = "claude/claude-sonnet-5-5"
        private const val HAIKU = "claude/claude-haiku-5-5"
        private const val FABLE = "claude/claude-fable-5-1"
        private const val INVALID_KEY_MESSAGE = "invalid x-api-key"

        /** 가짜 Anthropic API 가 받은 호출의 (query, x-api-key, anthropic-version). */
        private val anthropicCalls = ConcurrentLinkedQueue<Triple<String?, String?, String?>>()

        /** 가짜 agent-server 가 받은 `POST /internal/llm-cache/invalidate` 수. */
        private val invalidations = AtomicInteger()

        private val fakeAnthropic: DisposableServer by lazy {
            val listed = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-haiku-4-5-20251001")
            val modelsJson = listed.joinToString(",", prefix = """{"data":[""", postfix = """],"has_more":false}""") {
                """{"type":"model","id":"$it","display_name":"x","created_at":"2026-10-01T00:00:00Z"}"""
            }
            HttpServer.create().port(0).route { routes ->
                routes.get("/v1/models") { request, response ->
                    val apiKey = request.requestHeaders().get("x-api-key")
                    anthropicCalls.add(
                        Triple(request.uri().substringAfter('?', ""), apiKey, request.requestHeaders().get("anthropic-version"))
                    )
                    if (apiKey == VALID_KEY) {
                        response.header("Content-Type", "application/json").sendString(Mono.just(modelsJson))
                    } else {
                        response.status(401).header("Content-Type", "application/json").sendString(
                            Mono.just("""{"type":"error","error":{"type":"authentication_error","message":"$INVALID_KEY_MESSAGE"}}""")
                        )
                    }
                }
            }.bindNow()
        }

        private val fakeAgentServer: DisposableServer by lazy {
            HttpServer.create().port(0).route { routes ->
                routes.post("/internal/llm-cache/invalidate") { _, response ->
                    invalidations.incrementAndGet()
                    response.status(204).send()
                }
            }.bindNow()
        }

        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            registry.add("artel.anthropic.base-url") { "http://localhost:${fakeAnthropic.port()}" }
            registry.add("artel.agent.base-url") { "http://localhost:${fakeAgentServer.port()}/internal" }
            registry.add("artel.secrets.key") { "test-only-aes-key-that-is-at-least-32-characters" }
        }
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var authProperties: AuthProperties
    @Autowired private lateinit var internalApiServer: InternalApiServer
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper

    private var adminId: Long = 0
    private var memberId: Long = 0
    private lateinit var adminSession: String
    private lateinit var memberSession: String

    @BeforeEach
    fun seed(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        anthropicCalls.clear()
        invalidations.set(0)
        val admin = appUserRepository.save(testAppUser("anthropic-admin").copy(platformRole = PlatformRole.ADMIN.name))
        adminId = admin.id!!
        adminSession = jwtService.issue(AuthenticatedUser(adminId.toString(), "password", "anthropic-admin", "anthropic-admin", null))
        val member = appUserRepository.save(testAppUser("anthropic-member"))
        memberId = member.id!!
        memberSession = jwtService.issue(AuthenticatedUser(memberId.toString(), "password", "anthropic-member", "anthropic-member", null))
        // V110 이 `claude/` 행을 켠 채로 둔다. 이 클래스의 확인 테스트는 각자 켤 행을 고르므로 모두 끈 데서 시작한다.
        databaseClient.sql("UPDATE llm_model SET enabled = FALSE WHERE id LIKE 'claude/%'").fetch().rowsUpdated().awaitSingle()
    }

    @AfterEach
    fun clean(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("UPDATE llm_model SET enabled = id NOT IN ('bedrock/us.anthropic.claude-haiku-5-5', 'bedrock/us.openai.gpt-6.1-sol'), updated_by = NULL")
            .fetch().rowsUpdated().awaitSingle()
        appUserRepository.deleteById(adminId)
        appUserRepository.deleteById(memberId)
    }

    @Test
    fun `stores, reports and deletes the claude api key, and the internal path hands it over`(): Unit = runBlocking {
        val empty = call(HttpMethod.GET, "/api/admin/settings/anthropic")
        assertThat(empty["configured"].asBoolean()).isFalse()
        assertThat(empty["maskedKey"].isNull).isTrue()
        assertThat(empty["lastCheck"].isNull).isTrue()
        assertThat(empty.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("configured", "maskedKey", "updatedAt", "lastCheck")
        assertThat(internalSettings()["anthropicApiKey"].isNull).isTrue()

        val stored = call(HttpMethod.PUT, "/api/admin/settings/anthropic", """{"apiKey":"  $VALID_KEY "}""")
        assertThat(stored["configured"].asBoolean()).isTrue()
        assertThat(stored["maskedKey"].asText()).isEqualTo("****wxyz")
        assertThat(stored["updatedAt"].isNull).isFalse()

        val internal = internalSettings()
        assertThat(internal["anthropicApiKey"].asText()).isEqualTo(VALID_KEY)
        assertThat(internal["openrouterApiKey"].isNull).isTrue()
        assertThat(internal["bedrockApiKey"].isNull).isTrue()

        val storedValue = databaseClient.sql("SELECT setting_value FROM platform_setting WHERE setting_key = 'anthropic.api_key'")
            .map { row, _ -> row.get("setting_value", String::class.java)!! }
            .one().awaitSingle()
        assertThat(storedValue).startsWith("v1:").doesNotContain(VALID_KEY)

        call(HttpMethod.POST, "/api/admin/settings/anthropic/check")
        assertThat(call(HttpMethod.GET, "/api/admin/settings/anthropic")["lastCheck"]["keyValid"].asBoolean()).isTrue()

        val cleared = call(HttpMethod.PUT, "/api/admin/settings/anthropic", """{"apiKey":null}""")
        assertThat(cleared["configured"].asBoolean()).isFalse()
        assertThat(cleared["lastCheck"].isNull).isTrue()
        assertThat(internalSettings()["anthropicApiKey"].isNull).isTrue()
        val remaining = databaseClient.sql("SELECT COUNT(*) AS n FROM platform_setting WHERE setting_key LIKE 'anthropic.%'")
            .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
            .one().awaitSingle()
        assertThat(remaining).isZero()
    }

    @Test
    fun `saving and deleting the key ask the agent-server to drop its cache`() {
        call(HttpMethod.PUT, "/api/admin/settings/anthropic", """{"apiKey":"$VALID_KEY"}""")
        assertThat(invalidations.get()).isEqualTo(1)

        call(HttpMethod.PUT, "/api/admin/settings/anthropic", """{"apiKey":"  "}""")
        assertThat(invalidations.get()).isEqualTo(2)
    }

    @Test
    fun `a key without the sk-ant- prefix or with whitespace is 400 invalid_api_key and nothing is stored`(): Unit = runBlocking {
        for (apiKey in listOf("sk-or-v1-openrouter-key-0123456789", "sk-ant-has a space-0123456789", "sk-ant-" + "x".repeat(600))) {
            val (status, body) = exchange(adminSession, HttpMethod.PUT, "/api/admin/settings/anthropic", """{"apiKey":"$apiKey"}""")
            assertThat(status).describedAs(apiKey.take(20)).isEqualTo(400)
            assertThat(objectMapper.readTree(body)["code"].asText()).isEqualTo("invalid_api_key")
        }
        val rows = databaseClient.sql("SELECT COUNT(*) AS n FROM platform_setting")
            .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
            .one().awaitSingle()
        assertThat(rows).isZero()
        assertThat(invalidations.get()).isZero()
    }

    @Test
    fun `check lists the models once and reports every enabled claude row`() {
        enable(OPUS, SONNET, FABLE)
        call(HttpMethod.PUT, "/api/admin/settings/anthropic", """{"apiKey":"$VALID_KEY"}""")

        val result = call(HttpMethod.POST, "/api/admin/settings/anthropic/check")

        assertThat(result["keyConfigured"].asBoolean()).isTrue()
        assertThat(result["keyValid"].asBoolean()).isTrue()
        // 꺼진 Haiku 5.5 는 목록에 있어도 결과에 넣지 않는다. 순서는 sort_order 다.
        assertThat(result["models"].map { it["id"].asText() }).containsExactly(OPUS, SONNET, FABLE)
        val byId = result["models"].associateBy { it["id"].asText() }
        assertThat(byId.getValue(OPUS)["reachable"].asBoolean()).isTrue()
        assertThat(byId.getValue(OPUS)["error"].isNull).isTrue()
        assertThat(byId.getValue(SONNET)["reachable"].asBoolean()).isTrue()
        assertThat(byId.getValue(FABLE)["reachable"].asBoolean()).isFalse()
        assertThat(byId.getValue(FABLE)["error"].asText()).contains("claude-fable-5-1")

        assertThat(anthropicCalls).containsExactly(Triple("limit=1000", VALID_KEY, "2023-06-01"))
        assertThat(call(HttpMethod.GET, "/api/admin/settings/anthropic")["lastCheck"]["models"].size()).isEqualTo(3)
    }

    @Test
    fun `check reports a key the anthropic api rejects`() {
        enable(OPUS, HAIKU)
        call(HttpMethod.PUT, "/api/admin/settings/anthropic", """{"apiKey":"$REVOKED_KEY"}""")

        val result = call(HttpMethod.POST, "/api/admin/settings/anthropic/check")

        assertThat(result["keyConfigured"].asBoolean()).isTrue()
        assertThat(result["keyValid"].asBoolean()).isFalse()
        assertThat(result["models"].map { it["id"].asText() }).containsExactly(OPUS, HAIKU)
        assertThat(result["models"].map { it["reachable"].asBoolean() }).containsOnly(false)
        assertThat(result["models"].map { it["error"].asText() }).containsOnly(INVALID_KEY_MESSAGE)
        assertThat(anthropicCalls).hasSize(1)
    }

    @Test
    fun `check with every claude row disabled still validates the key and reports no model`() {
        call(HttpMethod.PUT, "/api/admin/settings/anthropic", """{"apiKey":"$VALID_KEY"}""")

        val result = call(HttpMethod.POST, "/api/admin/settings/anthropic/check")

        assertThat(result["keyValid"].asBoolean()).isTrue()
        assertThat(result["models"].size()).isZero()
    }

    @Test
    fun `check without a stored key calls nothing`() {
        enable(OPUS)

        val result = call(HttpMethod.POST, "/api/admin/settings/anthropic/check")

        assertThat(result["keyConfigured"].asBoolean()).isFalse()
        assertThat(result["keyValid"].asBoolean()).isFalse()
        assertThat(result["models"].size()).isZero()
        assertThat(anthropicCalls).isEmpty()
    }

    @Test
    fun `a non-admin is refused on every claude api key path`() {
        val requests = listOf(
            Triple(HttpMethod.GET, "/api/admin/settings/anthropic", null),
            Triple(HttpMethod.PUT, "/api/admin/settings/anthropic", """{"apiKey":"$VALID_KEY"}"""),
            Triple(HttpMethod.POST, "/api/admin/settings/anthropic/check", null)
        )
        for ((method, path, json) in requests) {
            assertThat(exchange(memberSession, method, path, json).first).describedAs("$method $path").isEqualTo(403)
        }
        assertThat(anthropicCalls).isEmpty()
        assertThat(invalidations.get()).isZero()
    }

    /** 공개 응답 어디에도, 로그 어디에도 Claude API key 원문이 없다. 이유는 `LlmKeySettingsIntegrationTest` 의 같은 확인과 같다. */
    @Test
    fun `the claude api key never appears in a public response or in the log`(output: CapturedOutput) {
        enable(OPUS, FABLE)
        val publicBodies = listOf(
            callRaw(HttpMethod.PUT, "/api/admin/settings/anthropic", """{"apiKey":"$VALID_KEY"}"""),
            callRaw(HttpMethod.GET, "/api/admin/settings/anthropic"),
            callRaw(HttpMethod.POST, "/api/admin/settings/anthropic/check"),
            callRaw(HttpMethod.GET, "/api/admin/settings/anthropic")
        )
        assertThat(internalSettings()["anthropicApiKey"].asText()).isEqualTo(VALID_KEY)

        for (body in publicBodies) {
            assertThat(body).doesNotContain(VALID_KEY)
        }
        assertThat(output.all).doesNotContain(VALID_KEY)
    }

    private fun enable(vararg ids: String): Unit = runBlocking {
        for (id in ids) {
            databaseClient.sql("UPDATE llm_model SET enabled = TRUE WHERE id = :id").bind("id", id)
                .fetch().rowsUpdated().awaitSingle()
        }
    }

    private fun internalSettings(): JsonNode = objectMapper.readTree(
        client(internalApiServer.port).get().uri("/internal/settings/llm")
            .retrieve().bodyToMono(ByteArray::class.java).block()
    )

    private fun call(method: HttpMethod, path: String, json: String? = null): JsonNode =
        objectMapper.readTree(callRaw(method, path, json))

    /** 본문을 바이트로 다루는 이유는 `LlmKeySettingsIntegrationTest.callRaw` 와 같다. */
    private fun callRaw(method: HttpMethod, path: String, json: String? = null): String =
        String(request(adminSession, method, path, json).retrieve().bodyToMono(ByteArray::class.java).block()!!, Charsets.UTF_8)

    private fun exchange(session: String, method: HttpMethod, path: String, json: String?): Pair<Int, String> =
        request(session, method, path, json).exchangeToMono { response ->
            response.bodyToMono(ByteArray::class.java).map { response.statusCode().value() to String(it, Charsets.UTF_8) }
                .switchIfEmpty(Mono.just(response.statusCode().value() to ""))
        }.block()!!

    private fun request(session: String, method: HttpMethod, path: String, json: String?): WebClient.RequestHeadersSpec<*> {
        val spec = client(port).method(method).uri(path).cookie(authProperties.cookieName, session)
        return if (json != null) {
            spec.contentType(MediaType.APPLICATION_JSON).bodyValue(json.toByteArray(Charsets.UTF_8))
        } else {
            spec
        }
    }

    private fun client(targetPort: Int) = WebClient.create("http://localhost:$targetPort")
}
