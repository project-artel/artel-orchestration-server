package kr.artel.orchestration.testrun

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.settings.service.SecretCipher
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.userllmkey.entity.LlmKeyProvider
import kr.artel.orchestration.userllmkey.entity.UserLlmKeyEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

/**
 * 시나리오 생성 챗봇에서 고른 model 과 key 로 저작 세션이 돈다(ARTEL-1427).
 *
 * QA 실행과 같은 규칙이다 — 고른 model 은 `keySource` 의 key 로 열고, 그 key 가 없으면 공용 key 로 넘어가지 않고 400 이다.
 * 기본 model 은 없다 — 고르지 않은 말은 400 `model_required` 다.
 * 목 Agent 는 세션 개설 본문과 WS 프레임을 그대로 모아 두고, 턴마다 빈 결과로 답해 다음 말을 받을 수 있게 한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunChatModelChoiceIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var oauthUserService: OAuthUserService

    @Autowired
    private lateinit var runRepository: TestRunRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var projectMemberRepository: ProjectMemberRepository

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var secretCipher: SecretCipher

    companion object {
        private lateinit var mockAgent: DisposableServer

        private const val RESULT_JSON = """{"type":"result","message":"알겠습니다","scenarios":[]}"""
        private const val USER_ANTHROPIC_KEY = "sk-ant-model-choice-0000wxyz"
        private const val CLAUDE = "claude/claude-sonnet-5-5"
        private const val LUNA = "openai/gpt-6-luna"

        /** 목 Agent 가 받은 `POST /sessions` 본문. */
        private val openBodies = CopyOnWriteArrayList<String>()

        /** 목 Agent 가 WS 로 받은 프레임. */
        private val turnFrames = CopyOnWriteArrayList<String>()

        private val seq = AtomicLong(9100)

        @JvmStatic
        @DynamicPropertySource
        fun registerAgentUrls(registry: DynamicPropertyRegistry) {
            mockAgent = HttpServer.create().port(0).route { routes ->
                routes.post("/sessions") { request, response ->
                    request.receive().aggregate().asString().flatMap { body ->
                        openBodies.add(body)
                        response.header("Content-Type", "application/json")
                            .sendString(Mono.just("""{"session_id":"model-choice-sid-${openBodies.size}"}"""))
                            .then()
                    }
                }
                // 연결하면 첫 턴의 결과를, 이후 턴마다 결과를 하나씩 보낸다.
                routes.ws("/sessions/{id}") { inbound, outbound ->
                    outbound.sendString(
                        Mono.just(RESULT_JSON).concatWith(
                            inbound.receive().asString().flatMapIterable { frame ->
                                turnFrames.add(frame)
                                if (frame.contains("\"type\":\"turn\"")) listOf(RESULT_JSON) else emptyList()
                            }
                        )
                    ).then()
                }
            }.bindNow()
            registry.add("artel.agent.base-url") { "http://localhost:${mockAgent.port()}" }
            registry.add("artel.agent.ws-base-url") { "ws://localhost:${mockAgent.port()}" }
            // 사용자 key 를 암호문으로 넣고 꺼내려면 있어야 한다(UserLlmKeyIntegrationTest 와 같은 값).
            registry.add("artel.secrets.key") { "test-only-aes-key-that-is-at-least-32-characters" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            mockAgent.disposeNow()
        }
    }

    private fun webClient() = WebClient.create("http://localhost:$port")

    private suspend fun issueUser(): Pair<Long, String> {
        val id = seq.incrementAndGet()
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "model-choice-$id",
                login = "model-choice-$id",
                displayName = "Chooser",
                avatarUrl = null,
                email = null,
            )
        )
        return user.userId.toLong() to jwtService.issue(user)
    }

    private suspend fun memberRun(appUserId: Long): Pair<Long, Long> {
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "model-choice-project", genre = "RPG", createdAt = now, updatedAt = now)
        )
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = appUserId, role = "OWNER", createdAt = now)
        )
        val runId = runRepository.save(TestRunEntity(projectId = project.id, name = "런")).id!!
        return project.id to runId
    }

    /** 사용자 key 를 저장한다. 확인 호출 없이 암호문을 직접 넣는다 — 여기서 보려는 것은 저작이 그 key 를 싣는가다. */
    private suspend fun storeAnthropicKey(appUserId: Long) {
        val now = Instant.now()
        val encrypted = secretCipher.encrypt(
            USER_ANTHROPIC_KEY,
            UserLlmKeyEntity.settingKey(appUserId, LlmKeyProvider.ANTHROPIC),
        )
        databaseClient.sql(
            """
            INSERT INTO user_llm_key (app_user_id, service, encrypted_key, key_hint, verified_at, updated_at)
            VALUES (:userId, 'anthropic', :encrypted, 'sk-ant…wxyz', :now, :now)
            """
        ).bind("userId", appUserId).bind("encrypted", encrypted).bind("now", now).await()
    }

    private fun send(projectId: Long, runId: Long, token: String, body: String): Pair<Int, String> {
        val response = webClient().post()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue(body)
            .exchangeToMono { it.bodyToMono(String::class.java).defaultIfEmpty("").map { text -> it.statusCode().value() to text } }
            .block(Duration.ofSeconds(10))!!
        return response
    }

    private fun json(text: String): JsonNode = objectMapper.readTree(text)

    private fun awaitSize(list: List<*>, size: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (list.size < size && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    @Test
    fun `고른 model 과 사용자 key 로 세션을 열고, 같은 model 이면 다음 턴에 다시 싣지 않는다`(): Unit = runBlocking {
        val (appUserId, token) = issueUser()
        val (projectId, runId) = memberRun(appUserId)
        storeAnthropicKey(appUserId)
        val opened = openBodies.size
        val turns = turnFrames.size

        val (status, _) = send(projectId, runId, token, """{"message":"시나리오 만들어줘 run$runId","model":"$CLAUDE","keySource":"user"}""")
        assertThat(status).isEqualTo(200)
        awaitSize(openBodies, opened + 1)

        val open = json(openBodies[opened])
        assertThat(open["model"].asText()).isEqualTo(CLAUDE)
        assertThat(open["llm_key"]["service"].asText()).isEqualTo("anthropic")
        assertThat(open["llm_key"]["api_key"].asText()).isEqualTo(USER_ANTHROPIC_KEY)

        // 첫 턴의 결과가 와서 턴이 끝난 뒤에 다음 말을 보낸다.
        Thread.sleep(1500)
        assertThat(send(projectId, runId, token, """{"message":"하나 더","model":"$CLAUDE","keySource":"user"}""").first).isEqualTo(200)
        awaitSize(turnFrames, turns + 1)

        val turn = json(turnFrames.last { it.contains("\"type\":\"turn\"") })
        assertThat(turn["model"].isNull).isTrue()
        assertThat(turn.has("llm_key")).isFalse()
        assertThat(openBodies).hasSize(opened + 1)
    }

    @Test
    fun `대화 중에 사용자 key 의 model 을 고르면 그 턴에 model 과 key 를 함께 싣는다`(): Unit = runBlocking {
        val (appUserId, token) = issueUser()
        val (projectId, runId) = memberRun(appUserId)
        storeAnthropicKey(appUserId)
        val opened = openBodies.size

        assertThat(send(projectId, runId, token, """{"message":"공용 key 로 시작 run$runId","model":"$LUNA","keySource":"platform"}""").first).isEqualTo(200)
        awaitSize(openBodies, opened + 1)
        assertThat(json(openBodies[opened])["model"].asText()).isEqualTo(LUNA)
        assertThat(json(openBodies[opened]).has("llm_key")).isFalse()

        Thread.sleep(1500)
        val turns = turnFrames.size
        assertThat(send(projectId, runId, token, """{"message":"이제 내 키로","model":"$CLAUDE","keySource":"user"}""").first).isEqualTo(200)
        awaitSize(turnFrames, turns + 1)

        val turn = json(turnFrames.last { it.contains("\"type\":\"turn\"") })
        assertThat(turn["model"].asText()).isEqualTo(CLAUDE)
        assertThat(turn["llm_key"]["api_key"].asText()).isEqualTo(USER_ANTHROPIC_KEY)
        // 세션은 그대로다 — 다시 열면 대화 기억이 사라진다.
        assertThat(openBodies).hasSize(opened + 1)
    }

    @Test
    fun `고른 key 가 없으면 공용 key 로 넘어가지 않고 400 이며 세션을 열지 않는다`(): Unit = runBlocking {
        val (appUserId, token) = issueUser()
        val (projectId, runId) = memberRun(appUserId)
        val opened = openBodies.size

        val (status, body) = send(projectId, runId, token, """{"message":"내 키로 run$runId","model":"$CLAUDE","keySource":"user"}""")

        assertThat(status).isEqualTo(400)
        assertThat(json(body)["code"].asText()).isEqualTo("llm_key_missing")
        Thread.sleep(800)
        assertThat(openBodies).hasSize(opened)
    }

    @Test
    fun `모델을 고르지 않은 말은 400 model_required 이고 세션을 열지 않는다`(): Unit = runBlocking {
        val (appUserId, token) = issueUser()
        val (projectId, runId) = memberRun(appUserId)
        val opened = openBodies.size

        val (status, body) = send(projectId, runId, token, """{"message":"모델 없이 run$runId"}""")

        assertThat(status).isEqualTo(400)
        assertThat(json(body)["code"].asText()).isEqualTo("model_required")
        Thread.sleep(800)
        assertThat(openBodies).hasSize(opened)
    }

    @Test
    fun `사용자 key 의 model 에서 공용 key 의 model 로 바꾸면 그 턴의 key 칸이 빈다`(): Unit = runBlocking {
        val (appUserId, token) = issueUser()
        val (projectId, runId) = memberRun(appUserId)
        storeAnthropicKey(appUserId)
        val opened = openBodies.size

        assertThat(send(projectId, runId, token, """{"message":"내 키로 시작 run$runId","model":"$CLAUDE","keySource":"user"}""").first).isEqualTo(200)
        awaitSize(openBodies, opened + 1)

        Thread.sleep(1500)
        val turns = turnFrames.size
        assertThat(send(projectId, runId, token, """{"message":"공용 key 로","model":"$LUNA","keySource":"platform"}""").first).isEqualTo(200)
        awaitSize(turnFrames, turns + 1)

        val turn = json(turnFrames.last { it.contains("\"type\":\"turn\"") })
        assertThat(turn["model"].asText()).isEqualTo(LUNA)
        assertThat(turn.has("llm_key")).isFalse()
    }
}
