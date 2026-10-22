package kr.artel.orchestration.projectagent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.settings.repository.PlatformSettingRepository
import kr.artel.orchestration.settings.service.ANTHROPIC_API_KEY_SETTING
import kr.artel.orchestration.settings.service.PROJECT_AGENT_MODEL_SETTING
import kr.artel.orchestration.settings.service.SecretCipher
import kr.artel.orchestration.userllmkey.entity.LlmKeyProvider
import kr.artel.orchestration.userllmkey.entity.UserLlmKeyEntity
import kr.artel.orchestration.userllmkey.repository.UserLlmKeyRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * project agent model 의 세 단계(사용자 > 프로젝트 > 관리자)와 step 실패 원인.
 *
 * agent-server 는 `POST /project-agent/step` 만 받는 가짜다. [queuedReplies] 의 답을 순서대로 돌려주고, 받은 본문을
 * [stepRequests] 에 쌓는다. `artel.agent.model` 은 test profile 에서 비어 있어, 아무 설정도 없으면 model 은 null 이다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
class ProjectAgentModelIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var platformSettingRepository: PlatformSettingRepository
    @Autowired private lateinit var userLlmKeyRepository: UserLlmKeyRepository
    @Autowired private lateinit var secretCipher: SecretCipher
    @Autowired private lateinit var databaseClient: DatabaseClient

    private data class Reply(val status: Int, val body: String)

    private data class Member(val appUserId: Long, val token: String, val projectId: Long)

    companion object {
        private const val USER_MODEL = "claude/claude-opus-5-5"
        private const val PROJECT_MODEL = "claude/claude-sonnet-5-5"
        private const val ADMIN_MODEL = "claude/claude-haiku-5-5"
        private const val GPT_MODEL = "gpt/gpt-6.1-sol"
        private const val USER_ANTHROPIC_KEY = "sk-ant-api03-user-anthropic-key-0123456789-wxyz"
        private const val TEXT_REPLY = """{"content":"끝났습니다.","tool_calls":[]}"""

        private lateinit var fakeAgent: DisposableServer
        private val queuedReplies = ConcurrentLinkedQueue<Reply>()
        private val stepRequests = CopyOnWriteArrayList<String>()
        private val seq = AtomicLong(19000)

        @JvmStatic
        @DynamicPropertySource
        fun registerAgent(registry: DynamicPropertyRegistry) {
            fakeAgent = HttpServer.create().port(0).route { routes ->
                routes.post("/project-agent/step") { request, response ->
                    request.receive().aggregate().asString().flatMap { body ->
                        stepRequests.add(body)
                        val reply = queuedReplies.poll() ?: Reply(200, TEXT_REPLY)
                        response.status(reply.status)
                            .header("Content-Type", "application/json")
                            .sendString(Mono.just(reply.body))
                            .then()
                    }
                }
            }.bindNow()
            registry.add("artel.agent.base-url") { "http://localhost:${fakeAgent.port()}" }
            registry.add("artel.project-agent.step-timeout") { "PT2S" }
            registry.add("artel.secrets.key") { "test-only-aes-key-that-is-at-least-32-characters" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            fakeAgent.disposeNow()
        }
    }

    @BeforeEach
    fun reset(): Unit = runBlocking {
        queuedReplies.clear()
        stepRequests.clear()
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
    }

    /** 다른 테스트가 `user_llm_key` 전체 행 수를 보므로, 이 테스트가 넣은 사용자 key 를 남기지 않는다. */
    @AfterEach
    fun clean(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        usersWithKeys.forEach { userLlmKeyRepository.deleteOne(it, LlmKeyProvider.ANTHROPIC.wire) }
        usersWithKeys.clear()
    }

    private val usersWithKeys = mutableListOf<Long>()

    // ------------------------------------------------------------ 사용자 선택 API

    @Test
    fun `사용자 선택이 없으면 관리자 설정을 effectiveModel 로 돌려준다`(): Unit = runBlocking {
        val member = newMember("me-empty")
        val empty = me(member)
        assertThat(empty["model"].isNull).isTrue()
        assertThat(empty["keySource"].isNull).isTrue()
        assertThat(empty["effectiveModel"].isNull).isTrue()
        assertThat(empty["problem"].isNull).isTrue()

        storeAdminModel(ADMIN_MODEL)
        val withAdmin = me(member)
        assertThat(withAdmin["effectiveModel"].asText()).isEqualTo(ADMIN_MODEL)
        assertThat(withAdmin["effectiveKeySource"].asText()).isEqualTo("platform")
    }

    @Test
    fun `사용자 선택은 QA run 과 같이 확인하고 쓸 수 없으면 400 이다`(): Unit = runBlocking {
        val member = newMember("me-validate")
        val cases = listOf(
            """{"model":"no/such-model","keySource":"platform"}""" to "llm_model_unknown",
            """{"model":"$USER_MODEL","keySource":"platform"}""" to "llm_key_missing",
            """{"model":"$USER_MODEL","keySource":"user"}""" to "llm_key_missing",
            """{"model":"$GPT_MODEL"}""" to "llm_key_missing",
        )
        for ((json, code) in cases) {
            val (status, body) = callMe(member, HttpMethod.PUT, json)
            assertThat(status).describedAs(json).isEqualTo(400)
            assertThat(body!!["code"].asText()).describedAs(json).isEqualTo(code)
        }
        assertThat(callMe(member, HttpMethod.PUT, """{"model":"$USER_MODEL","keySource":"mine"}""").first).isEqualTo(400)
        assertThat(me(member)["model"].isNull).isTrue()
    }

    @Test
    fun `사용자 key 로 고른 model 을 저장하고 key 가 지워지면 problem 을 알린다`(): Unit = runBlocking {
        val member = newMember("me-store")
        storeUserAnthropicKey(member.appUserId)

        val (status, stored) = callMe(member, HttpMethod.PUT, """{"model":"$USER_MODEL","keySource":"user"}""")
        assertThat(status).isEqualTo(200)
        assertThat(stored!!["model"].asText()).isEqualTo(USER_MODEL)
        assertThat(stored["keySource"].asText()).isEqualTo("user")
        assertThat(stored["effectiveModel"].asText()).isEqualTo(USER_MODEL)
        assertThat(stored["effectiveKeySource"].asText()).isEqualTo("user")
        assertThat(stored["problem"].isNull).isTrue()

        userLlmKeyRepository.deleteOne(member.appUserId, LlmKeyProvider.ANTHROPIC.wire)
        val broken = me(member)
        assertThat(broken["model"].asText()).isEqualTo(USER_MODEL)
        // step 은 다른 model 로 넘어가지 않는다. effective 는 고른 값 그대로다.
        assertThat(broken["effectiveModel"].asText()).isEqualTo(USER_MODEL)
        assertThat(broken["problem"].asText()).isEqualTo("llm_key_missing")

        val (clearedStatus, cleared) = callMe(member, HttpMethod.PUT, """{"model":null}""")
        assertThat(clearedStatus).isEqualTo(200)
        assertThat(cleared!!["model"].isNull).isTrue()
        assertThat(cleared["keySource"].isNull).isTrue()
        assertThat(cleared["problem"].isNull).isTrue()
    }

    // ------------------------------------------------------------ 프로젝트 설정 API

    @Test
    fun `프로젝트 설정은 OWNER 만 바꾸고 멤버는 읽기만 하며 밖의 사람은 404 다`(): Unit = runBlocking {
        val owner = newMember("project-owner")
        val teammate = newMember("project-teammate", owner.projectId, role = "MEMBER")
        val outsider = newMember("project-outsider")
        storeAdminAnthropicKey()

        val (status, stored) = callProject(owner, owner.projectId, HttpMethod.PUT, """{"model":"$PROJECT_MODEL"}""")
        assertThat(status).isEqualTo(200)
        assertThat(stored!!["model"].asText()).isEqualTo(PROJECT_MODEL)
        assertThat(stored["effectiveModel"].asText()).isEqualTo(PROJECT_MODEL)
        assertThat(stored["problem"].isNull).isTrue()

        val (teammateRead, read) = callProject(teammate, owner.projectId, HttpMethod.GET)
        assertThat(teammateRead).isEqualTo(200)
        assertThat(read!!["model"].asText()).isEqualTo(PROJECT_MODEL)
        assertThat(callProject(teammate, owner.projectId, HttpMethod.PUT, """{"model":null}""").first).isEqualTo(403)
        assertThat(callProject(outsider, owner.projectId, HttpMethod.GET).first).isEqualTo(404)
        assertThat(callProject(outsider, owner.projectId, HttpMethod.PUT, """{"model":null}""").first).isEqualTo(404)
        // 다른 프로젝트의 멤버가 그 프로젝트로 effective 를 물으면 404 다.
        assertThat(callMe(outsider, HttpMethod.GET, query = "?projectId=${owner.projectId}").first).isEqualTo(404)

        // 프로젝트를 주면 effective 가 프로젝트 설정을 따르고, 주지 않으면 관리자 설정 이하를 따른다.
        assertThat(me(teammate, owner.projectId)["effectiveModel"].asText()).isEqualTo(PROJECT_MODEL)
        assertThat(me(teammate)["effectiveModel"].isNull).isTrue()

        val (clearedStatus, cleared) = callProject(owner, owner.projectId, HttpMethod.PUT, """{"model":null}""")
        assertThat(clearedStatus).isEqualTo(200)
        assertThat(cleared!!["model"].isNull).isTrue()
    }

    @Test
    fun `프로젝트 설정은 공용 key 로 부를 수 있는 model 만 받고 key 가 사라지면 관리자 설정으로 넘어간다`(): Unit = runBlocking {
        val owner = newMember("project-key")
        for (model in listOf(GPT_MODEL, PROJECT_MODEL)) {
            val (status, body) = callProject(owner, owner.projectId, HttpMethod.PUT, """{"model":"$model"}""")
            assertThat(status).describedAs(model).isEqualTo(400)
            assertThat(body!!["code"].asText()).describedAs(model).isEqualTo("llm_key_missing")
        }
        val (unknownStatus, unknown) = callProject(owner, owner.projectId, HttpMethod.PUT, """{"model":"no/such-model"}""")
        assertThat(unknownStatus).isEqualTo(400)
        assertThat(unknown!!["code"].asText()).isEqualTo("llm_model_unknown")

        storeAdminAnthropicKey()
        storeAdminModel(ADMIN_MODEL)
        assertThat(callProject(owner, owner.projectId, HttpMethod.PUT, """{"model":"$PROJECT_MODEL"}""").first).isEqualTo(200)

        platformSettingRepository.deleteById(ANTHROPIC_API_KEY_SETTING)
        val (_, broken) = callProject(owner, owner.projectId, HttpMethod.GET)
        assertThat(broken!!["model"].asText()).isEqualTo(PROJECT_MODEL)
        assertThat(broken["problem"].asText()).isEqualTo("llm_key_missing")
        assertThat(broken["effectiveModel"].asText()).isEqualTo(ADMIN_MODEL)
    }

    // ------------------------------------------------------------ step 요청

    @Test
    fun `step 의 model 은 사용자 선택, 프로젝트 설정, 관리자 설정 순서로 정하고 사용자 key 를 llm_key 로 싣는다`(
        output: CapturedOutput
    ): Unit = runBlocking {
        val member = newMember("step-order")
        val conversationId = createConversation(member)

        sendAndAwait(member, conversationId, "IDLE")
        assertThat(lastStepRequest()["model"].isNull).isTrue()
        assertThat(lastStepRequest()["llm_key"].isNull).isTrue()

        storeAdminModel(ADMIN_MODEL)
        sendAndAwait(member, conversationId, "IDLE")
        assertThat(lastStepRequest()["model"].asText()).isEqualTo(ADMIN_MODEL)

        storeAdminAnthropicKey()
        assertThat(callProject(member, member.projectId, HttpMethod.PUT, """{"model":"$PROJECT_MODEL"}""").first).isEqualTo(200)
        sendAndAwait(member, conversationId, "IDLE")
        assertThat(lastStepRequest()["model"].asText()).isEqualTo(PROJECT_MODEL)
        assertThat(lastStepRequest()["llm_key"].isNull).isTrue()

        storeUserAnthropicKey(member.appUserId)
        assertThat(callMe(member, HttpMethod.PUT, """{"model":"$USER_MODEL","keySource":"user"}""").first).isEqualTo(200)
        sendAndAwait(member, conversationId, "IDLE")
        val request = lastStepRequest()
        assertThat(request["model"].asText()).isEqualTo(USER_MODEL)
        assertThat(request["llm_key"]["service"].asText()).isEqualTo("anthropic")
        assertThat(request["llm_key"]["api_key"].asText()).isEqualTo(USER_ANTHROPIC_KEY)
        assertThat(output.all).doesNotContain(USER_ANTHROPIC_KEY)
    }

    @Test
    fun `고른 사용자 key 가 사라지면 step 을 부르지 않고 llm_key_missing NOTICE 를 남긴다`(): Unit = runBlocking {
        val member = newMember("step-missing")
        storeUserAnthropicKey(member.appUserId)
        assertThat(callMe(member, HttpMethod.PUT, """{"model":"$USER_MODEL","keySource":"user"}""").first).isEqualTo(200)
        userLlmKeyRepository.deleteOne(member.appUserId, LlmKeyProvider.ANTHROPIC.wire)
        // 관리자 설정이 있어도 그쪽으로 넘어가지 않는다.
        storeAdminModel(ADMIN_MODEL)

        val conversationId = createConversation(member)
        sendAndAwait(member, conversationId, "ERROR")
        assertThat(stepRequests).isEmpty()

        val notice = lastNotice(member, conversationId)
        assertThat(notice["payload"]["kind"].asText()).isEqualTo("error")
        assertThat(notice["payload"]["cause"].asText()).isEqualTo("llm_key_missing")
        assertThat(notice["payload"]["model"].asText()).isEqualTo(USER_MODEL)
        assertThat(notice["payload"]["keySource"].asText()).isEqualTo("user")
        assertThat(notice["content"].asText()).contains("개인 key").contains("계정 설정")
    }

    @Test
    fun `agent-server 실패 본문의 detail code 를 NOTICE 원인으로 남기고 locale 에 맞는 문장을 쓴다`(): Unit = runBlocking {
        val member = newMember("step-cause")
        storeAdminModel(ADMIN_MODEL)
        appUserRepository.findById(member.appUserId)!!.let { appUserRepository.save(it.copy(locale = "en")) }
        val conversationId = createConversation(member)

        queuedReplies.add(Reply(502, """{"detail":{"code":"llm_auth_failed","message":"The provider rejected the key."}}"""))
        sendAndAwait(member, conversationId, "ERROR")
        val rejected = lastNotice(member, conversationId)
        assertThat(rejected["payload"]["cause"].asText()).isEqualTo("llm_auth_failed")
        assertThat(rejected["payload"]["model"].asText()).isEqualTo(ADMIN_MODEL)
        assertThat(rejected["payload"]["keySource"].asText()).isEqualTo("platform")
        assertThat(rejected["content"].asText()).contains("shared key was rejected").contains(ADMIN_MODEL)
        assertThat(rejected["content"].asText()).doesNotContain("The provider rejected the key.")

        // 모르는 값이나 읽지 못하는 본문은 llm_provider_error 다.
        queuedReplies.add(Reply(502, """{"detail":"provider down"}"""))
        sendAndAwait(member, conversationId, "ERROR")
        assertThat(lastNotice(member, conversationId)["payload"]["cause"].asText()).isEqualTo("llm_provider_error")

        // 코드가 없는 422 는 agent-server 가 model 을 거절한 것이다.
        queuedReplies.add(Reply(422, """{"detail":"unknown model"}"""))
        sendAndAwait(member, conversationId, "ERROR")
        assertThat(lastNotice(member, conversationId)["payload"]["cause"].asText()).isEqualTo("llm_model_unavailable")
    }

    // ------------------------------------------------------------ helpers

    private suspend fun newMember(label: String, projectId: Long? = null, role: String = "OWNER"): Member {
        val providerUserId = "$label-${seq.incrementAndGet()}"
        val user = oauthUserService.upsert(
            OAuthIdentity("github", providerUserId, "pam-$providerUserId", "Project Agent Model", null, null)
        )
        val appUserId = user.userId.toLong()
        val now = Instant.now()
        val project = projectId ?: projectRepository.save(
            ProjectEntity(name = "agent-model-project", genre = "RPG", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = project, appUserId = appUserId, role = role, createdAt = now)
        )
        return Member(appUserId, jwtService.issue(user), project)
    }

    private suspend fun storeAdminModel(model: String) {
        platformSettingRepository.upsert(PROJECT_AGENT_MODEL_SETTING, model, false, null, Instant.now())
    }

    private suspend fun storeAdminAnthropicKey() {
        platformSettingRepository.upsert(
            ANTHROPIC_API_KEY_SETTING,
            secretCipher.encrypt("sk-ant-api03-admin-key-0123456789abcdef-zzzz", ANTHROPIC_API_KEY_SETTING),
            true, null, Instant.now()
        )
    }

    private suspend fun storeUserAnthropicKey(appUserId: Long) {
        usersWithKeys += appUserId
        val now = Instant.now()
        userLlmKeyRepository.upsert(
            appUserId = appUserId,
            service = LlmKeyProvider.ANTHROPIC.wire,
            encryptedKey = secretCipher.encrypt(USER_ANTHROPIC_KEY, UserLlmKeyEntity.settingKey(appUserId, LlmKeyProvider.ANTHROPIC)),
            keyHint = "sk-ant…wxyz",
            verifiedAt = now,
            updatedAt = now
        )
    }

    private fun exchange(member: Member, method: HttpMethod, path: String, body: String? = null): Pair<Int, JsonNode?> {
        val spec = WebClient.create("http://localhost:$port").method(method).uri(path)
            .cookie("artel_access_token", member.token)
            .contentType(MediaType.APPLICATION_JSON)
        val request = if (body == null) spec else spec.bodyValue(body)
        val (status, text) = request
            .exchangeToMono { response ->
                response.bodyToMono(String::class.java).defaultIfEmpty("").map { response.statusCode().value() to it }
            }
            .block(Duration.ofSeconds(10))!!
        return status to text.takeIf { it.isNotBlank() }?.let(objectMapper::readTree)
    }

    private fun callMe(member: Member, method: HttpMethod, body: String? = null, query: String = "") =
        exchange(member, method, "/api/auth/me/project-agent-model$query", body)

    private fun me(member: Member, projectId: Long? = null): JsonNode {
        val (status, body) = callMe(member, HttpMethod.GET, query = projectId?.let { "?projectId=$it" }.orEmpty())
        assertThat(status).isEqualTo(200)
        return body!!
    }

    private fun callProject(member: Member, projectId: Long, method: HttpMethod, body: String? = null) =
        exchange(member, method, "/api/projects/$projectId/project-agent-model", body)

    private fun createConversation(member: Member): Long {
        val (status, body) = exchange(
            member, HttpMethod.POST, "/api/projects/${member.projectId}/agent/conversations", """{"pageCatalog":{"pages":[]}}"""
        )
        assertThat(status).isEqualTo(201)
        return body!!["id"].asLong()
    }

    private fun sendAndAwait(member: Member, conversationId: Long, expected: String) {
        val base = "/api/projects/${member.projectId}/agent/conversations/$conversationId"
        assertThat(exchange(member, HttpMethod.POST, "$base/messages", """{"text":"QA 돌려 줘"}""").first).isEqualTo(202)
        val deadline = System.currentTimeMillis() + 8_000
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = exchange(member, HttpMethod.GET, base).second!!["status"].asText()
            if (last == expected) return
            Thread.sleep(50)
        }
        throw AssertionError("status 가 $expected 가 되지 않았다: $last")
    }

    private fun lastStepRequest(): JsonNode = objectMapper.readTree(stepRequests.last())

    private fun lastNotice(member: Member, conversationId: Long): JsonNode =
        exchange(member, HttpMethod.GET, "/api/projects/${member.projectId}/agent/conversations/$conversationId/messages")
            .second!!["items"].last { it["role"].asText() == "NOTICE" }
}
