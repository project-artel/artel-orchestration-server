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
import kr.artel.orchestration.settings.config.AgentServerProperties
import kr.artel.orchestration.settings.service.AgentCacheInvalidator
import kr.artel.orchestration.support.testAppUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * agent-server 캐시 비우기. 관리 화면에서 key 를 바꾸거나 model 을 켜고 끄면 agent-server 가 들고 있는 key · 카탈로그
 * 캐시를 비우라고 부른다. agent-server 는 이 클래스가 띄운 가짜 서버다.
 *
 * 예전에는 Bedrock key 확인과 함께 있었다(BedrockKeyAndAgentCacheIntegrationTest). Bedrock 을 걷으며(ARTEL-1439)
 * 캐시 쪽만 남겼다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentCacheIntegrationTest {

    companion object {
        private const val OPENROUTER_KEY = "sk-or-v1-admin-page-key-0123456789abcdef"
        private const val GROK = "x-ai/grok-4.7"

        /** 가짜 agent-server 가 받은 `POST /internal/llm-cache/invalidate` 수. */
        private val invalidations = AtomicInteger()

        /** 가짜 agent-server 가 낼 상태 코드. 204 가 정상, 503 은 agent-server 가 고장 난 경우. */
        private val agentStatus = AtomicInteger(204)

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
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper

    private var adminId: Long = 0
    private var memberId: Long = 0
    private lateinit var adminSession: String
    private lateinit var memberSession: String

    @BeforeEach
    fun seed(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        invalidations.set(0)
        agentStatus.set(204)
        val admin = appUserRepository.save(testAppUser("cache-admin").copy(platformRole = PlatformRole.ADMIN.name))
        adminId = admin.id!!
        adminSession = jwtService.issue(AuthenticatedUser(adminId.toString(), "password", "cache-admin", "cache-admin", null))
        val member = appUserRepository.save(testAppUser("cache-member"))
        memberId = member.id!!
        memberSession = jwtService.issue(AuthenticatedUser(memberId.toString(), "password", "cache-member", "cache-member", null))
    }

    @AfterEach
    fun clean(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("UPDATE llm_model SET enabled = TRUE, updated_by = NULL WHERE id = :id").bind("id", GROK)
            .fetch().rowsUpdated().awaitSingle()
        appUserRepository.deleteById(adminId)
        appUserRepository.deleteById(memberId)
    }

    @Test
    fun `a non-admin is refused on the cache path`() {
        assertThat(statusOf(memberSession, HttpMethod.POST, "/api/admin/settings/llm/invalidate-cache", null)).isEqualTo(403)
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
    fun `saving or deleting the key and toggling a model ask the agent-server to drop its cache`() {
        call(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":"$OPENROUTER_KEY"}""")
        call(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":null}""")
        call(HttpMethod.PATCH, "/api/admin/llm-models", """{"id":"$GROK","enabled":false}""")

        assertThat(invalidations.get()).isEqualTo(3)
    }

    @Test
    fun `a failed invalidation never fails the save`() {
        agentStatus.set(503)

        val stored = call(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":"$OPENROUTER_KEY"}""")
        val toggled = call(HttpMethod.PATCH, "/api/admin/llm-models", """{"id":"$GROK","enabled":false}""")

        assertThat(stored["configured"].asBoolean()).isTrue()
        assertThat(toggled["enabled"].asBoolean()).isFalse()
        assertThat(invalidations.get()).isEqualTo(2)
    }

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
