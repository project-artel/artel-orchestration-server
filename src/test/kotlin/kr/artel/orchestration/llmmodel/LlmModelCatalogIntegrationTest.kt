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
import org.springframework.web.reactive.function.client.WebClient

/** V107 이 넣은 카탈로그와 그것을 읽는 세 경로(내부, 선택 목록, 관리). */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LlmModelCatalogIntegrationTest {

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
        databaseClient.sql("UPDATE llm_model SET enabled = (id NOT LIKE 'bedrock/%'), updated_by = NULL")
            .fetch().rowsUpdated().awaitSingle()
        appUserRepository.deleteById(adminId)
        appUserRepository.deleteById(memberId)
    }

    @Test
    fun `the migration seeds fifteen rows and leaves only the two bedrock rows disabled`() {
        val rows = internal()

        assertThat(rows).hasSize(15)
        assertThat(rows.map { it["sortOrder"].asInt() }).isEqualTo((1..15).map { it * 10 })
        assertThat(rows.filter { !it["enabled"].asBoolean() }.map { it["id"].asText() })
            .containsExactly("bedrock/us.anthropic.claude-haiku-5-5", "bedrock/us.openai.gpt-6.1-sol")
    }

    @Test
    fun `the internal endpoint carries the full spec, disabled rows included`() {
        val byId = internal().associateBy { it["id"].asText() }

        val haiku = byId.getValue("bedrock/us.anthropic.claude-haiku-5-5")
        assertThat(haiku["label"].asText()).isEqualTo("Claude Haiku 5.5 (AWS Bedrock)")
        assertThat(haiku["provider"].asText()).isEqualTo("anthropic")
        assertThat(haiku["supportsStrictJson"].asBoolean()).isFalse()
        assertThat(haiku["maxInputTokens"].asInt()).isEqualTo(872_000)
        assertThat(haiku["inputModalities"].map { it.asText() }).containsExactly("text", "image")
        assertThat(haiku["reasoning"]["kind"].asText()).isEqualTo("effort")
        assertThat(haiku["reasoning"]["efforts"].map { it.asText() }).containsExactly("max", "xhigh", "high", "medium", "low")
        assertThat(haiku["reasoning"]["minTokens"].isNull).isTrue()
        assertThat(haiku["pricing"]["inputPerMtok"].asDouble()).isEqualTo(0.10)
        assertThat(haiku["pricing"]["cacheWritePerMtok"].asDouble()).isEqualTo(0.125)
        assertThat(haiku["pricing"]["asOf"].asText()).isEqualTo("2026-10-13")
        assertThat(haiku["pricing"]["longContextThresholdTokens"].asInt()).isEqualTo(100_000)
        assertThat(haiku["enabled"].asBoolean()).isFalse()
        assertThat(haiku["sortOrder"].asInt()).isEqualTo(140)

        val sol = byId.getValue("bedrock/us.openai.gpt-6.1-sol")
        assertThat(sol["maxInputTokens"].asInt()).isEqualTo(922_000)
        assertThat(sol["supportsStrictJson"].asBoolean()).isFalse()
        assertThat(sol["inputModalities"].map { it.asText() }).containsExactly("text", "image", "file")
        assertThat(sol["reasoning"]["efforts"].map { it.asText() }).containsExactly("max", "xhigh", "high", "medium", "low")
        assertThat(sol["pricing"].isNull).isTrue()

        val chat = byId.getValue("openai/gpt-chat-latest")
        assertThat(chat["reasoning"].isNull).isTrue()
        assertThat(chat["pricing"].isNull).isTrue()
    }

    @Test
    fun `qa-models lists enabled rows only, in sort order, and follows a toggle`() {
        val before = qaModels()
        assertThat(before.map { it["id"].asText() }).hasSize(13).noneMatch { it.startsWith("bedrock/") }
        assertThat(before.first()["id"].asText()).isEqualTo("openai/gpt-5.6-luna")

        val gemini = before.first { it["id"].asText() == "google/gemini-3.8-flash" }
        assertThat(gemini["supportsVision"].asBoolean()).isTrue()
        assertThat(gemini["multimodal"].asBoolean()).isTrue()
        assertThat(gemini["supportsStrictJson"].asBoolean()).isTrue()
        assertThat(gemini["inputModalities"].map { it.asText() }).containsExactly("text", "image", "file", "audio", "video")
        assertThat(gemini["reasoning"]["kind"].asText()).isEqualTo("effort")

        call(adminSession, HttpMethod.PATCH, "/api/admin/llm-models", """{"id":"google/gemma-4-31b-it:free","enabled":false}""")
        call(adminSession, HttpMethod.PATCH, "/api/admin/llm-models", """{"id":"bedrock/us.openai.gpt-6.1-sol","enabled":true}""")

        val after = qaModels().map { it["id"].asText() }
        assertThat(after).doesNotContain("google/gemma-4-31b-it:free").contains("bedrock/us.openai.gpt-6.1-sol")
        assertThat(after).hasSize(13)
    }

    @Test
    fun `admin lists every row with its route and records who changed it`() {
        val rows = call(adminSession, HttpMethod.GET, "/api/admin/llm-models")
        assertThat(rows.size()).isEqualTo(15)
        val bedrock = rows.first { it["id"].asText() == "bedrock/us.openai.gpt-6.1-sol" }
        assertThat(bedrock["route"].asText()).isEqualTo("bedrock")
        assertThat(bedrock["provider"].asText()).isEqualTo("openai")
        assertThat(bedrock["enabled"].asBoolean()).isFalse()
        assertThat(bedrock["supportsVision"].asBoolean()).isTrue()
        assertThat(bedrock["maxInputTokens"].asInt()).isEqualTo(922_000)
        assertThat(bedrock["updatedAt"].asText()).isNotBlank()
        assertThat(rows.first { it["id"].asText() == "openai/gpt-5.6-luna" }["route"].asText()).isEqualTo("openrouter")

        val updated = call(adminSession, HttpMethod.PATCH, "/api/admin/llm-models", """{"id":"google/gemma-4-31b-it:free","enabled":false}""")
        assertThat(updated["id"].asText()).isEqualTo("google/gemma-4-31b-it:free")
        assertThat(updated["enabled"].asBoolean()).isFalse()

        val updatedBy = runBlocking {
            databaseClient.sql("SELECT updated_by FROM llm_model WHERE id = 'google/gemma-4-31b-it:free'")
                .map { row, _ -> row.get("updated_by", java.lang.Long::class.java)!!.toLong() }
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

    private fun call(session: String, method: HttpMethod, path: String, json: String? = null): JsonNode =
        objectMapper.readTree(request(session, method, path, json).retrieve().bodyToMono(ByteArray::class.java).block())

    private fun statusOf(session: String, method: HttpMethod, path: String, json: String? = null): Int =
        request(session, method, path, json).exchangeToMono { reactor.core.publisher.Mono.just(it.statusCode().value()) }.block()!!

    private fun request(session: String, method: HttpMethod, path: String, json: String?): WebClient.RequestHeadersSpec<*> {
        val spec = WebClient.create("http://localhost:$port").method(method).uri(path).cookie(authProperties.cookieName, session)
        return if (json != null) spec.contentType(MediaType.APPLICATION_JSON).bodyValue(json) else spec
    }
}
