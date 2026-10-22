package kr.artel.orchestration.projectagent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.projectagent.dto.SendProjectAgentMessageRequest
import kr.artel.orchestration.projectagent.dto.ProjectAgentToolResultRequest
import kr.artel.orchestration.projectagent.entity.ProjectAgentConversationEntity
import kr.artel.orchestration.projectagent.entity.ProjectAgentMessageEntity
import kr.artel.orchestration.projectagent.entity.ProjectAgentRole
import kr.artel.orchestration.projectagent.repository.ProjectAgentMessageRepository
import kr.artel.orchestration.projectagent.entity.ProjectAgentStatus
import kr.artel.orchestration.projectagent.repository.ProjectAgentConversationRepository
import kr.artel.orchestration.projectagent.service.ProjectAgentService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
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
 * project agent 대화 API(ARTEL-1127). agent-server 는 `POST /project-agent/step` 만 받는 가짜로 바꾼다.
 *
 * 가짜는 [queuedReplies] 에 넣어 둔 답을 순서대로 돌려주고, 비어 있으면 글로만 답한다. 받은 요청 본문은
 * [stepRequests] 에 쌓여 있어 snake_case 모양과 `tools_enabled` 를 거기서 확인한다.
 * 한도와 timeout 은 테스트가 보기 쉽게 줄여 둔다(tool call 3개, step 2초, heartbeat 1초).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectAgentConversationIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var oauthUserService: OAuthUserService

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var projectMemberRepository: ProjectMemberRepository

    @Autowired
    private lateinit var conversationRepository: ProjectAgentConversationRepository

    @Autowired
    private lateinit var service: ProjectAgentService

    @Autowired
    private lateinit var messageRepository: ProjectAgentMessageRepository

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private data class Reply(val status: Int, val body: String, val delayMillis: Long = 0)

    private data class Member(val appUserId: Long, val token: String, val projectId: Long)

    companion object {
        private lateinit var fakeAgent: DisposableServer

        private val queuedReplies = ConcurrentLinkedQueue<Reply>()
        private val stepRequests = CopyOnWriteArrayList<String>()
        private val seq = AtomicLong(9000)

        private const val OPEN_PAGE_REPLY =
            """{"content":"QA 페이지를 엽니다.","tool_calls":[{"id":"call_1","name":"open_page","args":{"page":"qa"}}]}"""
        private const val TWO_CALLS_REPLY =
            """{"content":"둘 다 합니다.","tool_calls":[{"id":"call_a","name":"read_page","args":{}},{"id":"call_b","name":"set_field","args":{"field":"build","value":3}}]}"""
        private const val TEXT_REPLY = """{"content":"끝났습니다.","tool_calls":[]}"""

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
                            .sendString(Mono.just(reply.body).delayElement(Duration.ofMillis(reply.delayMillis)))
                            .then()
                    }
                }
            }.bindNow()
            registry.add("artel.agent.base-url") { "http://localhost:${fakeAgent.port()}" }
            registry.add("artel.project-agent.step-timeout") { "PT2S" }
            registry.add("artel.project-agent.heartbeat-interval") { "PT1S" }
            registry.add("artel.project-agent.tool-call-limit") { "3" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            fakeAgent.disposeNow()
        }
    }

    @BeforeEach
    fun resetFake() {
        queuedReplies.clear()
        stepRequests.clear()
    }

    private fun client() = WebClient.create("http://localhost:$port")

    private suspend fun newMember(label: String, projectId: Long? = null): Member {
        val providerUserId = "$label-${seq.incrementAndGet()}"
        val user = oauthUserService.upsert(
            OAuthIdentity("github", providerUserId, "pa-$providerUserId", "Project Agent", null, null)
        )
        val appUserId = user.userId.toLong()
        val now = Instant.now()
        val project = projectId ?: projectRepository.save(
            ProjectEntity(name = "agent-project", genre = "RPG", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = project, appUserId = appUserId, role = "OWNER", createdAt = now)
        )
        return Member(appUserId, jwtService.issue(user), project)
    }

    private fun call(member: Member, method: HttpMethod, path: String, body: String? = null): Pair<Int, JsonNode?> {
        val spec = client().method(method)
            .uri("/api/projects/${member.projectId}/agent/conversations$path")
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

    private fun createConversation(member: Member): Long {
        val (status, body) = call(member, HttpMethod.POST, "", """{"pageCatalog":{"pages":[{"id":"qa"}]}}""")
        assertThat(status).isEqualTo(201)
        return body!!["id"].asLong()
    }

    private fun send(member: Member, conversationId: Long, text: String = "QA 돌려 줘") =
        call(member, HttpMethod.POST, "/$conversationId/messages", """{"text":"$text"}""")

    private fun toolResult(member: Member, conversationId: Long, callId: String, status: String = "ok") =
        call(
            member, HttpMethod.POST, "/$conversationId/tool-results",
            """{"callId":"$callId","status":"$status","result":{"done":true}}""",
        )

    private fun statusOf(member: Member, conversationId: Long): String =
        call(member, HttpMethod.GET, "/$conversationId").second!!["status"].asText()

    private fun roles(member: Member, conversationId: Long): List<String> =
        call(member, HttpMethod.GET, "/$conversationId/messages").second!!["items"].map { it["role"].asText() }

    private fun awaitStatus(member: Member, conversationId: Long, expected: String) {
        val deadline = System.currentTimeMillis() + 8_000
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = statusOf(member, conversationId)
            if (last == expected) return
            Thread.sleep(50)
        }
        throw AssertionError("status 가 $expected 가 되지 않았다: $last")
    }

    @Test
    fun `대화를 만들고 목록과 이름 변경과 삭제를 한다`(): Unit = runBlocking {
        val member = newMember("crud")
        val first = createConversation(member)
        val second = createConversation(member)

        val (_, list) = call(member, HttpMethod.GET, "")
        assertThat(list!!["items"].map { it["id"].asLong() }).containsExactly(second, first)
        val created = call(member, HttpMethod.GET, "/$first").second!!
        assertThat(created["status"].asText()).isEqualTo("IDLE")
        assertThat(created["title"].isNull).isTrue()
        assertThat(created["projectId"].asLong()).isEqualTo(member.projectId)

        val renamed = call(member, HttpMethod.PATCH, "/$first", """{"title":"새 제목"}""")
        assertThat(renamed.first).isEqualTo(200)
        assertThat(renamed.second!!["title"].asText()).isEqualTo("새 제목")

        assertThat(call(member, HttpMethod.DELETE, "/$first").first).isEqualTo(204)
        assertThat(call(member, HttpMethod.GET, "/$first").first).isEqualTo(404)
        assertThat(call(member, HttpMethod.GET, "").second!!["items"].map { it["id"].asLong() }).containsExactly(second)
    }

    @Test
    fun `다른 사용자의 대화와 참여하지 않은 프로젝트는 404 다`(): Unit = runBlocking {
        val owner = newMember("owner")
        val conversationId = createConversation(owner)
        val teammate = newMember("teammate", owner.projectId)
        val outsider = newMember("outsider")
        val outsiderOnOwnerProject = outsider.copy(projectId = owner.projectId)

        // 같은 프로젝트의 다른 멤버도 남의 대화는 볼 수 없다.
        assertThat(call(teammate, HttpMethod.GET, "/$conversationId").first).isEqualTo(404)
        assertThat(send(teammate, conversationId).first).isEqualTo(404)
        assertThat(call(teammate, HttpMethod.POST, "/$conversationId/cancel").first).isEqualTo(404)
        assertThat(call(teammate, HttpMethod.GET, "").second!!["items"]).isEmpty()
        // 프로젝트 멤버가 아니면 목록과 생성도 404 다.
        assertThat(call(outsiderOnOwnerProject, HttpMethod.GET, "").first).isEqualTo(404)
        assertThat(call(outsiderOnOwnerProject, HttpMethod.POST, "", """{"pageCatalog":{"pages":[]}}""").first).isEqualTo(404)
        assertThat(call(outsiderOnOwnerProject, HttpMethod.GET, "/$conversationId/messages").first).isEqualTo(404)
        // 다른 프로젝트 경로로는 자기 대화도 찾을 수 없다.
        assertThat(call(owner.copy(projectId = outsider.projectId), HttpMethod.GET, "/$conversationId").first).isEqualTo(404)
    }

    @Test
    fun `메시지를 보내면 step 을 불러 tool call 이 달린 ASSISTANT 를 저장하고 AWAITING_TOOL 이 된다`(): Unit = runBlocking {
        val member = newMember("step")
        val conversationId = createConversation(member)
        queuedReplies.add(Reply(200, OPEN_PAGE_REPLY))

        val (status, stored) = call(
            member, HttpMethod.POST, "/$conversationId/messages",
            """{"text":"QA 돌려 줘","pageCatalog":{"pages":[{"id":"home"}]},"page":{"page":"home","params":{},"fields":{},"actions":[]}}""",
        )
        assertThat(status).isEqualTo(202)
        assertThat(stored!!["role"].asText()).isEqualTo("USER")
        assertThat(stored["content"].asText()).isEqualTo("QA 돌려 줘")
        assertThat(stored["payload"]["page"]["page"].asText()).isEqualTo("home")

        awaitStatus(member, conversationId, "AWAITING_TOOL")
        val messages = call(member, HttpMethod.GET, "/$conversationId/messages").second!!["items"]
        assertThat(messages.map { it["role"].asText() }).containsExactly("USER", "ASSISTANT")
        val assistant = messages[1]
        assertThat(assistant["content"].asText()).isEqualTo("QA 페이지를 엽니다.")
        val toolCall = assistant["payload"]["toolCalls"][0]
        assertThat(toolCall["callId"].asText()).isEqualTo("call_1")
        assertThat(toolCall["tool"].asText()).isEqualTo("open_page")
        assertThat(toolCall["args"]["page"].asText()).isEqualTo("qa")

        val conversation = call(member, HttpMethod.GET, "/$conversationId").second!!
        assertThat(conversation["title"].asText()).isEqualTo("QA 돌려 줘")

        // agent-server 가 받은 요청은 snake_case 이고, 바뀐 page catalog 와 현재 page 가 실렸다.
        val request = objectMapper.readTree(stepRequests.single())
        assertThat(request["conversation_id"].asLong()).isEqualTo(conversationId)
        assertThat(request["project_id"].asLong()).isEqualTo(member.projectId)
        assertThat(request["locale"].asText()).isEqualTo("ko")
        assertThat(request["tools_enabled"].asBoolean()).isTrue()
        assertThat(request["page_catalog"]["pages"][0]["id"].asText()).isEqualTo("home")
        assertThat(request["messages"].size()).isEqualTo(1)
        assertThat(request["messages"][0]["role"].asText()).isEqualTo("user")
        assertThat(request["messages"][0]["content"].asText()).startsWith("QA 돌려 줘\n\n[current page]\n{")
    }

    @Test
    fun `첫 메시지로 만든 제목은 60자에서 자른다`(): Unit = runBlocking {
        val member = newMember("title")
        val conversationId = createConversation(member)
        send(member, conversationId, "가".repeat(100))
        awaitStatus(member, conversationId, "IDLE")
        send(member, conversationId, "두 번째 메시지")
        awaitStatus(member, conversationId, "IDLE")

        val title = call(member, HttpMethod.GET, "/$conversationId").second!!["title"].asText()
        assertThat(title).isEqualTo("가".repeat(60))
    }

    @Test
    fun `답을 기다리지 않는 callId 는 409 not_pending 이다`(): Unit = runBlocking {
        val member = newMember("pending")
        val conversationId = createConversation(member)

        // 아직 ASSISTANT 메시지가 없다.
        val early = toolResult(member, conversationId, "call_1")
        assertThat(early.first).isEqualTo(409)
        assertThat(early.second!!["code"].asText()).isEqualTo("not_pending")

        queuedReplies.add(Reply(200, OPEN_PAGE_REPLY))
        send(member, conversationId)
        awaitStatus(member, conversationId, "AWAITING_TOOL")

        val unknown = toolResult(member, conversationId, "call_999")
        assertThat(unknown.first).isEqualTo(409)
        assertThat(unknown.second!!["code"].asText()).isEqualTo("not_pending")

        assertThat(toolResult(member, conversationId, "call_1").first).isEqualTo(202)
        awaitStatus(member, conversationId, "IDLE")
        // 이미 답한 호출에 다시 답할 수 없다.
        assertThat(toolResult(member, conversationId, "call_1").first).isEqualTo(409)
        assertThat(toolResult(member, conversationId, "call_1", status = "wrong").first).isEqualTo(400)
    }

    @Test
    fun `마지막 tool 결과가 오면 다음 step 을 부르고 tool 메시지를 history 에 싣는다`(): Unit = runBlocking {
        val member = newMember("loop")
        val conversationId = createConversation(member)
        queuedReplies.add(Reply(200, TWO_CALLS_REPLY))
        queuedReplies.add(Reply(200, TEXT_REPLY))
        send(member, conversationId)
        awaitStatus(member, conversationId, "AWAITING_TOOL")

        val first = toolResult(member, conversationId, "call_a")
        assertThat(first.first).isEqualTo(202)
        assertThat(first.second!!["role"].asText()).isEqualTo("TOOL_RESULT")
        assertThat(first.second!!["payload"]["callId"].asText()).isEqualTo("call_a")
        // 아직 call_b 가 남아 있어 step 을 부르지 않는다.
        Thread.sleep(300)
        assertThat(statusOf(member, conversationId)).isEqualTo("AWAITING_TOOL")
        assertThat(stepRequests).hasSize(1)

        toolResult(member, conversationId, "call_b")
        awaitStatus(member, conversationId, "IDLE")
        assertThat(roles(member, conversationId))
            .containsExactly("USER", "ASSISTANT", "TOOL_RESULT", "TOOL_RESULT", "ASSISTANT")

        val second = objectMapper.readTree(stepRequests[1])
        val history = second["messages"]
        assertThat(history.map { it["role"].asText() }).containsExactly("user", "assistant", "tool", "tool")
        assertThat(history[1]["tool_calls"].map { it["id"].asText() }).containsExactly("call_a", "call_b")
        assertThat(history[2]["tool_call_id"].asText()).isEqualTo("call_a")
        assertThat(objectMapper.readTree(history[2]["content"].asText())["status"].asText()).isEqualTo("ok")
        assertThat(history[0].has("tool_calls")).isFalse()
    }

    @Test
    fun `step 이 도는 동안 들어온 입력은 409 conversation_busy 다`(): Unit = runBlocking {
        val member = newMember("busy")
        val conversationId = createConversation(member)
        queuedReplies.add(Reply(200, TEXT_REPLY, delayMillis = 1_000))
        send(member, conversationId)
        assertThat(statusOf(member, conversationId)).isEqualTo("RUNNING")

        val message = send(member, conversationId, "또 보냄")
        assertThat(message.first).isEqualTo(409)
        assertThat(message.second!!["code"].asText()).isEqualTo("conversation_busy")
        val event = call(member, HttpMethod.POST, "/$conversationId/events", """{"kind":"job_finished","summary":"끝"}""")
        assertThat(event.first).isEqualTo(409)

        awaitStatus(member, conversationId, "IDLE")
        assertThat(roles(member, conversationId)).containsExactly("USER", "ASSISTANT")
        assertThat(stepRequests).hasSize(1)
    }

    @Test
    fun `이벤트는 EVENT 메시지로 저장되고 model 에는 user 메시지로 간다`(): Unit = runBlocking {
        val member = newMember("event")
        val conversationId = createConversation(member)

        val (status, stored) = call(
            member, HttpMethod.POST, "/$conversationId/events",
            """{"kind":"job_finished","summary":"스캔이 끝났습니다","data":{"id":"7"}}""",
        )
        assertThat(status).isEqualTo(202)
        assertThat(stored!!["role"].asText()).isEqualTo("EVENT")
        assertThat(stored["content"].asText()).isEqualTo("스캔이 끝났습니다")
        assertThat(stored["payload"]["kind"].asText()).isEqualTo("job_finished")
        awaitStatus(member, conversationId, "IDLE")

        val content = objectMapper.readTree(stepRequests.single())["messages"][0]["content"].asText()
        assertThat(content).isEqualTo("[event:job_finished] 스캔이 끝났습니다\n{\"id\":\"7\"}")
    }

    @Test
    fun `도는 step 을 취소하면 IDLE 이 되고 늦게 온 응답은 버려진다`(): Unit = runBlocking {
        val member = newMember("cancel-running")
        val conversationId = createConversation(member)
        queuedReplies.add(Reply(200, OPEN_PAGE_REPLY, delayMillis = 1_000))
        send(member, conversationId)

        val (status, body) = call(member, HttpMethod.POST, "/$conversationId/cancel")
        assertThat(status).isEqualTo(200)
        assertThat(body!!["status"].asText()).isEqualTo("IDLE")
        delay(1_500)
        assertThat(statusOf(member, conversationId)).isEqualTo("IDLE")
        assertThat(roles(member, conversationId)).containsExactly("USER", "NOTICE")
    }

    @Test
    fun `답을 기다리는 tool call 을 취소하면 cancelled 결과와 stopped NOTICE 가 남고 model 은 부르지 않는다`(): Unit = runBlocking {
        val member = newMember("cancel-awaiting")
        val conversationId = createConversation(member)
        queuedReplies.add(Reply(200, TWO_CALLS_REPLY))
        send(member, conversationId)
        awaitStatus(member, conversationId, "AWAITING_TOOL")

        val cancelled = call(member, HttpMethod.POST, "/$conversationId/cancel")
        assertThat(cancelled.second!!["status"].asText()).isEqualTo("IDLE")
        val items = call(member, HttpMethod.GET, "/$conversationId/messages").second!!["items"]
        assertThat(items.map { it["role"].asText() }).containsExactly("USER", "ASSISTANT", "TOOL_RESULT", "TOOL_RESULT", "NOTICE")
        assertThat(items[2]["payload"]["status"].asText()).isEqualTo("cancelled")
        assertThat(items[2]["payload"]["callId"].asText()).isEqualTo("call_a")
        assertThat(items[4]["payload"]["kind"].asText()).isEqualTo("stopped")
        assertThat(stepRequests).hasSize(1)
        assertThat(toolResult(member, conversationId, "call_a").first).isEqualTo(409)
    }

    @Test
    fun `step 이 실패하면 NOTICE error 와 ERROR 가 되고 다음 입력은 받는다`(): Unit = runBlocking {
        val member = newMember("error")
        val conversationId = createConversation(member)
        queuedReplies.add(Reply(502, """{"detail":"provider down"}"""))
        send(member, conversationId)
        awaitStatus(member, conversationId, "ERROR")

        val items = call(member, HttpMethod.GET, "/$conversationId/messages").second!!["items"]
        assertThat(items.map { it["role"].asText() }).containsExactly("USER", "NOTICE")
        assertThat(items[1]["payload"]["kind"].asText()).isEqualTo("error")
        // 서버 오류 원문은 화면 문장에 실리지 않는다.
        assertThat(items[1]["content"].asText()).doesNotContain("provider down")

        assertThat(send(member, conversationId, "다시").first).isEqualTo(202)
        awaitStatus(member, conversationId, "IDLE")
        assertThat(roles(member, conversationId)).containsExactly("USER", "NOTICE", "USER", "ASSISTANT")
    }

    @Test
    fun `step 이 제한 시간을 넘기면 ERROR 가 된다`(): Unit = runBlocking {
        val member = newMember("timeout")
        val conversationId = createConversation(member)
        queuedReplies.add(Reply(200, TEXT_REPLY, delayMillis = 4_000))
        send(member, conversationId)
        awaitStatus(member, conversationId, "ERROR")
        assertThat(roles(member, conversationId)).containsExactly("USER", "NOTICE")
    }

    @Test
    fun `tool call 이 한도에 닿으면 tools_enabled 를 끄고 step_limit NOTICE 를 남긴다`(): Unit = runBlocking {
        val member = newMember("limit")
        val conversationId = createConversation(member)
        // 한도는 3 이다. 호출을 셋 받아 결과를 모두 돌려주면 네 번째 step 부터 tool 이 꺼진다.
        repeat(3) { index ->
            queuedReplies.add(
                Reply(200, """{"content":"","tool_calls":[{"id":"call_$index","name":"read_page","args":{}}]}""")
            )
        }
        send(member, conversationId)
        repeat(3) { index ->
            awaitStatus(member, conversationId, "AWAITING_TOOL")
            assertThat(toolResult(member, conversationId, "call_$index").first).isEqualTo(202)
        }
        awaitStatus(member, conversationId, "IDLE")

        assertThat(stepRequests.map { objectMapper.readTree(it)["tools_enabled"].asBoolean() })
            .containsExactly(true, true, true, false)
        assertThat(roles(member, conversationId).takeLast(2)).containsExactly("ASSISTANT", "NOTICE")
        val notice = call(member, HttpMethod.GET, "/$conversationId/messages").second!!["items"].last()
        assertThat(notice["payload"]["kind"].asText()).isEqualTo("step_limit")

        // 다음 사용자 입력부터는 다시 센다.
        send(member, conversationId, "계속")
        awaitStatus(member, conversationId, "IDLE")
        assertThat(objectMapper.readTree(stepRequests.last())["tools_enabled"].asBoolean()).isTrue()
    }

    @Test
    fun `SSE 는 message 와 conversation 이벤트와 heartbeat comment 를 보낸다`(): Unit = runBlocking {
        val member = newMember("sse")
        val conversationId = createConversation(member)
        queuedReplies.add(Reply(200, OPEN_PAGE_REPLY))

        val events = CopyOnWriteArrayList<ServerSentEvent<String>>()
        val subscription = client().get()
            .uri("/api/projects/${member.projectId}/agent/conversations/$conversationId/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .cookie("artel_access_token", member.token)
            .retrieve()
            .bodyToFlux(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .doOnNext { events.add(it) }
            .subscribe()
        try {
            // 연결이 열리면 comment 가 먼저 온다.
            awaitUntil { events.any { it.comment() == "open" } }
            send(member, conversationId)
            awaitUntil { events.any { it.event() == "message" && it.data()!!.contains("\"role\":\"ASSISTANT\"") } }
            awaitUntil { events.any { it.comment() == "heartbeat" } }

            val names = events.mapNotNull { it.event() }
            assertThat(names).contains("message", "conversation")
            val userMessage = events.first { it.event() == "message" }
            assertThat(objectMapper.readTree(userMessage.data()!!)["role"].asText()).isEqualTo("USER")
            val last = events.last { it.event() == "conversation" }
            assertThat(objectMapper.readTree(last.data()!!)["status"].asText()).isEqualTo("AWAITING_TOOL")
        } finally {
            subscription.dispose()
        }
    }

    @Test
    fun `기동할 때 RUNNING 으로 남은 대화는 NOTICE error 와 함께 ERROR 가 된다`(): Unit = runBlocking {
        val member = newMember("recover")
        val now = Instant.now()
        val stuck = conversationRepository.save(
            ProjectAgentConversationEntity(
                projectId = member.projectId, appUserId = member.appUserId,
                status = ProjectAgentStatus.RUNNING, createdAt = now, lastMessageAt = now,
            )
        ).id!!

        assertThat(service.recoverInterrupted()).isGreaterThanOrEqualTo(1)

        assertThat(statusOf(member, stuck)).isEqualTo("ERROR")
        val items = call(member, HttpMethod.GET, "/$stuck/messages").second!!["items"]
        assertThat(items.map { it["role"].asText() }).containsExactly("NOTICE")
        assertThat(items[0]["payload"]["kind"].asText()).isEqualTo("error")
        assertThat(send(member, stuck).first).isEqualTo(202)
    }

    @Test
    fun `요청 coroutine 이 취소돼도 대화는 RUNNING 에 갇히지 않는다`(): Unit = runBlocking {
        val member = newMember("disconnect")
        val conversationId = createConversation(member)
        // 취소가 claim 앞, 저장 중간, step 시작 뒤 어디에 닿든 대화는 IDLE 로 돌아오거나 시작조차 하지 않아야 한다.
        repeat(15) { attempt ->
            val job = async(Dispatchers.Default) {
                service.sendMessage(member.projectId, conversationId, member.appUserId, SendProjectAgentMessageRequest("메시지 $attempt"))
            }
            delay(attempt.toLong() % 5)
            job.cancelAndJoin()
            awaitStatus(member, conversationId, "IDLE")
            val items = roles(member, conversationId)
            assertThat(items.count { it == "USER" }).isEqualTo(items.count { it == "ASSISTANT" })
        }

        // tool 결과 쪽도 같다. 취소가 어디에 닿든 AWAITING_TOOL 인데 답할 호출이 없는 상태가 남으면 안 된다.
        queuedReplies.add(Reply(200, OPEN_PAGE_REPLY))
        send(member, conversationId)
        awaitStatus(member, conversationId, "AWAITING_TOOL")
        val job = async(Dispatchers.Default) {
            service.submitToolResult(
                member.projectId, conversationId, member.appUserId, ProjectAgentToolResultRequest("call_1", "ok"),
            )
        }
        job.cancelAndJoin()
        Thread.sleep(300)
        val status = statusOf(member, conversationId)
        if (status == "AWAITING_TOOL") {
            assertThat(toolResult(member, conversationId, "call_1").first).isEqualTo(202)
        }
        awaitStatus(member, conversationId, "IDLE")
    }

    @Test
    fun `한 응답에서 겹치는 tool call id 는 서로 다른 id 로 바뀐다`(): Unit = runBlocking {
        val member = newMember("dup")
        val conversationId = createConversation(member)
        queuedReplies.add(
            Reply(
                200,
                """{"content":"","tool_calls":[{"id":"call_dup","name":"read_page","args":{}},{"id":"call_dup","name":"read_page","args":{}},{"id":"","name":"read_page","args":{}}]}""",
            )
        )
        send(member, conversationId)
        awaitStatus(member, conversationId, "AWAITING_TOOL")
        val calls = call(member, HttpMethod.GET, "/$conversationId/messages").second!!["items"][1]["payload"]["toolCalls"]
        val ids = calls.map { it["callId"].asText() }
        assertThat(ids).hasSize(3).doesNotHaveDuplicates()
        assertThat(ids[0]).isEqualTo("call_dup")

        ids.dropLast(1).forEach { assertThat(toolResult(member, conversationId, it).first).isEqualTo(202) }
        Thread.sleep(300)
        assertThat(statusOf(member, conversationId)).isEqualTo("AWAITING_TOOL")
        toolResult(member, conversationId, ids.last())
        awaitStatus(member, conversationId, "IDLE")
        assertThat(stepRequests).hasSize(2)
    }

    @Test
    fun `대화를 지우면 구독 중인 SSE stream 이 끝난다`(): Unit = runBlocking {
        val member = newMember("sse-delete")
        val conversationId = createConversation(member)
        val events = CopyOnWriteArrayList<ServerSentEvent<String>>()
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val subscription = client().get()
            .uri("/api/projects/${member.projectId}/agent/conversations/$conversationId/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .cookie("artel_access_token", member.token)
            .retrieve()
            .bodyToFlux(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .doOnNext { events.add(it) }
            .doOnComplete { completed.set(true) }
            .subscribe()
        try {
            awaitUntil { events.any { it.comment() == "open" } }
            assertThat(call(member, HttpMethod.DELETE, "/$conversationId").first).isEqualTo(204)
            awaitUntil { completed.get() }
            assertThat(events.mapNotNull { it.event() }).isEmpty()
            assertThat(events.mapNotNull { it.comment() }).doesNotContain("closed")
        } finally {
            subscription.dispose()
        }
    }

    @Test
    fun `답 없는 tool call 뒤에 입력이 이어진 대화는 cancelled 결과를 끼워 step 에 보낸다`(): Unit = runBlocking {
        val member = newMember("history")
        val now = Instant.now()
        val conversationId = conversationRepository.save(
            ProjectAgentConversationEntity(
                projectId = member.projectId, appUserId = member.appUserId,
                status = ProjectAgentStatus.ERROR, createdAt = now, lastMessageAt = now,
            )
        ).id!!
        messageRepository.save(ProjectAgentMessageEntity(conversationId = conversationId, role = ProjectAgentRole.USER, content = "처음", createdAt = now))
        messageRepository.save(
            ProjectAgentMessageEntity(
                conversationId = conversationId, role = ProjectAgentRole.ASSISTANT, content = "엽니다", createdAt = now,
                payload = Json.of("""{"toolCalls":[{"callId":"call_x","tool":"read_page","args":{}},{"callId":"call_y","tool":"read_page","args":{}}]}"""),
            )
        )
        messageRepository.save(
            ProjectAgentMessageEntity(
                conversationId = conversationId, role = ProjectAgentRole.TOOL_RESULT, createdAt = now,
                payload = Json.of("""{"callId":"call_y","status":"ok"}"""),
            )
        )

        send(member, conversationId, "다시")
        awaitStatus(member, conversationId, "IDLE")

        val messages = objectMapper.readTree(stepRequests.single())["messages"]
        assertThat(messages.map { it["role"].asText() }).containsExactly("user", "assistant", "tool", "tool", "user")
        assertThat(messages[2]["tool_call_id"].asText()).isEqualTo("call_x")
        assertThat(objectMapper.readTree(messages[2]["content"].asText())["status"].asText()).isEqualTo("cancelled")
        assertThat(messages[3]["tool_call_id"].asText()).isEqualTo("call_y")
    }

    @Test
    fun `잘못된 입력은 400 이고 대화는 그대로다`(): Unit = runBlocking {
        val member = newMember("validation")
        // JSON null 은 없는 것으로 본다.
        assertThat(call(member, HttpMethod.POST, "", """{"pageCatalog":null}""").first).isEqualTo(201)
        assertThat(call(member, HttpMethod.POST, "", """{}""").first).isEqualTo(201)
        assertThat(call(member, HttpMethod.POST, "", """{"pageCatalog":{"x":1}}""").first).isEqualTo(400)
        assertThat(call(member, HttpMethod.POST, "", """{"pageCatalog":[]}""").first).isEqualTo(400)
        assertThat(call(member, HttpMethod.POST, "", """{"pageCatalog":{"pages":"no"}}""").first).isEqualTo(400)

        val conversationId = createConversation(member)
        assertThat(
            call(member, HttpMethod.POST, "/$conversationId/messages", """{"text":"안녕","pageCatalog":{"pages":{}}}""").first
        ).isEqualTo(400)
        assertThat(statusOf(member, conversationId)).isEqualTo("IDLE")
        assertThat(
            call(member, HttpMethod.POST, "/$conversationId/messages", """{"text":"안녕","pageCatalog":null}""").first
        ).isEqualTo(202)
        awaitStatus(member, conversationId, "IDLE")

        val tooLong = "가".repeat(2001)
        assertThat(call(member, HttpMethod.POST, "/$conversationId/events", """{"kind":"${"k".repeat(65)}","summary":"s"}""").first).isEqualTo(400)
        assertThat(call(member, HttpMethod.POST, "/$conversationId/events", """{"kind":"job_finished","summary":"$tooLong"}""").first).isEqualTo(400)
        assertThat(call(member, HttpMethod.POST, "/$conversationId/tool-results", """{"callId":" ","status":"ok"}""").first).isEqualTo(400)
        assertThat(call(member, HttpMethod.POST, "/$conversationId/tool-results", """{"callId":"c","status":"error","error":"$tooLong"}""").first).isEqualTo(400)
        assertThat(roles(member, conversationId)).containsExactly("USER", "ASSISTANT")
        assertThat(statusOf(member, conversationId)).isEqualTo("IDLE")
    }

    @Test
    fun `llm_usage 는 PROJECT_AGENT service 를 받는다`(): Unit = runBlocking {
        databaseClient.sql(
            """
            INSERT INTO llm_usage (service, reference_id, provider, model, input_tokens, output_tokens, latency_ms, called_at)
            VALUES ('PROJECT_AGENT', 1, 'openai', 'gpt', 10, 5, 100, now())
            """.trimIndent()
        ).fetch().rowsUpdated().block()
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("조건이 시간 안에 충족되지 않았다")
    }
}
