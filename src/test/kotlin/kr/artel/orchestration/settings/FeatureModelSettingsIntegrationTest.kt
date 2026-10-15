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
import kr.artel.orchestration.settings.service.FeatureModelService
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

/**
 * 기능별 LLM model 설정(`/api/admin/settings/models`, `/internal/settings/models`)과 저작 model 의 우선순위.
 *
 * `artel.agent.model` 은 [ENV_MODEL] 로 둔다. agent-server 는 `POST /internal/llm-cache/invalidate` 를 받는 가짜 서버다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeatureModelSettingsIntegrationTest {

    companion object {
        private const val ENV_MODEL = "env/authoring-model"
        private const val ENABLED = "claude/claude-opus-5-5"
        private const val DISABLED = "claude/claude-haiku-5-5"
        private const val JEV = "typesafe/jev-1.13"

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
            registry.add("artel.agent.model") { ENV_MODEL }
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
    @Autowired private lateinit var featureModelService: FeatureModelService

    private var adminId: Long = 0
    private var memberId: Long = 0
    private lateinit var adminSession: String
    private lateinit var memberSession: String

    @BeforeEach
    fun seed(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        invalidations.set(0)
        val admin = appUserRepository.save(testAppUser("models-admin").copy(platformRole = PlatformRole.ADMIN.name))
        adminId = admin.id!!
        adminSession = jwtService.issue(AuthenticatedUser(adminId.toString(), "password", "models-admin", "models-admin", null))
        val member = appUserRepository.save(testAppUser("models-member"))
        memberId = member.id!!
        memberSession = jwtService.issue(AuthenticatedUser(memberId.toString(), "password", "models-member", "models-member", null))
        setEnabled(ENABLED, true)
        setEnabled(DISABLED, false)
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
    fun `empty settings report the environment model as the fallback and no decision model`() {
        val status = call(HttpMethod.GET, "/api/admin/settings/models")
        assertThat(status["scenarioAuthoringModel"].isNull).isTrue()
        assertThat(status["scenarioAuthoringFallback"].asText()).isEqualTo(ENV_MODEL)
        assertThat(status["decisionModel"].isNull).isTrue()
        assertThat(status["updatedAt"].isNull).isTrue()
        assertThat(internalModels().fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("scenarioAuthoringModel", "decisionModel")
    }

    @Test
    fun `stores both models, the internal path hands them over, and null clears them`(): Unit = runBlocking {
        val stored = call(HttpMethod.PUT, "/api/admin/settings/models", """{"scenarioAuthoringModel":"$ENABLED","decisionModel":"$ENABLED"}""")
        assertThat(stored["scenarioAuthoringModel"].asText()).isEqualTo(ENABLED)
        assertThat(stored["decisionModel"].asText()).isEqualTo(ENABLED)
        assertThat(stored["updatedAt"].isNull).isFalse()
        assertThat(invalidations.get()).isEqualTo(1)
        assertThat(call(HttpMethod.GET, "/api/admin/settings/models")["scenarioAuthoringModel"].asText()).isEqualTo(ENABLED)

        val internal = internalModels()
        assertThat(internal["scenarioAuthoringModel"].asText()).isEqualTo(ENABLED)
        assertThat(internal["decisionModel"].asText()).isEqualTo(ENABLED)

        val cleared = call(HttpMethod.PUT, "/api/admin/settings/models", """{"scenarioAuthoringModel":null,"decisionModel":null}""")
        assertThat(cleared["scenarioAuthoringModel"].isNull).isTrue()
        assertThat(cleared["decisionModel"].isNull).isTrue()
        assertThat(cleared["updatedAt"].isNull).isTrue()
        assertThat(invalidations.get()).isEqualTo(2)
        assertThat(internalModels()["decisionModel"].isNull).isTrue()
        assertThat(settingRows()).isZero()
    }

    @Test
    fun `jev is accepted for the decision model only`() {
        val ok = call(HttpMethod.PUT, "/api/admin/settings/models", """{"scenarioAuthoringModel":null,"decisionModel":"$JEV"}""")
        assertThat(ok["decisionModel"].asText()).isEqualTo(JEV)

        val (status, body) = exchange(adminSession, HttpMethod.PUT, "/api/admin/settings/models", """{"scenarioAuthoringModel":"$JEV","decisionModel":null}""")
        assertThat(status).isEqualTo(400)
        assertThat(objectMapper.readTree(body)["code"].asText()).isEqualTo("llm_model_not_found")
    }

    @Test
    fun `an unknown or disabled model is 400 and nothing is stored`(): Unit = runBlocking {
        val cases = listOf(
            """{"scenarioAuthoringModel":"no/such-model","decisionModel":null}""" to "llm_model_not_found",
            """{"scenarioAuthoringModel":null,"decisionModel":"no/such-model"}""" to "llm_model_not_found",
            """{"scenarioAuthoringModel":"$DISABLED","decisionModel":null}""" to "llm_model_disabled",
            """{"scenarioAuthoringModel":"$ENABLED","decisionModel":"$DISABLED"}""" to "llm_model_disabled"
        )
        for ((json, code) in cases) {
            val (status, body) = exchange(adminSession, HttpMethod.PUT, "/api/admin/settings/models", json)
            assertThat(status).describedAs(json).isEqualTo(400)
            assertThat(objectMapper.readTree(body)["code"].asText()).describedAs(json).isEqualTo(code)
        }
        assertThat(settingRows()).isZero()
        assertThat(invalidations.get()).isZero()
    }

    @Test
    fun `a member gets 403 on both methods`() {
        assertThat(exchange(memberSession, HttpMethod.GET, "/api/admin/settings/models", null).first).isEqualTo(403)
        assertThat(
            exchange(memberSession, HttpMethod.PUT, "/api/admin/settings/models", """{"scenarioAuthoringModel":null,"decisionModel":null}""").first
        ).isEqualTo(403)
    }

    @Test
    fun `the authoring model is the stored one, then the environment one`(): Unit = runBlocking {
        assertThat(featureModelService.resolveAuthoringModel()).isEqualTo(ENV_MODEL)

        call(HttpMethod.PUT, "/api/admin/settings/models", """{"scenarioAuthoringModel":"$ENABLED","decisionModel":null}""")
        assertThat(featureModelService.resolveAuthoringModel()).isEqualTo(ENABLED)

        call(HttpMethod.PUT, "/api/admin/settings/models", """{"scenarioAuthoringModel":null,"decisionModel":null}""")
        assertThat(featureModelService.resolveAuthoringModel()).isEqualTo(ENV_MODEL)
    }

    private fun settingRows(): Long = runBlocking {
        databaseClient.sql("SELECT COUNT(*) AS n FROM platform_setting WHERE setting_key LIKE 'llm.model.%'")
            .map { row, _ -> row.get("n", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
    }

    private fun setEnabled(id: String, enabled: Boolean): Unit = runBlocking {
        databaseClient.sql("UPDATE llm_model SET enabled = :enabled WHERE id = :id").bind("enabled", enabled).bind("id", id)
            .fetch().rowsUpdated().awaitSingle()
    }

    private fun internalModels(): JsonNode = objectMapper.readTree(
        client(internalApiServer.port).get().uri("/internal/settings/models")
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
