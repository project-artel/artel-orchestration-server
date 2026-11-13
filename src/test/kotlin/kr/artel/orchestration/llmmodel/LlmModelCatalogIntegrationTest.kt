package kr.artel.orchestration.llmmodel

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
import reactor.netty.http.server.HttpServer

/** V109 와 V110 이 넣은 카탈로그와 그것을 읽는 세 경로(내부, 선택 목록, 관리). */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LlmModelCatalogIntegrationTest {

    companion object {
        private const val IMPORTED_ID = "gpt/gpt-6-sol"
        private const val DISCOVERY_KEY = "openai-catalog-test-key"

        private val providers by lazy {
            HttpServer.create().port(0).route { routes ->
                routes.get("/v1/models") { request, response ->
                    if (request.requestHeaders()["Authorization"] != "Bearer $DISCOVERY_KEY") {
                        response.status(401).send()
                    } else {
                        response.header("Content-Type", "application/json").sendString(Mono.just(
                            """{"data":[{"id":"gpt-6-sol"},{"id":"gpt-6-luna"},{"id":"gpt-image-1"}]}"""
                        ))
                    }
                }
                routes.get("/api/v1/models/user") { request, response ->
                    if (request.requestHeaders()["Authorization"] != "Bearer router-catalog-test-key") {
                        response.status(401).send()
                    } else {
                        response.header("Content-Type", "application/json").sendString(Mono.just("""
                            {"data":[
                              {"id":"google/vision","name":"Vision","context_length":100000,"top_provider":{"max_completion_tokens":10000},"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"supported_parameters":["tools"]},
                              {"id":"google/text","context_length":100000,"top_provider":{"max_completion_tokens":10000},"architecture":{"input_modalities":["text"],"output_modalities":["text"]},"supported_parameters":["tools"]},
                              {"id":"test/vision","context_length":100000,"top_provider":{"max_completion_tokens":10000},"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"supported_parameters":["tools"]}
                            ]}
                        """.trimIndent()))
                    }
                }
                routes.get("/claude/v1/models") { request, response ->
                    if (request.requestHeaders()["Authorization"] != "Bearer claude-catalog-test-key" ||
                        request.requestHeaders()["anthropic-version"] != "2026-10-21"
                    ) {
                        response.status(401).send()
                    } else {
                        val body = if ("after_id=claude-old" in request.uri()) {
                            """{"has_more":false,"data":[{"id":"claude-test","lifecycle":"active","max_input_tokens":900000,"max_tokens":128000,"capabilities":{"image_input":{"supported":true},"structured_outputs":{"supported":true}}}]}"""
                        } else {
                            """{"has_more":true,"last_id":"claude-old","data":[{"id":"claude-old","lifecycle":"retired"}]}"""
                        }
                        response.header("Content-Type", "application/json").sendString(Mono.just(body))
                    }
                }
            }.bindNow()
        }

        @JvmStatic
        @DynamicPropertySource
        fun discoverySettings(registry: DynamicPropertyRegistry) {
            registry.add("artel.openai.base-url") { "http://localhost:${providers.port()}" }
            registry.add("artel.model-discovery.openai-api-key") { DISCOVERY_KEY }
            registry.add("artel.llm.openrouter-base-url") { "http://localhost:${providers.port()}/api/v1" }
            registry.add("artel.model-discovery.openrouter-api-key") { "router-catalog-test-key" }
            registry.add("artel.anthropic.base-url") { "http://localhost:${providers.port()}/claude" }
            registry.add("artel.model-discovery.claude-api-key") { "claude-catalog-test-key" }
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
    fun seedUsers(): Unit = runBlocking {
        val admin = appUserRepository.save(testAppUser("catalog-admin").copy(platformRole = PlatformRole.ADMIN.name))
        adminId = admin.id!!
        adminSession = jwtService.issue(AuthenticatedUser(adminId.toString(), "password", "catalog-admin", "catalog-admin", null))
        val member = appUserRepository.save(testAppUser("catalog-member"))
        memberId = member.id!!
        memberSession = jwtService.issue(AuthenticatedUser(memberId.toString(), "password", "catalog-member", "catalog-member", null))
    }

    @AfterEach
    fun clean(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM llm_model WHERE id = :id")
            .bind("id", IMPORTED_ID).fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("UPDATE llm_model SET enabled = TRUE, updated_by = NULL")
            .fetch().rowsUpdated().awaitSingle()
        appUserRepository.deleteById(adminId)
        appUserRepository.deleteById(memberId)
    }

    @Test
    fun `platform admin discovers compatible OpenRouter and Claude models from their metadata`() {
        val path = "/api/admin/llm-models/discovery"
        val router = call(adminSession, HttpMethod.GET, "$path?provider=openrouter")
        val claude = call(adminSession, HttpMethod.GET, "$path?provider=claude")
        assertThat(router.map { it["id"].asText() }).containsExactly("google/vision")
        assertThat(claude.map { it["id"].asText() }).containsExactly("claude/claude-test")
    }

    @Test
    fun `platform admin imports a live compatible model without changing an existing catalog entry`() {
        val path = "/api/admin/llm-models"
        val request = """{"provider":"openai","id":"$IMPORTED_ID"}"""
        assertThat(statusOf(memberSession, HttpMethod.GET, "$path/discovery?provider=openai")).isEqualTo(403)
        assertThat(statusOf(memberSession, HttpMethod.POST, "$path/import", request)).isEqualTo(403)
        assertThat(statusOf(adminSession, HttpMethod.POST, "$path/import", """{"provider":"openai","id":"gpt/gpt-image-1"}"""))
            .isEqualTo(400)

        val candidates = call(adminSession, HttpMethod.GET, "$path/discovery?provider=openai")
        assertThat(candidates.map { it["id"].asText() }).containsExactly(IMPORTED_ID)
        val created = call(adminSession, HttpMethod.POST, "$path/import", request)
        assertThat(created["id"].asText()).isEqualTo(IMPORTED_ID)
        assertThat(created["enabled"].asBoolean()).isTrue()
        assertThat(created["route"].asText()).isEqualTo("gpt")
        assertThat(call(adminSession, HttpMethod.GET, "$path/discovery?provider=openai").size()).isZero()

        call(adminSession, HttpMethod.PATCH, path, """{"id":"$IMPORTED_ID","enabled":false}""")
        val duplicate = call(adminSession, HttpMethod.POST, "$path/import", request)
        assertThat(duplicate["enabled"].asBoolean()).isFalse()
        val importedRows = internal().filter { it["id"].asText() == IMPORTED_ID }
        assertThat(importedRows).hasSize(1)
        val imported = importedRows.single()
        assertThat(imported["reasoning"]["efforts"].map { it.asText() })
            .containsExactly("max", "xhigh", "high", "medium", "low")
        assertThat(imported["pricing"].isNull).isTrue()
    }

    @Test
    fun `the migrations leave twenty rows, all enabled, and no bedrock row`() {
        val rows = internal()

        // V183 가 Bedrock 행 셋(순번 10 · 140 · 150)을 지웠다(ARTEL-1439). 남은 행은 순번을 그대로 갖는다.
        assertThat(rows).hasSize(20)
        assertThat(rows.map { it["sortOrder"].asInt() }).isEqualTo((1..23).map { it * 10 } - listOf(10, 140, 150))
        assertThat(rows.map { it["id"].asText() }).noneMatch { it.startsWith("bedrock/") }
        assertThat(rows.filter { !it["enabled"].asBoolean() }).isEmpty()
    }

    @Test
    fun `the four claude rows carry the effort list and their pricing`() {
        val byId = internal().associateBy { it["id"].asText() }
        val expectedPricing = mapOf(
            "claude/claude-opus-5-5" to listOf(4.00, 20.00, 5.00, 0.20),
            "claude/claude-sonnet-5-5" to listOf(2.00, 10.00, 2.50, 0.20),
            "claude/claude-haiku-5-5" to listOf(0.10, 0.50, 0.125, 0.01),
            "claude/claude-fable-5-1" to listOf(10.00, 50.00, 12.50, 0.25)
        )
        val expectedLabels = mapOf(
            "claude/claude-opus-5-5" to "Claude Opus 5.5 (Claude API)",
            "claude/claude-sonnet-5-5" to "Claude Sonnet 5.5 (Claude API)",
            "claude/claude-haiku-5-5" to "Claude Haiku 5.5 (Claude API)",
            "claude/claude-fable-5-1" to "Claude Fable 5.1 (Claude API)"
        )

        for ((id, prices) in expectedPricing) {
            val row = byId.getValue(id)
            assertThat(row["label"].asText()).describedAs(id).isEqualTo(expectedLabels.getValue(id))
            assertThat(row["provider"].asText()).describedAs(id).isEqualTo("anthropic")
            assertThat(row["supportsStrictJson"].asBoolean()).describedAs(id).isTrue()
            assertThat(row["maxInputTokens"].asInt()).describedAs(id).isEqualTo(872_000)
            assertThat(row["inputModalities"].map { it.asText() }).describedAs(id).containsExactly("text", "image", "file")
            assertThat(row["reasoning"]["kind"].asText()).describedAs(id).isEqualTo("effort")
            assertThat(row["reasoning"]["efforts"].map { it.asText() }).describedAs(id)
                .containsExactly("max", "xhigh", "high", "medium", "low")
            assertThat(row["reasoning"]["defaultTokens"].isNull).describedAs(id).isTrue()
            // V110 이 켠다. 공용 Anthropic key 가 없으면 공용 항목에서 빠지는 것은 `/api/qa-models` 가 정한다.
            assertThat(row["enabled"].asBoolean()).describedAs(id).isTrue()
            val pricing = row["pricing"]
            assertThat(
                listOf("inputPerMtok", "outputPerMtok", "cacheWritePerMtok", "cacheReadPerMtok").map { pricing[it].asDouble() }
            ).describedAs(id).isEqualTo(prices)
            assertThat(pricing["asOf"].asText()).describedAs(id).isEqualTo("2026-10-14")
            assertThat(pricing["source"].asText()).describedAs(id).isEqualTo("Anthropic model table (claude-api skill, cached 2026-10-06)")
        }

        val haikuPricing = byId.getValue("claude/claude-haiku-5-5")["pricing"]
        assertThat(haikuPricing["longContextThresholdTokens"].asInt()).isEqualTo(100_000)
        assertThat(haikuPricing["longContextInputPerMtok"].asDouble()).isEqualTo(0.50)
        assertThat(haikuPricing["longContextOutputPerMtok"].asDouble()).isEqualTo(2.50)
        assertThat(haikuPricing["longContextCacheWritePerMtok"].asDouble()).isEqualTo(0.625)
        assertThat(haikuPricing["longContextCacheReadPerMtok"].asDouble()).isEqualTo(0.05)
        assertThat(byId.getValue("claude/claude-opus-5-5")["pricing"].has("longContextThresholdTokens")).isFalse()
    }

    @Test
    fun `the four gpt rows preserve runtime capabilities and are enabled`() {
        val byId = internal().associateBy { it["id"].asText() }

        for (name in listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-luna", "gpt-5.6-terra")) {
            val gpt = byId.getValue("gpt/$name")
            val openRouter = byId.getValue("openai/$name")
            assertThat(gpt["label"].asText()).describedAs(name).isEqualTo(openRouter["label"].asText() + " (OpenAI API)")
            for (field in listOf("provider", "supportsStrictJson", "maxInputTokens", "inputModalities", "reasoning")) {
                assertThat(gpt[field]).describedAs("$name $field").isEqualTo(openRouter[field])
            }
            assertThat(gpt["enabled"].asBoolean()).describedAs(name).isTrue()
        }
    }

    @Test
    fun `the internal endpoint carries the full spec, disabled rows included`() {
        val byId = internal().associateBy { it["id"].asText() }

        assertThat(byId.keys).noneMatch { it.startsWith("bedrock/") }

        val sol = byId.getValue("openai/gpt-6.1-sol")
        assertThat(sol["maxInputTokens"].asInt()).isEqualTo(922_000)
        assertThat(sol["inputModalities"].map { it.asText() }).containsExactly("text", "image", "file")
        assertThat(sol["reasoning"]["efforts"].map { it.asText() }).containsExactly("max", "xhigh", "high", "medium", "low")
        assertThat(sol["reasoning"]["defaultTokens"].isNull).isTrue()

        val qwen = byId.getValue("qwen/qwen3.7-max")
        assertThat(qwen["reasoning"].isNull).isTrue()
        assertThat(qwen["pricing"].isNull).isTrue()
        assertThat(qwen["inputModalities"].map { it.asText() }).containsExactly("text")
    }

    @Test
    fun `qa-models lists enabled rows only, in sort order, and follows a toggle`() {
        val before = platformQaModels()
        // 공용 key 로 부를 수 있는 켜진 행: OpenRouter 12. `claude/` 는 공용 Claude key 가 없어 빠지고 `gpt/` 는 공용 key 가 없다.
        assertThat(before.map { it["id"].asText() }).hasSize(12).doesNotContain("claude/claude-opus-5-5", "gpt/gpt-6-luna")
        assertThat(before.first()["id"].asText()).isEqualTo("openai/gpt-6.1-sol")
        assertThat(before.first()["reasoning"]["kind"].asText()).isEqualTo("effort")
        assertThat(before.first()["reasoning"].has("defaultTokens")).isFalse()

        val gemini = before.first { it["id"].asText() == "google/gemini-3.8-flash" }
        assertThat(gemini["supportsVision"].asBoolean()).isTrue()
        assertThat(gemini["multimodal"].asBoolean()).isTrue()
        assertThat(gemini["supportsStrictJson"].asBoolean()).isTrue()
        assertThat(gemini["inputModalities"].map { it.asText() }).containsExactly("text", "image", "file", "audio", "video")
        assertThat(gemini["reasoning"]["kind"].asText()).isEqualTo("effort")

        call(adminSession, HttpMethod.PATCH, "/api/admin/llm-models", """{"id":"google/gemma-4-31b-it:free","enabled":false}""")

        val after = platformQaModels().map { it["id"].asText() }
        assertThat(after).doesNotContain("google/gemma-4-31b-it:free").hasSize(11)
    }

    @Test
    fun `admin lists every row with its route and records who changed it`() {
        val rows = call(adminSession, HttpMethod.GET, "/api/admin/llm-models")
        assertThat(rows.size()).isEqualTo(20)
        assertThat(rows.map { it["route"].asText() }).doesNotContain("bedrock")
        val sol = rows.first { it["id"].asText() == "openai/gpt-6.1-sol" }
        assertThat(sol["provider"].asText()).isEqualTo("openai")
        assertThat(sol["supportsVision"].asBoolean()).isTrue()
        assertThat(sol["maxInputTokens"].asInt()).isEqualTo(922_000)
        assertThat(sol["updatedAt"].asText()).isNotBlank()
        assertThat(rows.first { it["id"].asText() == "openai/gpt-6-luna" }["route"].asText()).isEqualTo("openrouter")
        // `anthropic/` 는 OpenRouter 경로이고, `claude/` 만 Claude API 경로다.
        assertThat(rows.first { it["id"].asText() == "anthropic/claude-opus-5.5" }["route"].asText()).isEqualTo("openrouter")
        val claude = rows.first { it["id"].asText() == "claude/claude-sonnet-5-5" }
        assertThat(claude["route"].asText()).isEqualTo("claude")
        assertThat(claude["provider"].asText()).isEqualTo("anthropic")
        assertThat(claude["enabled"].asBoolean()).isTrue()
        assertThat(rows.first { it["id"].asText() == "gpt/gpt-6-luna" }["route"].asText()).isEqualTo("gpt")

        val updated = call(adminSession, HttpMethod.PATCH, "/api/admin/llm-models", """{"id":"google/gemma-4-31b-it:free","enabled":false}""")
        assertThat(updated["id"].asText()).isEqualTo("google/gemma-4-31b-it:free")
        assertThat(updated["enabled"].asBoolean()).isFalse()

        val updatedBy = runBlocking {
            databaseClient.sql("SELECT updated_by FROM llm_model WHERE id = 'google/gemma-4-31b-it:free'")
                .map { row, _ -> row.get("updated_by", Long::class.javaObjectType)!! }
                .one().awaitSingle()
        }
        assertThat(updatedBy).isEqualTo(adminId)
    }

    @Test
    fun `a non-admin is refused and an unknown id is 404`() {
        assertThat(statusOf(memberSession, HttpMethod.GET, "/api/admin/llm-models")).isEqualTo(403)
        assertThat(statusOf(memberSession, HttpMethod.PATCH, "/api/admin/llm-models", """{"id":"google/gemma-4-31b-it:free","enabled":false}""")).isEqualTo(403)
        assertThat(qaModels().map { it["id"].asText() }).contains("google/gemma-4-31b-it:free")

        assertThat(statusOf(adminSession, HttpMethod.PATCH, "/api/admin/llm-models", """{"id":"no/such-model","enabled":false}""")).isEqualTo(404)
    }

    @Test
    fun `the internal path does not exist on the public port`() {
        assertThat(statusOf(adminSession, HttpMethod.GET, "/internal/llm-models")).isEqualTo(404)
    }

    private fun internal(): List<JsonNode> = objectMapper.readTree(
        WebClient.create("http://localhost:${internalApiServer.port}").get().uri("/internal/llm-models")
            .retrieve().bodyToMono(ByteArray::class.java).block()
    ).toList()

    private fun qaModels(): List<JsonNode> = call(memberSession, HttpMethod.GET, "/api/qa-models").toList()

    /** 공용 key 항목만. 사용자 key 항목은 `UserLlmKeyIntegrationTest` 가 본다. */
    private fun platformQaModels(): List<JsonNode> = qaModels().filter { it["keySource"].asText() == "platform" }

    private fun call(session: String, method: HttpMethod, path: String, json: String? = null): JsonNode =
        objectMapper.readTree(request(session, method, path, json).retrieve().bodyToMono(ByteArray::class.java).block())

    private fun statusOf(session: String, method: HttpMethod, path: String, json: String? = null): Int =
        request(session, method, path, json).exchangeToMono { reactor.core.publisher.Mono.just(it.statusCode().value()) }.block()!!

    private fun request(session: String, method: HttpMethod, path: String, json: String?): WebClient.RequestHeadersSpec<*> {
        val spec = WebClient.create("http://localhost:$port").method(method).uri(path).cookie(authProperties.cookieName, session)
        return if (json != null) spec.contentType(MediaType.APPLICATION_JSON).bodyValue(json) else spec
    }
}
