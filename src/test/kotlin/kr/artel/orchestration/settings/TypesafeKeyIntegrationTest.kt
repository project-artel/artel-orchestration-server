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
import java.util.concurrent.atomic.AtomicInteger

/** 관리 화면의 TypeSafe API key(저장, 지우기, 암호화, 내부 포트로 건네기). agent-server 는 가짜 서버다. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TypesafeKeyIntegrationTest {

    companion object {
        private const val KEY = "ts-live-admin-page-key-0123456789abcdef-wxyz"

        private val invalidations = AtomicInteger()

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
        invalidations.set(0)
        val admin = appUserRepository.save(testAppUser("typesafe-admin").copy(platformRole = PlatformRole.ADMIN.name))
        adminId = admin.id!!
        adminSession = jwtService.issue(AuthenticatedUser(adminId.toString(), "password", "typesafe-admin", "typesafe-admin", null))
        val member = appUserRepository.save(testAppUser("typesafe-member"))
        memberId = member.id!!
        memberSession = jwtService.issue(AuthenticatedUser(memberId.toString(), "password", "typesafe-member", "typesafe-member", null))
    }

    @AfterEach
    fun clean(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        appUserRepository.deleteById(adminId)
        appUserRepository.deleteById(memberId)
    }

    @Test
    fun `stores, reports and deletes the key, and the internal path hands it over`(): Unit = runBlocking {
        val empty = call(HttpMethod.GET, "/api/admin/settings/typesafe")
        assertThat(empty["configured"].asBoolean()).isFalse()
        assertThat(empty["maskedKey"].isNull).isTrue()
        assertThat(empty.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("configured", "maskedKey", "updatedAt")
        assertThat(internalSettings()["typesafeApiKey"].isNull).isTrue()

        val stored = call(HttpMethod.PUT, "/api/admin/settings/typesafe", """{"apiKey":"  $KEY "}""")
        assertThat(stored["configured"].asBoolean()).isTrue()
        assertThat(stored["maskedKey"].asText()).isEqualTo("****wxyz")
        assertThat(stored["updatedAt"].isNull).isFalse()
        assertThat(invalidations.get()).isEqualTo(1)

        val internal = internalSettings()
        assertThat(internal["typesafeApiKey"].asText()).isEqualTo(KEY)
        assertThat(internal["openrouterApiKey"].isNull).isTrue()

        val storedValue = databaseClient.sql("SELECT setting_value FROM platform_setting WHERE setting_key = 'typesafe.api_key'")
            .map { row, _ -> row.get("setting_value", String::class.java)!! }
            .one().awaitSingle()
        assertThat(storedValue).startsWith("v1:").doesNotContain(KEY)

        val cleared = call(HttpMethod.PUT, "/api/admin/settings/typesafe", """{"apiKey":null}""")
        assertThat(cleared["configured"].asBoolean()).isFalse()
        assertThat(invalidations.get()).isEqualTo(2)
        assertThat(internalSettings()["typesafeApiKey"].isNull).isTrue()
    }

    @Test
    fun `a key with whitespace or too long is 400 invalid_api_key and nothing is stored`(): Unit = runBlocking {
        for (apiKey in listOf("has a space-0123456789", "short", "x".repeat(600))) {
            val (status, body) = exchange(adminSession, HttpMethod.PUT, "/api/admin/settings/typesafe", """{"apiKey":"$apiKey"}""")
            assertThat(status).describedAs(apiKey.take(20)).isEqualTo(400)
            assertThat(objectMapper.readTree(body)["code"].asText()).isEqualTo("invalid_api_key")
        }
        val rows = databaseClient.sql("SELECT COUNT(*) AS n FROM platform_setting")
            .map { row, _ -> row.get("n", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        assertThat(rows).isZero()
        assertThat(invalidations.get()).isZero()
    }

    @Test
    fun `a member gets 403 on both methods`() {
        assertThat(exchange(memberSession, HttpMethod.GET, "/api/admin/settings/typesafe", null).first).isEqualTo(403)
        assertThat(exchange(memberSession, HttpMethod.PUT, "/api/admin/settings/typesafe", """{"apiKey":"$KEY"}""").first).isEqualTo(403)
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
