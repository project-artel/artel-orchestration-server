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
import kr.artel.orchestration.settings.config.AgentServerProperties
import kr.artel.orchestration.settings.service.AgentCacheInvalidator
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
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 관리 화면의 Bedrock API key(저장, 지우기, 확인, 내부 포트로 건네기)와 agent-server 캐시 비우기.
 *
 * Bedrock runtime 과 agent-server 는 이 클래스가 띄운 가짜 서버다. 가짜 Bedrock 은 Haiku 4.5 와 Haiku 5.5 에는
 * 200 을, GPT-6.1 Sol 에는 실제 Bedrock 처럼 "not available for this account" 400 을 낸다. Haiku 4.5 는 켜진 채로
 * 시드되므로 모든 확인에 들어간다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
class BedrockKeyAndAgentCacheIntegrationTest {

    companion object {
        private const val BEDROCK_KEY = "ABSKQmVkcm9ja0FQSUtleS1hZG1pbi1wYWdlLTAxMjM0NTY3ODk=wxyz"
        private const val OPENROUTER_KEY = "sk-or-v1-admin-page-key-0123456789abcdef"
        private const val HAIKU_4_5 = "bedrock/us.anthropic.claude-haiku-4-5-20251001-v1:0"
        private const val HAIKU = "bedrock/us.anthropic.claude-haiku-5-5"
        private const val SOL = "bedrock/us.openai.gpt-6.1-sol"
        private const val SOL_REFUSAL =
            "us.openai.gpt-6.1-sol is not available for this account. You can explore other available models on Amazon Bedrock."

        /** 가짜 Bedrock 이 받은 호출의 (model id, Authorization 헤더). */
        private val bedrockCalls = ConcurrentLinkedQueue<Pair<String, String?>>()

        /** 가짜 agent-server 가 받은 `POST /internal/llm-cache/invalidate` 수. */
        private val invalidations = AtomicInteger()

        /** 가짜 agent-server 가 낼 상태 코드. 204 가 정상, 503 은 agent-server 가 고장 난 경우. */
        private val agentStatus = AtomicInteger(204)

        private val fakeBedrock: DisposableServer by lazy {
            HttpServer.create().port(0).route { routes ->
                routes.post("/model/{modelId}/converse") { request, response ->
                    // WebClient 는 Haiku 4.5 id 의 `:` 를 `%3A` 로 보낸다. boto3 도 같고, Bedrock 은 그 경로를 받는다.
                    val modelId = java.net.URLDecoder.decode(request.param("modelId")!!, Charsets.UTF_8)
                    bedrockCalls.add(modelId to request.requestHeaders().get("Authorization"))
                    request.receive().then(
                        if (modelId == "us.openai.gpt-6.1-sol") {
                            response.status(400).header("Content-Type", "application/json")
                                .sendString(Mono.just("""{"message":"$SOL_REFUSAL"}""")).then()
                        } else {
                            response.header("Content-Type", "application/json")
                                .sendString(Mono.just("""{"output":{"message":{"role":"assistant","content":[{"text":"pong"}]}}}""")).then()
                        }
                    )
                }
            }.bindNow()
        }

        private val fakeAgentServer: DisposableServer by lazy {
            HttpServer.create().port(0).route { routes ->
                routes.post("/internal/llm-cache/invalidate") { _, response ->
                    invalidations.incrementAndGet()
                    response.status(agentStatus.get()).send()
                }
            }.bindNow()
        }

        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            registry.add("artel.bedrock.runtime-base-url") { "http://localhost:${fakeBedrock.port()}" }
            // 배포 설정처럼 base-url 이 `/internal` 까지 포함한다.
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
        bedrockCalls.clear()
        invalidations.set(0)
        agentStatus.set(204)
        val admin = appUserRepository.save(testAppUser("bedrock-admin").copy(platformRole = PlatformRole.ADMIN.name))
        adminId = admin.id!!
        adminSession = jwtService.issue(AuthenticatedUser(adminId.toString(), "password", "bedrock-admin", "bedrock-admin", null))
        val member = appUserRepository.save(testAppUser("bedrock-member"))
        memberId = member.id!!
        memberSession = jwtService.issue(AuthenticatedUser(memberId.toString(), "password", "bedrock-member", "bedrock-member", null))
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
    fun `stores, reports and deletes the bedrock key, and the internal path hands both keys over`(): Unit = runBlocking {
        val empty = call(HttpMethod.GET, "/api/admin/settings/bedrock")
        assertThat(empty["configured"].asBoolean()).isFalse()
        assertThat(empty["maskedKey"].isNull).isTrue()
        assertThat(empty["region"].asText()).isEqualTo("us-west-2")
        assertThat(internalSettings()["bedrockApiKey"].isNull).isTrue()
        assertThat(internalSettings()["openrouterApiKey"].isNull).isTrue()

        val stored = call(HttpMethod.PUT, "/api/admin/settings/bedrock", """{"apiKey":"$BEDROCK_KEY"}""")
        assertThat(stored["configured"].asBoolean()).isTrue()
        assertThat(stored["maskedKey"].asText()).isEqualTo("****wxyz")
        assertThat(stored["updatedAt"].isNull).isFalse()
        call(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":"$OPENROUTER_KEY"}""")

        val internal = internalSettings()
        assertThat(internal["bedrockApiKey"].asText()).isEqualTo(BEDROCK_KEY)
        assertThat(internal["openrouterApiKey"].asText()).isEqualTo(OPENROUTER_KEY)
        assertThat(internal.fieldNames().asSequence().toList()).containsExactlyInAnyOrder(
            "openrouterApiKey", "bedrockApiKey", "anthropicApiKey", "typesafeApiKey")

        val storedValue = databaseClient.sql("SELECT setting_value FROM platform_setting WHERE setting_key = 'bedrock.api_key'")
            .map { row, _ -> row.get("setting_value", String::class.java)!! }
            .one().awaitSingle()
        assertThat(storedValue).startsWith("v1:").doesNotContain(BEDROCK_KEY)

        call(HttpMethod.POST, "/api/admin/settings/bedrock/check")
        val cleared = call(HttpMethod.PUT, "/api/admin/settings/bedrock", """{"apiKey":"  "}""")
        assertThat(cleared["configured"].asBoolean()).isFalse()
        assertThat(cleared["lastCheck"].isNull).isTrue()
        assertThat(internalSettings()["bedrockApiKey"].isNull).isTrue()
        val remaining = databaseClient.sql("SELECT COUNT(*) AS n FROM platform_setting WHERE setting_key LIKE 'bedrock.%'")
            .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
            .one().awaitSingle()
        assertThat(remaining).isZero()
    }

    @Test
    fun `check calls converse once for every enabled bedrock row and reports reachable and refused models`() {
        enable(HAIKU, SOL)
        call(HttpMethod.PUT, "/api/admin/settings/bedrock", """{"apiKey":"$BEDROCK_KEY"}""")

        val result = call(HttpMethod.POST, "/api/admin/settings/bedrock/check")

        assertThat(result["keyConfigured"].asBoolean()).isTrue()
        val byId = result["models"].associateBy { it["id"].asText() }
        assertThat(byId.keys).containsExactlyInAnyOrder(HAIKU_4_5, HAIKU, SOL)
        assertThat(byId.getValue(HAIKU_4_5)["reachable"].asBoolean()).isTrue()
        assertThat(byId.getValue(HAIKU)["reachable"].asBoolean()).isTrue()
        assertThat(byId.getValue(HAIKU)["error"].isNull).isTrue()
        assertThat(byId.getValue(SOL)["reachable"].asBoolean()).isFalse()
        assertThat(byId.getValue(SOL)["error"].asText()).isEqualTo(SOL_REFUSAL)
        assertThat(bedrockCalls.map { it.first }).containsExactlyInAnyOrder(
            "us.anthropic.claude-haiku-4-5-20251001-v1:0",
            "us.anthropic.claude-haiku-5-5",
            "us.openai.gpt-6.1-sol"
        )
        assertThat(bedrockCalls.map { it.second }.toSet()).containsExactly("Bearer $BEDROCK_KEY")

        val lastCheck = call(HttpMethod.GET, "/api/admin/settings/bedrock")["lastCheck"]
        assertThat(lastCheck["models"].size()).isEqualTo(3)
    }

    @Test
    fun `check skips disabled bedrock rows`() {
        enable(HAIKU)
        call(HttpMethod.PUT, "/api/admin/settings/bedrock", """{"apiKey":"$BEDROCK_KEY"}""")

        val result = call(HttpMethod.POST, "/api/admin/settings/bedrock/check")

        assertThat(result["models"].map { it["id"].asText() }).containsExactly(HAIKU_4_5, HAIKU)
        assertThat(bedrockCalls.map { it.first })
            .containsExactlyInAnyOrder("us.anthropic.claude-haiku-4-5-20251001-v1:0", "us.anthropic.claude-haiku-5-5")
    }

    @Test
    fun `check without a stored key calls nothing`() {
        enable(HAIKU, SOL)

        val result = call(HttpMethod.POST, "/api/admin/settings/bedrock/check")

        assertThat(result["keyConfigured"].asBoolean()).isFalse()
        assertThat(result["models"].size()).isZero()
        assertThat(bedrockCalls).isEmpty()
    }

    @Test
    fun `a non-admin is refused on every bedrock and cache path`() {
        val requests = listOf(
            Triple(HttpMethod.GET, "/api/admin/settings/bedrock", null),
            Triple(HttpMethod.PUT, "/api/admin/settings/bedrock", """{"apiKey":"$BEDROCK_KEY"}"""),
            Triple(HttpMethod.POST, "/api/admin/settings/bedrock/check", null),
            Triple(HttpMethod.POST, "/api/admin/settings/llm/invalidate-cache", null)
        )
        for ((method, path, json) in requests) {
            assertThat(statusOf(memberSession, method, path, json)).describedAs("$method $path").isEqualTo(403)
        }
        assertThat(invalidations.get()).isZero()
    }

    @Test
    fun `invalidate-cache reports whether the agent-server answered`() {
        val ok = call(HttpMethod.POST, "/api/admin/settings/llm/invalidate-cache")
        assertThat(ok["agentServerInvalidated"].asBoolean()).isTrue()
        assertThat(ok["error"].isNull).isTrue()
        assertThat(invalidations.get()).isEqualTo(1)

        agentStatus.set(503)
        assertThat(statusOf(adminSession, HttpMethod.POST, "/api/admin/settings/llm/invalidate-cache", null)).isEqualTo(200)
        val failed = call(HttpMethod.POST, "/api/admin/settings/llm/invalidate-cache")
        assertThat(failed["agentServerInvalidated"].asBoolean()).isFalse()
        assertThat(failed["error"].asText()).contains("503")
    }

    @Test
    fun `an agent-server that is down is reported, not thrown`(): Unit = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val invalidator = AgentCacheInvalidator(AgentServerProperties("http://localhost:$closedPort", Duration.ofSeconds(3)))

        val result = invalidator.invalidate()

        assertThat(result.agentServerInvalidated).isFalse()
        assertThat(result.error).contains("could not be reached")
    }

    @Test
    fun `saving or deleting either key and toggling a model ask the agent-server to drop its cache`() {
        call(HttpMethod.PUT, "/api/admin/settings/bedrock", """{"apiKey":"$BEDROCK_KEY"}""")
        call(HttpMethod.PUT, "/api/admin/settings/bedrock", """{"apiKey":null}""")
        call(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":"$OPENROUTER_KEY"}""")
        call(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":null}""")
        call(HttpMethod.PATCH, "/api/admin/llm-models", """{"id":"$HAIKU","enabled":true}""")

        assertThat(invalidations.get()).isEqualTo(5)
    }

    @Test
    fun `a failed invalidation never fails the save`() {
        agentStatus.set(503)

        val stored = call(HttpMethod.PUT, "/api/admin/settings/bedrock", """{"apiKey":"$BEDROCK_KEY"}""")
        val toggled = call(HttpMethod.PATCH, "/api/admin/llm-models", """{"id":"$HAIKU","enabled":true}""")

        assertThat(stored["configured"].asBoolean()).isTrue()
        assertThat(toggled["enabled"].asBoolean()).isTrue()
        assertThat(invalidations.get()).isEqualTo(2)
    }

    /** 공개 응답 어디에도, 로그 어디에도 Bedrock key 원문이 없다. 이유는 `LlmKeySettingsIntegrationTest` 의 같은 확인과 같다. */
    @Test
    fun `the bedrock key never appears in a public response or in the log`(output: CapturedOutput) {
        enable(HAIKU, SOL)
        val publicBodies = listOf(
            callRaw(HttpMethod.PUT, "/api/admin/settings/bedrock", """{"apiKey":"$BEDROCK_KEY"}"""),
            callRaw(HttpMethod.GET, "/api/admin/settings/bedrock"),
            callRaw(HttpMethod.POST, "/api/admin/settings/bedrock/check"),
            callRaw(HttpMethod.GET, "/api/admin/settings/bedrock")
        )
        assertThat(internalSettings()["bedrockApiKey"].asText()).isEqualTo(BEDROCK_KEY)

        for (body in publicBodies) {
            assertThat(body).doesNotContain(BEDROCK_KEY)
        }
        assertThat(output.all).doesNotContain(BEDROCK_KEY)
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

    private fun statusOf(session: String, method: HttpMethod, path: String, json: String?): Int =
        request(session, method, path, json).exchangeToMono { Mono.just(it.statusCode().value()) }.block()!!

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
