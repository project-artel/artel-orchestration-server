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
import kr.artel.orchestration.settings.service.REQUIRED_OPENROUTER_MODELS
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

/**
 * 관리 화면의 OpenRouter API key: 저장, 출처 순서, 내부 포트로 건네기, OpenRouter 확인, 그리고 key 원문이
 * 어느 공개 응답에도, 어느 로그에도 나가지 않는다는 것.
 *
 * OpenRouter 는 이 클래스가 띄운 가짜 서버다. [VALID_KEY] 만 받아 주고, 필요한 model 중 둘을 뺀 목록을 낸다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
class LlmKeySettingsIntegrationTest {

    companion object {
        private const val VALID_KEY = "sk-or-v1-admin-page-key-0123456789abcdef"
        private const val ENVIRONMENT_KEY = "sk-or-v1-environment-key-fedcba9876543210"
        private const val INVALID_KEY = "sk-or-v1-revoked-key-000000000000wxyz"

        /** 가짜 OpenRouter 가 내지 않는 둘. 확인 결과의 missingModels 가 이 둘이어야 한다. */
        private val UNLISTED_MODELS = setOf("x-ai/grok-4.6", "openai/text-embedding-3-large")

        private val fakeOpenRouter: DisposableServer by lazy {
            val listedModels = REQUIRED_OPENROUTER_MODELS.filterNot { it in UNLISTED_MODELS } + "some/other-model"
            val modelsJson = listedModels.joinToString(",", prefix = """{"data":[""", postfix = "]}") { """{"id":"$it","name":"x"}""" }
            HttpServer.create().port(0).route { routes ->
                routes.get("/api/v1/key") { request, response ->
                    val authorized = request.requestHeaders().get("Authorization") in
                        setOf("Bearer $VALID_KEY", "Bearer $ENVIRONMENT_KEY")
                    if (authorized) {
                        response.header("Content-Type", "application/json").sendString(Mono.just("""{"data":{"label":"sk-or-v1-adm...cdef"}}"""))
                    } else {
                        response.status(401).send()
                    }
                }
                routes.get("/api/v1/models") { _, response ->
                    response.header("Content-Type", "application/json").sendString(Mono.just(modelsJson))
                }
                routes.get("/api/v1/embeddings/models") { _, response -> response.status(404).send() }
            }.bindNow()
        }

        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            registry.add("artel.llm.openrouter-base-url") { "http://localhost:${fakeOpenRouter.port()}/api/v1" }
            registry.add("artel.llm.openrouter-api-key") { ENVIRONMENT_KEY }
            registry.add("artel.secrets.key") { "test-only-secrets-key-that-is-at-least-32-bytes" }
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
    private lateinit var adminSession: String

    @BeforeEach
    fun seedAdmin(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        val admin = appUserRepository.save(testAppUser("llm-admin").copy(platformRole = PlatformRole.ADMIN.name))
        adminId = admin.id!!
        adminSession = jwtService.issue(AuthenticatedUser(adminId.toString(), "password", "llm-admin", "llm-admin", null))
    }

    @AfterEach
    fun clean(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        appUserRepository.deleteById(adminId)
    }

    @Test
    fun `falls back to the environment key, then prefers the admin key, then falls back again when cleared`() {
        val initial = call(HttpMethod.GET, "/api/admin/settings/llm")
        assertThat(initial["source"].asText()).isEqualTo("environment")
        assertThat(initial["maskedKey"].asText()).isEqualTo("****3210")
        assertThat(internalSettings()["apiKey"].asText()).isEqualTo(ENVIRONMENT_KEY)

        val stored = call(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":"$VALID_KEY"}""")
        assertThat(stored["source"].asText()).isEqualTo("admin")
        assertThat(stored["maskedKey"].asText()).isEqualTo("****cdef")
        assertThat(internalSettings()["apiKey"].asText()).isEqualTo(VALID_KEY)
        assertThat(internalSettings()["source"].asText()).isEqualTo("admin")

        val cleared = call(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":null}""")
        assertThat(cleared["source"].asText()).isEqualTo("environment")
        assertThat(cleared["adminKeyStored"].asBoolean()).isFalse()
    }

    @Test
    fun `stores the key encrypted`(): Unit = runBlocking {
        call(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":"$VALID_KEY"}""")

        val storedValue = databaseClient.sql("SELECT setting_value FROM platform_setting WHERE setting_key = 'llm.openrouter.api_key'")
            .map { row, _ -> row.get("setting_value", String::class.java)!! }
            .one().awaitSingle()
        assertThat(storedValue).startsWith("v1:").doesNotContain(VALID_KEY).doesNotContain("0123456789abcdef")
    }

    @Test
    fun `check reports which required models the key reaches`() {
        call(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":"$VALID_KEY"}""")

        val result = call(HttpMethod.POST, "/api/admin/settings/llm/check")

        assertThat(result["keyValid"].asBoolean()).isTrue()
        assertThat(result["error"].isNull).isTrue()
        assertThat(result["missingModels"].map { it.asText() }).containsExactlyInAnyOrderElementsOf(UNLISTED_MODELS)
        assertThat(result["reachableModels"].size()).isEqualTo(REQUIRED_OPENROUTER_MODELS.size - UNLISTED_MODELS.size)
        assertThat(call(HttpMethod.GET, "/api/admin/settings/llm")["lastCheck"]["keyValid"].asBoolean()).isTrue()
    }

    @Test
    fun `check reports a key OpenRouter rejects`() {
        call(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":"$INVALID_KEY"}""")

        val result = call(HttpMethod.POST, "/api/admin/settings/llm/check")

        assertThat(result["keyValid"].asBoolean()).isFalse()
        assertThat(result["error"].asText()).isEqualTo("invalid_key")
        assertThat(result["missingModels"].size()).isEqualTo(REQUIRED_OPENROUTER_MODELS.size)
    }

    @Test
    fun `the internal path does not exist on the public port`() {
        val status = client(port).get().uri("/internal/settings/llm")
            .exchangeToMono { Mono.just(it.statusCode().value()) }.block()
        assertThat(status).isEqualTo(404)
    }

    /**
     * 공개 API 의 응답 본문 어디에도, 그리고 이 테스트 동안 남은 로그 어디에도 key 원문이 없다.
     *
     * `logging.level.org.springframework.web: DEBUG` 가 켜져 있어 Spring 은 디코드한 요청 본문과 내보내는
     * 응답 객체를 `toString` 으로 남긴다. 그래서 이 확인은 형식이 아니라 실제로 깨질 수 있는 확인이다.
     */
    @Test
    fun `the key never appears in a public response or in the log`(output: CapturedOutput) {
        val publicBodies = listOf(
            callRaw(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":"$VALID_KEY"}"""),
            callRaw(HttpMethod.GET, "/api/admin/settings/llm"),
            callRaw(HttpMethod.POST, "/api/admin/settings/llm/check"),
            callRaw(HttpMethod.GET, "/api/admin/settings/llm")
        )
        // 내부 경로는 key 를 건네는 것이 일이다. 그 응답이 로그에도 남지 않는지만 본다.
        assertThat(internalSettings()["apiKey"].asText()).isEqualTo(VALID_KEY)

        for (body in publicBodies) {
            assertThat(body).doesNotContain(VALID_KEY).doesNotContain(ENVIRONMENT_KEY)
        }
        assertThat(output.all).doesNotContain(VALID_KEY).doesNotContain(ENVIRONMENT_KEY)
    }

    private fun internalSettings(): JsonNode = objectMapper.readTree(
        client(internalApiServer.port).get().uri("/internal/settings/llm")
            .retrieve().bodyToMono(ByteArray::class.java).block()
    )

    private fun call(method: HttpMethod, path: String, json: String? = null): JsonNode =
        objectMapper.readTree(callRaw(method, path, json))

    private fun callRaw(method: HttpMethod, path: String, json: String? = null): String {
        val request = client(port).method(method).uri(path).cookie(authProperties.cookieName, adminSession)
        // 본문을 바이트로 보내고 받는다. 문자열로 다루면 이 테스트의 WebClient 가 DEBUG 로그에 본문을 그대로
        // 남겨, 서버가 아니라 테스트가 key 를 로그에 쓴 것으로 아래 로그 확인이 깨진다.
        val withBody = if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).bodyValue(json.toByteArray(Charsets.UTF_8))
        } else {
            request
        }
        return String(withBody.retrieve().bodyToMono(ByteArray::class.java).block()!!, Charsets.UTF_8)
    }

    private fun client(targetPort: Int) = WebClient.create("http://localhost:$targetPort")
}
