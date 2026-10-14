package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.support.testAppUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * QA run 과 try 를 시작하는 요청의 `model` 은 `llm_model` 로 먼저 확인한다. 카탈로그에 없거나 꺼진 model 은
 * agent-server 에 닿기 전에 400 이다.
 *
 * 확인이 시나리오와 게임 인스턴스를 읽기 전에 일어나므로, 이 테스트의 id 는 없는 값이어도 된다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QaRequestModelValidationIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var authProperties: AuthProperties
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper

    private var userId: Long = 0
    private lateinit var session: String

    @BeforeEach
    fun seedUser(): Unit = runBlocking {
        val user = appUserRepository.save(testAppUser("qa-model-check"))
        userId = user.id!!
        session = jwtService.issue(AuthenticatedUser(userId.toString(), "password", "qa-model-check", "qa-model-check", null))
    }

    @AfterEach
    fun clean(): Unit = runBlocking {
        databaseClient.sql("UPDATE llm_model SET enabled = (id NOT IN ('bedrock/us.anthropic.claude-haiku-5-5', 'bedrock/us.openai.gpt-6.1-sol')), updated_by = NULL")
            .fetch().rowsUpdated().awaitSingle()
        appUserRepository.deleteById(userId)
    }

    @Test
    fun `a model missing from the catalog is 400 llm_model_unknown`() {
        assertRefused("/api/qa-tries", """{"testScenarioId":"1","gameInstanceId":"1","model":"no/such-model"}""", "llm_model_unknown", "no/such-model")
        assertRefused("/api/qa-runs", """{"testRunId":"1","gameInstanceId":"1","model":"no/such-model"}""", "llm_model_unknown", "no/such-model")
    }

    @Test
    fun `a disabled model is 400 llm_model_disabled`(): Unit = runBlocking {
        databaseClient.sql("UPDATE llm_model SET enabled = FALSE WHERE id = 'google/gemma-4-31b-it:free'")
            .fetch().rowsUpdated().awaitSingle()

        for (model in listOf("google/gemma-4-31b-it:free", "bedrock/us.openai.gpt-6.1-sol")) {
            assertRefused("/api/qa-tries", """{"testScenarioId":"1","gameInstanceId":"1","model":"$model"}""", "llm_model_disabled", model)
            assertRefused("/api/qa-runs", """{"testRunId":"1","gameInstanceId":"1","model":"$model"}""", "llm_model_disabled", model)
        }
    }

    @Test
    fun `an enabled model passes the check and the request goes on`() {
        // 확인을 통과하면 다음 단계(없는 시나리오)에서 멈춘다. model 코드가 아니면 확인을 지난 것이다.
        // Bedrock 행 중 Haiku 4.5 는 켜진 채로 시드된다.
        for (model in listOf("openai/gpt-6-luna", "bedrock/us.anthropic.claude-haiku-4-5-20251001-v1:0")) {
            val (_, body) = post("/api/qa-tries", """{"testScenarioId":"1","gameInstanceId":"1","model":"$model"}""")
            assertThat(body).describedAs(model).doesNotContain("llm_model_unknown").doesNotContain("llm_model_disabled")
        }
    }

    private fun assertRefused(path: String, json: String, code: String, model: String) {
        val (status, body) = post(path, json)
        assertThat(status).describedAs("$path $model").isEqualTo(400)
        val node = objectMapper.readTree(body)
        assertThat(node["code"].asText()).describedAs("$path $model").isEqualTo(code)
        assertThat(node["message"].asText()).contains(model)
    }

    /** (상태 코드, 응답 본문) 을 돌려준다. */
    private fun post(path: String, json: String): Pair<Int, String> =
        WebClient.create("http://localhost:$port").post().uri(path)
            .cookie(authProperties.cookieName, session)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(json.toByteArray(Charsets.UTF_8))
            .exchangeToMono { response ->
                val status = response.statusCode().value()
                response.bodyToMono(ByteArray::class.java).map { status to String(it, Charsets.UTF_8) }
                    .switchIfEmpty(Mono.just(status to ""))
            }.block()!!
}
