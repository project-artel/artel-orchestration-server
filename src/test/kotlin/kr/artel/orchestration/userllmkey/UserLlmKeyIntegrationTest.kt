package kr.artel.orchestration.userllmkey

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentPort
import kr.artel.orchestration.qa.service.QaAgentSession
import kr.artel.orchestration.qa.service.QaAgentSessionContext
import kr.artel.orchestration.qa.service.QaSessionLlmKey
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.settings.repository.PlatformSettingRepository
import kr.artel.orchestration.settings.service.ANTHROPIC_API_KEY_SETTING
import kr.artel.orchestration.settings.service.SecretCipher
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.reactivestreams.Publisher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import reactor.netty.http.server.HttpServerRequest
import reactor.netty.http.server.HttpServerResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 사용자 LLM API key(ARTEL-1037): 저장과 확인, 선택 목록의 사용자 key 항목, 실행 요청의 `keySource`, agent-server
 * 세션 개설의 `llm_key`.
 *
 * 세 서비스는 이 클래스가 띄운 가짜 서버 하나다. 경로 앞부분(`/anthropic`, `/openai`, `/openrouter`)으로 서비스를
 * 가르고, 모두 [answerStatus] 로 답한다. agent-server 는 세션 개설 컨텍스트를 받아 두는 [RecordingAgentPort] 다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
class UserLlmKeyIntegrationTest {

    /** 가짜 서비스가 받은 호출 하나. */
    data class ProviderCall(
        val uri: String,
        val authorization: String?,
        val xApiKey: String?,
        val anthropicVersion: String?
    )

    class RecordingAgentPort : QaAgentPort {
        val contexts: MutableList<QaAgentSessionContext> = CopyOnWriteArrayList()

        override suspend fun createSession(
            context: QaAgentSessionContext,
            onMessage: suspend (QaAgentEnvelope) -> Unit,
            onDisconnect: suspend () -> Unit
        ): QaAgentSession {
            contexts += context
            return QaAgentSession("user-key-session-${contexts.size}")
        }

        override suspend fun send(sessionId: String, envelope: QaAgentEnvelope) = Unit

        override suspend fun close(sessionId: String) = Unit
    }

    @TestConfiguration
    class Fakes {
        @Bean @Primary fun recordingAgentPort() = RecordingAgentPort()
    }

    companion object {
        private const val ANTHROPIC_KEY = "sk-ant-api03-user-anthropic-key-0123456789-wxyz"
        private const val OPENAI_KEY = "sk-proj-user-openai-key-0123456789abcdef-abcd"
        private const val OPENROUTER_KEY = "sk-or-v1-user-openrouter-key-0123456789-efgh"

        private val providerCalls = ConcurrentLinkedQueue<ProviderCall>()

        /** 가짜 서비스가 돌려줄 status. */
        @Volatile private var answerStatus = 200

        /** 가짜 서비스가 답하기 전에 기다리는 시간. `artel.openai.check-timeout` 보다 길면 시간 초과다. */
        @Volatile private var answerDelay: Duration = Duration.ZERO

        private val fakeProviders: DisposableServer by lazy {
            HttpServer.create().port(0).route { routes ->
                routes.get("/anthropic/v1/models", ::answer)
                routes.get("/openai/v1/models", ::answer)
                routes.get("/openrouter/api/v1/key", ::answer)
            }.bindNow()
        }

        private fun answer(request: HttpServerRequest, response: HttpServerResponse): Publisher<Void> {
            val headers = request.requestHeaders()
            providerCalls.add(
                ProviderCall(request.uri(), headers.get("Authorization"), headers.get("x-api-key"), headers.get("anthropic-version"))
            )
            val status = answerStatus
            // 헤더까지 늦춘다. 헤더를 먼저 보내면 확인은 status 만 보고 끝나 시간 초과가 나지 않는다.
            return Mono.delay(answerDelay).flatMap {
                response.status(status).header("Content-Type", "application/json")
                    .sendString(Mono.just("""{"data":[]}""")).then()
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            registry.add("artel.anthropic.base-url") { "http://localhost:${fakeProviders.port()}/anthropic" }
            registry.add("artel.openai.base-url") { "http://localhost:${fakeProviders.port()}/openai" }
            registry.add("artel.openai.check-timeout") { "PT1S" }
            registry.add("artel.llm.openrouter-base-url") { "http://localhost:${fakeProviders.port()}/openrouter/api/v1" }
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
    @Autowired private lateinit var agentPort: RecordingAgentPort
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var testRunService: TestRunService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var platformSettingRepository: PlatformSettingRepository
    @Autowired private lateinit var secretCipher: SecretCipher

    private var userId: Long = 0
    private lateinit var session: String

    @BeforeEach
    fun seed(): Unit = runBlocking {
        wipe()
        val user = appUserRepository.save(testAppUser("user-llm-key"))
        userId = user.id!!
        session = jwtService.issue(AuthenticatedUser(userId.toString(), "password", "user-llm-key", "user-llm-key", null))
    }

    @AfterEach
    fun clean(): Unit = runBlocking {
        wipe()
        databaseClient.sql("UPDATE llm_model SET enabled = id NOT IN ('bedrock/us.anthropic.claude-haiku-5-5', 'bedrock/us.openai.gpt-6.1-sol'), updated_by = NULL")
            .fetch().rowsUpdated().awaitSingle()
        appUserRepository.deleteById(userId)
    }

    private suspend fun wipe() {
        providerCalls.clear()
        answerStatus = 200
        answerDelay = Duration.ZERO
        agentPort.contexts.clear()
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        runScenarioRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testRunRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        databaseClient.sql("DELETE FROM user_llm_key").fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
    }

    // ------------------------------------------------------------ 저장과 확인

    @Test
    fun `each service is checked with one read call carrying the key, and the key is stored encrypted`(): Unit = runBlocking {
        val expectedCalls = mapOf(
            "anthropic" to ProviderCall("/anthropic/v1/models", null, ANTHROPIC_KEY, "2023-06-01"),
            "openai" to ProviderCall("/openai/v1/models", "Bearer $OPENAI_KEY", null, null),
            "openrouter" to ProviderCall("/openrouter/api/v1/key", "Bearer $OPENROUTER_KEY", null, null)
        )
        val expectedHints = mapOf("anthropic" to "sk-ant…wxyz", "openai" to "sk-pro…abcd", "openrouter" to "sk-or-…efgh")

        for ((service, apiKey) in keysByService()) {
            providerCalls.clear()
            val (status, body) = exchange(HttpMethod.PUT, "/api/auth/me/llm-keys/$service", """{"apiKey":"  $apiKey "}""")

            assertThat(status).describedAs(service).isEqualTo(200)
            val stored = objectMapper.readTree(body)
            assertThat(stored.fieldNames().asSequence().toList()).containsExactly("service", "keyHint", "verifiedAt")
            assertThat(stored["service"].asText()).isEqualTo(service)
            assertThat(stored["keyHint"].asText()).isEqualTo(expectedHints.getValue(service))
            assertThat(providerCalls).describedAs(service).containsExactly(expectedCalls.getValue(service))

            val (encryptedKey, keyHint) = databaseClient
                .sql("SELECT encrypted_key, key_hint FROM user_llm_key WHERE app_user_id = :userId AND service = :service")
                .bind("userId", userId).bind("service", service)
                .map { row, _ -> row.get("encrypted_key", String::class.java)!! to row.get("key_hint", String::class.java)!! }
                .one().awaitSingle()
            assertThat(encryptedKey).describedAs(service).startsWith("v1:").doesNotContain(apiKey)
            assertThat(keyHint).isEqualTo(expectedHints.getValue(service))
        }
    }

    @Test
    fun `service answers map to stored, rejected, or unavailable`(): Unit = runBlocking {
        val expectations = listOf(
            Triple(200, 200, null),
            Triple(429, 200, null),
            Triple(401, 400, "llm_key_invalid"),
            Triple(403, 400, "llm_key_permission_denied"),
            Triple(404, 503, "llm_key_check_unavailable"),
            Triple(500, 503, "llm_key_check_unavailable")
        )
        for ((providerStatus, expectedStatus, expectedCode) in expectations) {
            databaseClient.sql("DELETE FROM user_llm_key").fetch().rowsUpdated().awaitSingle()
            answerStatus = providerStatus
            for ((service, apiKey) in keysByService()) {
                val (status, body) = exchange(HttpMethod.PUT, "/api/auth/me/llm-keys/$service", """{"apiKey":"$apiKey"}""")
                assertThat(status).describedAs("$service answers $providerStatus").isEqualTo(expectedStatus)
                if (expectedCode != null) {
                    assertThat(objectMapper.readTree(body)["code"].asText()).describedAs("$service $providerStatus").isEqualTo(expectedCode)
                }
            }
            val storedCount = if (expectedCode == null) 3L else 0L
            assertThat(countKeys()).describedAs("after $providerStatus").isEqualTo(storedCount)
        }
    }

    @Test
    fun `a check that times out is 503 llm_key_check_unavailable`() {
        answerDelay = Duration.ofSeconds(3)

        val (status, body) = exchange(HttpMethod.PUT, "/api/auth/me/llm-keys/openai", """{"apiKey":"$OPENAI_KEY"}""")

        assertThat(status).isEqualTo(503)
        assertThat(objectMapper.readTree(body)["code"].asText()).isEqualTo("llm_key_check_unavailable")
        runBlocking { assertThat(countKeys()).isZero() }
    }

    @Test
    fun `an unknown service, a blank key, or a wrong prefix is 400 invalid_request without calling the service`() {
        val requests = listOf(
            "gemini" to OPENAI_KEY,
            "anthropic" to "",
            "anthropic" to OPENROUTER_KEY,
            "openai" to "pk-live-not-an-openai-key-0123456789",
            "openrouter" to ANTHROPIC_KEY,
            "openrouter" to "sk-or-v1-has a space-0123456789abcdef"
        )
        for ((service, apiKey) in requests) {
            val (status, body) = exchange(HttpMethod.PUT, "/api/auth/me/llm-keys/$service", """{"apiKey":"$apiKey"}""")
            assertThat(status).describedAs("$service ${apiKey.take(10)}").isEqualTo(400)
            assertThat(objectMapper.readTree(body)["code"].asText()).isEqualTo("invalid_request")
        }
        val (missingStatus, _) = exchange(HttpMethod.PUT, "/api/auth/me/llm-keys/openai", "{}")
        assertThat(missingStatus).isEqualTo(400)
        assertThat(providerCalls).isEmpty()
    }

    @Test
    fun `get lists only the registered keys, put replaces, and delete is idempotent`(): Unit = runBlocking {
        assertThat(call(HttpMethod.GET, "/api/auth/me/llm-keys").size()).isZero()

        put("openrouter", OPENROUTER_KEY)
        put("anthropic", ANTHROPIC_KEY)
        val listed = call(HttpMethod.GET, "/api/auth/me/llm-keys")
        assertThat(listed.map { it["service"].asText() }).containsExactly("anthropic", "openrouter")
        assertThat(listed.first().fieldNames().asSequence().toList()).containsExactly("service", "keyHint", "verifiedAt")

        val replaced = put("openrouter", "sk-or-v1-second-openrouter-key-0123456789-zzzz")
        assertThat(replaced["keyHint"].asText()).isEqualTo("sk-or-…zzzz")
        val afterReplace = call(HttpMethod.GET, "/api/auth/me/llm-keys").first { it["service"].asText() == "openrouter" }
        assertThat(afterReplace["keyHint"].asText()).isEqualTo("sk-or-…zzzz")
        assertThat(afterReplace["verifiedAt"].asText()).isEqualTo(replaced["verifiedAt"].asText())

        assertThat(exchange(HttpMethod.DELETE, "/api/auth/me/llm-keys/openrouter").first).isEqualTo(204)
        assertThat(exchange(HttpMethod.DELETE, "/api/auth/me/llm-keys/openrouter").first).isEqualTo(204)
        assertThat(exchange(HttpMethod.DELETE, "/api/auth/me/llm-keys/openai").first).isEqualTo(204)
        assertThat(call(HttpMethod.GET, "/api/auth/me/llm-keys").map { it["service"].asText() }).containsExactly("anthropic")
    }

    // ------------------------------------------------------------ /api/qa-models

    @Test
    fun `qa-models adds a user entry for every enabled row with a user key service`() {
        val entries = call(HttpMethod.GET, "/api/qa-models").toList()
        val platform = entries.filter { it["keySource"].asText() == "platform" }
        val user = entries.filter { it["keySource"].asText() == "user" }

        // 공용 항목이 먼저다. `gpt/` 행은 공용 OpenAI key 가 없어, `claude/` 행은 관리 화면의 Claude API key 가 없어
        // 공용 항목이 아니다. OpenRouter 와 Bedrock 행은 그대로다.
        assertThat(entries.indexOfLast { it["keySource"].asText() == "platform" }).isEqualTo(platform.size - 1)
        assertThat(platform.map { it["id"].asText() })
            .noneMatch { it.startsWith("gpt/") || it.startsWith("claude/") }
            .contains("openai/gpt-6-luna", "bedrock/us.anthropic.claude-haiku-4-5-20251001-v1:0")
        assertThat(platform).allSatisfy { entry ->
            assertThat(entry["keyProvider"].isNull).isTrue()
            assertThat(entry["keyRegistered"].isNull).isTrue()
        }

        // Bedrock 은 사용자 key 가 없다. 나머지 켜진 행은 모두 사용자 항목이 있다.
        val providerById = user.associate { it["id"].asText() to it["keyProvider"].asText() }
        assertThat(providerById.keys).noneMatch { it.startsWith("bedrock/") }
        assertThat(providerById).containsEntry("claude/claude-sonnet-5-5", "anthropic")
            .containsEntry("gpt/gpt-6.1-sol", "openai")
            .containsEntry("gpt/gpt-5.6-terra", "openai")
            .containsEntry("openai/gpt-6-luna", "openrouter")
            .containsEntry("anthropic/claude-opus-5.5", "openrouter")
            .containsEntry("claude/claude-opus-5-5", "anthropic")
            .containsEntry("claude/claude-fable-5-1", "anthropic")
        // 공용 항목에 없는 `gpt/` 넷과 `claude/` 넷이 사용자 항목에만 있다.
        assertThat(user.size).isEqualTo(platform.count { !it["id"].asText().startsWith("bedrock/") } + 8)
        assertThat(user).allSatisfy { entry ->
            assertThat(entry["keyRegistered"].asBoolean()).isFalse()
            assertThat(entry["keyHint"].isNull).isTrue()
        }

        val haiku45 = platform.first { it["id"].asText() == "bedrock/us.anthropic.claude-haiku-4-5-20251001-v1:0" }
        assertThat(haiku45["pricing"]["inputPerMtok"].asDouble()).isEqualTo(1.00)
        assertThat(haiku45["pricing"]["outputPerMtok"].asDouble()).isEqualTo(5.00)
        val sonnet = user.first { it["id"].asText() == "claude/claude-sonnet-5-5" }
        assertThat(sonnet["pricing"]["inputPerMtok"].asDouble()).isEqualTo(2.00)
        assertThat(sonnet["pricing"]["outputPerMtok"].asDouble()).isEqualTo(10.00)
        assertThat(entries.first { it["id"].asText() == "qwen/qwen3.7-max" }["pricing"].isNull).isTrue()
    }

    @Test
    fun `qa-models lists the claude rows as platform entries once the admin claude api key is stored`(): Unit = runBlocking {
        storeAdminAnthropicKey()

        val platform = call(HttpMethod.GET, "/api/qa-models").filter { it["keySource"].asText() == "platform" }.map { it["id"].asText() }

        assertThat(platform).contains("claude/claude-opus-5-5", "claude/claude-sonnet-5-5", "claude/claude-haiku-5-5", "claude/claude-fable-5-1")
            .noneMatch { it.startsWith("gpt/") }
    }

    @Test
    fun `qa-models marks the user entries of a registered service with its hint`() {
        put("openrouter", OPENROUTER_KEY)

        val user = call(HttpMethod.GET, "/api/qa-models").filter { it["keySource"].asText() == "user" }

        val openRouterEntries = user.filter { it["keyProvider"].asText() == "openrouter" }
        assertThat(openRouterEntries).isNotEmpty().allSatisfy { entry ->
            assertThat(entry["keyRegistered"].asBoolean()).isTrue()
            assertThat(entry["keyHint"].asText()).isEqualTo("sk-or-…efgh")
        }
        val openAiEntries = user.filter { it["keyProvider"].asText() == "openai" }
        assertThat(openAiEntries).hasSize(4).allSatisfy { entry ->
            assertThat(entry["keyRegistered"].asBoolean()).isFalse()
            assertThat(entry["keyHint"].isNull).isTrue()
        }
    }

    // ------------------------------------------------------------ keySource

    @Test
    fun `keySource is checked against the stored keys before the run is looked up`() {
        put("openai", OPENAI_KEY)
        // id 가 없는 값이라 확인을 지나면 404 다.
        val refusals = listOf(
            """"model":"openai/gpt-6-luna","keySource":"user"""" to "llm_key_missing",
            """"model":"bedrock/us.anthropic.claude-haiku-4-5-20251001-v1:0","keySource":"user"""" to "llm_key_missing",
            """"model":"gpt/gpt-6-luna","keySource":"platform"""" to "llm_key_missing",
            """"model":"gpt/gpt-6-luna"""" to "llm_key_missing",
            """"model":"openai/gpt-6-luna","keySource":"admin"""" to "invalid_request",
            """"keySource":"user"""" to "invalid_request"
        )
        for (path in listOf("/api/qa-runs", "/api/qa-tries")) {
            for ((fields, code) in refusals) {
                val (status, body) = exchange(HttpMethod.POST, path, requestBody(path, "1", "1", fields))
                assertThat(status).describedAs("$path $fields").isEqualTo(400)
                assertThat(objectMapper.readTree(body)["code"].asText()).describedAs("$path $fields").isEqualTo(code)
            }
            val (status, body) = exchange(HttpMethod.POST, path, requestBody(path, "1", "1", """"model":"gpt/gpt-6-luna","keySource":"user""""))
            assertThat(status).describedAs("$path with a stored openai key").isEqualTo(404)
            assertThat(body).doesNotContain("llm_key")
        }
    }

    @Test
    fun `a platform run on a claude row needs the admin claude api key`(): Unit = runBlocking {
        for (path in listOf("/api/qa-runs", "/api/qa-tries")) {
            for (fields in listOf(""""model":"claude/claude-sonnet-5-5","keySource":"platform"""", """"model":"claude/claude-sonnet-5-5"""")) {
                val (status, body) = exchange(HttpMethod.POST, path, requestBody(path, "1", "1", fields))
                assertThat(status).describedAs("$path $fields").isEqualTo(400)
                assertThat(objectMapper.readTree(body)["code"].asText()).isEqualTo("llm_key_missing")
            }
        }

        storeAdminAnthropicKey()

        for (path in listOf("/api/qa-runs", "/api/qa-tries")) {
            val (status, body) = exchange(HttpMethod.POST, path, requestBody(path, "1", "1", """"model":"claude/claude-sonnet-5-5""""))
            // 확인을 지나 없는 id 에서 멈춘다.
            assertThat(status).describedAs("$path with the admin key").isEqualTo(404)
            assertThat(body).doesNotContain("llm_key")
        }
    }

    // ------------------------------------------------------------ 세션 개설

    @Test
    fun `a user key run carries the decrypted key to the agent and records key_source`(output: CapturedOutput): Unit = runBlocking {
        put("openai", OPENAI_KEY)
        val seed = seedRunnableInstance()

        val (status, body) = exchange(
            HttpMethod.POST, "/api/qa-runs",
            requestBody("/api/qa-runs", seed.testRunId, seed.instanceId, """"model":"gpt/gpt-6.1-sol","keySource":"user"""")
        )

        assertThat(status).describedAs(body).isEqualTo(201)
        assertThat(body).doesNotContain(OPENAI_KEY)
        assertThat(agentPort.contexts.single().llmKey).isEqualTo(QaSessionLlmKey(service = "openai", apiKey = OPENAI_KEY))
        val runId = objectMapper.readTree(body)["id"].asLong()
        assertThat(qaRunRepository.findById(runId)!!.keySource).isEqualTo("user")
        assertThat(qaTryRepository.findAll().toList().map { it.keySource }).containsOnly("user")
        assertThat(output.all).doesNotContain(OPENAI_KEY)
    }

    @Test
    fun `a platform run sends no key and records key_source platform`(): Unit = runBlocking {
        put("openrouter", OPENROUTER_KEY)
        val seed = seedRunnableInstance()

        val (status, body) = exchange(
            HttpMethod.POST, "/api/qa-runs",
            requestBody("/api/qa-runs", seed.testRunId, seed.instanceId, """"model":"openai/gpt-6-luna"""")
        )

        assertThat(status).describedAs(body).isEqualTo(201)
        assertThat(agentPort.contexts.single().llmKey).isNull()
        assertThat(qaRunRepository.findById(objectMapper.readTree(body)["id"].asLong())!!.keySource).isEqualTo("platform")
        assertThat(qaTryRepository.findAll().toList().map { it.keySource }).containsOnly("platform")
    }

    @Test
    fun `a single scenario try with a user key carries the key and records key_source`(): Unit = runBlocking {
        put("anthropic", ANTHROPIC_KEY)
        val seed = seedRunnableInstance()

        val (status, body) = exchange(
            HttpMethod.POST, "/api/qa-tries",
            requestBody("/api/qa-tries", seed.scenarioId, seed.instanceId, """"model":"claude/claude-haiku-5-5","keySource":"user"""")
        )

        assertThat(status).describedAs(body).isEqualTo(201)
        assertThat(agentPort.contexts.single().llmKey).isEqualTo(QaSessionLlmKey(service = "anthropic", apiKey = ANTHROPIC_KEY))
        assertThat(qaTryRepository.findById(objectMapper.readTree(body)["id"].asLong())!!.keySource).isEqualTo("user")
    }

    // ------------------------------------------------------------ helpers

    /** 관리 화면의 Claude API key 를 저장한 상태를 만든다. `PUT /api/admin/settings/anthropic` 과 같은 모양이다. */
    private suspend fun storeAdminAnthropicKey() {
        platformSettingRepository.upsert(
            settingKey = ANTHROPIC_API_KEY_SETTING,
            settingValue = secretCipher.encrypt("sk-ant-api03-admin-key-0123456789abcdef-zzzz", ANTHROPIC_API_KEY_SETTING),
            encrypted = true,
            updatedBy = userId,
            updatedAt = Instant.now()
        )
    }

    private fun keysByService() = mapOf("anthropic" to ANTHROPIC_KEY, "openai" to OPENAI_KEY, "openrouter" to OPENROUTER_KEY)

    private data class RunnableSeed(val testRunId: String, val scenarioId: String, val instanceId: String)

    /** 이 사용자가 OWNER 인 프로젝트, SDK 가 붙은 인스턴스, 시나리오 하나짜리 테스트 런. */
    private suspend fun seedRunnableInstance(): RunnableSeed {
        val now = Instant.now()
        val project = projectRepository.save(ProjectEntity(name = "user-key-p", genre = "ACTION", createdAt = now, updatedAt = now))
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = userId, role = ProjectRole.OWNER.name, createdAt = now)
        )
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id, name = "inst", platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now
            )
        )
        sessionManager.register(instance.id!!.toString(), Mockito.mock(WebSocketSession::class.java))
        val testRun = testRunRepository.save(TestRunEntity(projectId = project.id, name = "런"))
        val scenario = testScenarioRepository.save(TestScenarioEntity(projectId = project.id))
        testRunService.setScenarios(testRun.id!!, userId, listOf(scenario.id!!))
        return RunnableSeed(testRun.id.toString(), scenario.id.toString(), instance.id.toString())
    }

    private fun requestBody(path: String, targetId: String, instanceId: String, fields: String): String {
        val target = if (path == "/api/qa-runs") "testRunId" else "testScenarioId"
        return """{"$target":"$targetId","gameInstanceId":"$instanceId",$fields}"""
    }

    private suspend fun countKeys(): Long =
        databaseClient.sql("SELECT COUNT(*) AS n FROM user_llm_key")
            .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
            .one().awaitSingle()

    private fun put(service: String, apiKey: String): JsonNode =
        call(HttpMethod.PUT, "/api/auth/me/llm-keys/$service", """{"apiKey":"$apiKey"}""")

    private fun call(method: HttpMethod, path: String, json: String? = null): JsonNode {
        val (status, body) = exchange(method, path, json)
        assertThat(status).describedAs("$method $path: $body").isEqualTo(200)
        return objectMapper.readTree(body)
    }

    /** (상태 코드, 응답 본문) 을 돌려준다. */
    private fun exchange(method: HttpMethod, path: String, json: String? = null): Pair<Int, String> {
        val spec = WebClient.create("http://localhost:$port").method(method).uri(path).cookie(authProperties.cookieName, session)
        val request = if (json != null) spec.contentType(MediaType.APPLICATION_JSON).bodyValue(json.toByteArray(Charsets.UTF_8)) else spec
        return request.exchangeToMono { response ->
            response.bodyToMono(ByteArray::class.java).map { response.statusCode().value() to String(it, Charsets.UTF_8) }
                .switchIfEmpty(Mono.just(response.statusCode().value() to ""))
        }.block()!!
    }
}
