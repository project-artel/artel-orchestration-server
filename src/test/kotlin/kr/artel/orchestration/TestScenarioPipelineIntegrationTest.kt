package kr.artel.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testscenario.dto.CreateScenarioResponse
import kr.artel.orchestration.testscenario.dto.MessageResponse
import kr.artel.orchestration.testscenario.dto.ScenarioListResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.repository.TestScenarioMessageRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * TestScenario 파이프라인 통합 테스트: 인증(JWT) → 시나리오 생성 → SSE →
 * Agent 세션 오픈(POST /sessions) + WS(/sessions/{id}) 왕복 → SSE 중계.
 *
 * 실제 Agent 서버 계약을 흉내내는 목 서버(HTTP POST /sessions + WS /sessions/{id})로 검증한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestScenarioPipelineIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var oauthUserService: OAuthUserService

    @Autowired
    private lateinit var scenarioRepository: TestScenarioRepository

    @Autowired
    private lateinit var messageRepository: TestScenarioMessageRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var projectMemberRepository: ProjectMemberRepository

    private fun webClient() = WebClient.create("http://localhost:$port")

    private val sseType = object : ParameterizedTypeReference<ServerSentEvent<ScenarioStreamEvent>>() {}

    companion object {
        private lateinit var mockAgent: DisposableServer
        private const val MOCK_SESSION_ID = "mock-sid-1"
        private const val RESULT_JSON =
            """{"type":"result","message":"ok","scenarios":[{"title":"튜토리얼 시나리오","description":"d","case_ids":[]}]}"""

        /** Agent가 받은 세션 오픈 요청 본문 */
        private val openRequests = CopyOnWriteArrayList<String>()
        /** Agent가 WS로 받은 턴 메시지 */
        private val turnMessages = CopyOnWriteArrayList<String>()

        private val projectIdSeq = AtomicLong(1000)

        @JvmStatic
        @DynamicPropertySource
        fun registerAgentUrls(registry: DynamicPropertyRegistry) {
            mockAgent = HttpServer.create().port(0).route { routes ->
                // 세션 오픈 (HTTP) — session_id 발급
                routes.post("/sessions") { request, response ->
                    request.receive().aggregate().asString().defaultIfEmpty("").flatMap { body ->
                        openRequests.add(body)
                        response.header("Content-Type", "application/json")
                            .sendString(Mono.just("""{"session_id":"$MOCK_SESSION_ID"}"""))
                            .then()
                    }
                }
                // 턴 진행 (WS) — 연결 시 첫 결과, 이후 각 턴마다 결과
                routes.ws("/sessions/{id}") { inbound, outbound ->
                    outbound.sendString(
                        Flux.concat(
                            Mono.just(RESULT_JSON),
                            inbound.receive().asString().map { turn ->
                                turnMessages.add(turn)
                                RESULT_JSON
                            }
                        )
                    ).then()
                }
            }.bindNow()
            registry.add("artel.agent.base-url") { "http://localhost:${mockAgent.port()}" }
            registry.add("artel.agent.ws-base-url") { "ws://localhost:${mockAgent.port()}" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            mockAgent.disposeNow()
        }
    }

    /** 가상 사용자를 생성하고 (appUserId, JWT)를 반환한다. */
    private suspend fun issueUser(providerUserId: String): Pair<Long, String> {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = "tester-$providerUserId",
                displayName = "Tester",
                avatarUrl = null,
                email = null
            )
        )!!
        return user.userId.toLong() to jwtService.issue(user)
    }

    /** 프로젝트를 만들고 사용자를 참여자(OWNER)로 등록한 뒤 projectId 반환. */
    private suspend fun createMemberProject(appUserId: Long): Long {
        val now = java.time.Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "test-project", genre = "RPG", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = appUserId, role = "OWNER", createdAt = now)
        )
        return project.id!!
    }

    /** 참여자 없이 프로젝트만 생성(비참여자 검증용). */
    private suspend fun createEmptyProject(): Long {
        val now = java.time.Instant.now()
        return projectRepository.save(
            ProjectEntity(name = "no-member", genre = "RPG", createdAt = now, updatedAt = now)
        )!!.id!!
    }

    private fun createScenario(client: WebClient, token: String, projectId: Long): Long {
        val res = client.post()
            .uri("/api/test-scenario")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue("""{"projectId":$projectId}""")
            .retrieve()
            .bodyToMono(CreateScenarioResponse::class.java)
            .block(Duration.ofSeconds(5))
        return res!!.testScenarioId
    }

    private fun subscribeSse(
        client: WebClient, testScenarioId: Long, token: String, onEvent: (ServerSentEvent<ScenarioStreamEvent>) -> Unit
    ) = client.get()
        .uri("/api/test-scenario/$testScenarioId/stream")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .cookie("artel_access_token", token)
        .retrieve()
        .bodyToFlux(sseType)
        .doOnNext(onEvent)
        .subscribe()

    private fun postMessage(
        client: WebClient, testScenarioId: Long, token: String, msg: String, draftJson: String? = null
    ) {
        val body = if (draftJson == null)
            """{"type":"USER_MESSAGE","testScenarioMessage":"$msg"}"""
        else
            """{"type":"USER_MESSAGE","testScenarioMessage":"$msg","draft":$draftJson}"""
        client.post()
            .uri("/api/test-scenario/$testScenarioId/message")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue(body)
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))
    }

    /**
     * 생성 → 첫 입력(세션 오픈) → Agent 결과가 SSE로 전달 + scenario가 DB에 저장 → 후속 입력은 WS 턴으로.
     */
    @Test
    fun testCreateOpenSessionRoundTripAndPersist(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("user-${projectIdSeq.incrementAndGet()}")
        val projectId = createMemberProject(appUserId)
        val scenarioId = createScenario(client, token, projectId)

        val eventLatch = Sinks.one<ServerSentEvent<ScenarioStreamEvent>>()
        val disposable = subscribeSse(client, scenarioId, token) { eventLatch.tryEmitValue(it) }
        Thread.sleep(1000)

        // 첫 입력 → Agent 세션 오픈(POST /sessions) → WS 연결 시 첫 결과 수신
        postMessage(client, scenarioId, token, "튜토리얼 시나리오 만들어줘")

        val event = eventLatch.asMono().block(Duration.ofSeconds(5))
        assertThat(event).isNotNull
        assertThat(event?.event()).isEqualTo("result")
        assertThat(event?.data()?.scenarios?.first()?.title).isEqualTo("튜토리얼 시나리오")

        // 세션 오픈 요청에 첫 user_input이 실렸는지
        Thread.sleep(300)
        val myOpen = openRequests.filter { it.contains("튜토리얼 시나리오 만들어줘") }
        assertThat(myOpen).isNotEmpty
        val openNode = objectMapper.readTree(myOpen[0])
        assertThat(openNode.get("user_input").asText()).contains("튜토리얼")
        // locale 미설정 사용자는 en으로 전달된다(계정에 locale을 고른 적 없음).
        assertThat(openNode.get("locale").asText()).isEqualTo("en")

        // 이 흐름은 run_id 없이 세션을 열었으므로(첫 메시지에 runId 미포함) 결과는 중계만 되고 DB 저장은
        // 건너뛴다. 원본 시나리오 행은 그대로 남는다(빈 payload). scenarios INSERT 반영은 별도 테스트에서
        // run_id와 함께 검증한다(TestScenarioReconcileIntegrationTest).
        Thread.sleep(300)
        assertThat(scenarioRepository.findById(scenarioId)).isNotNull

        // 후속 입력은 WS 턴으로 전송되며, 사용자가 편집한 draft가 함께 실린다
        val draft = """{"title":"편집됨","description":"user edit","steps":[{"step":1,"title":"t","state":"s","action":"a","expected":"e"}]}"""
        postMessage(client, scenarioId, token, "2단계 더 구체적으로", draftJson = draft)
        Thread.sleep(500)
        val myTurns = turnMessages.filter { it.contains("2단계 더 구체적으로") }
        assertThat(myTurns).isNotEmpty
        val turnNode = objectMapper.readTree(myTurns[0])
        assertThat(turnNode.get("type").asText()).isEqualTo("turn")
        assertThat(turnNode.get("draft").get("title").asText()).isEqualTo("편집됨")

        // 채팅이 사용자별 프라이빗 스레드로 저장됐는지 (USER/ASSISTANT 구분)
        Thread.sleep(500)
        val messages = messageRepository
            .findByTestScenarioIdAndAppUserIdOrderByCreatedAtAsc(scenarioId, appUserId)
            .toList()
        val roles = messages.map { it.role }
        assertThat(roles).contains("USER", "ASSISTANT")
        assertThat(messages.first { it.role == "USER" }.content).contains("튜토리얼")
        assertThat(messages.first { it.role == "ASSISTANT" }.content).isEqualTo("ok")

        // 재방문 조회 엔드포인트 — 원본 시나리오 단건(payload는 신규 저작 반영과 무관하게 유지된다)
        val scenario = client.get()
            .uri("/api/test-scenario/$scenarioId")
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToMono(ScenarioResponse::class.java)
            .block(Duration.ofSeconds(5))!!
        assertThat(scenario.testScenarioId).isEqualTo(scenarioId)

        // 재방문 조회 엔드포인트 — 사용자 프라이빗 채팅
        val fetched = client.get()
            .uri("/api/test-scenario/$scenarioId/messages")
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToFlux(MessageResponse::class.java)
            .collectList()
            .block(Duration.ofSeconds(5))!!
        assertThat(fetched.map { it.role }).contains("USER", "ASSISTANT")

        disposable.dispose()
    }

    /**
     * 실시간 자동저장(PUT): Agent를 거치지 않은 순수 canvas 편집이 payload로 저장되고,
     * 응답으로 저장된 payload가 되돌아와 FE가 정합성을 맞출 수 있다. 비참여자는 404.
     */
    @Test
    fun testUpdateAutosavesDraft(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("editor-${projectIdSeq.incrementAndGet()}")
        val projectId = createMemberProject(appUserId)
        val scenarioId = createScenario(client, token, projectId)

        // 사용자가 canvas에서 스텝을 편집(Agent 미경유).
        val edited = """{"title":"드래그로 편집","description":"reordered","steps":[{"step":1,"title":"두번째였음","state":"s","action":"a","expected":"e"},{"step":2,"title":"첫번째였음","state":"s","action":"a","expected":"e"}]}"""
        val saved = client.put()
            .uri("/api/test-scenario/$scenarioId")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue("""{"draft":$edited}""")
            .retrieve()
            .bodyToMono(ScenarioResponse::class.java)
            .block(Duration.ofSeconds(5))!!

        // 응답이 저장된 상태를 반영(정합성 확인용).
        assertThat(saved.payload.title).isEqualTo("드래그로 편집")
        assertThat(saved.payload.steps).hasSize(2)
        assertThat(saved.payload.steps.first().title).isEqualTo("두번째였음")

        // DB에도 반영됨.
        val persisted = scenarioRepository.findById(scenarioId)!!
        assertThat(persisted.payload.asString()).contains("드래그로 편집")

        // 비참여자는 저장 불가(404).
        val (_, outsiderToken) = issueUser("outsider-${projectIdSeq.incrementAndGet()}")
        val status = client.put()
            .uri("/api/test-scenario/$scenarioId")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", outsiderToken)
            .bodyValue("""{"draft":$edited}""")
            .exchangeToMono { Mono.just(it.statusCode().value()) }
            .block(Duration.ofSeconds(5))
        assertThat(status).isEqualTo(404)
    }

    private fun approveScenario(client: WebClient, testScenarioId: Long, token: String, draftJson: String?) {
        val body = if (draftJson == null) "{}" else """{"draft":$draftJson}"""
        client.post()
            .uri("/api/test-scenario/$testScenarioId/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue(body)
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))
    }

    /**
     * Approve: 최종 draft가 payload로 확정 저장되고, 채팅 내역과 시나리오는 그대로 남는다.
     * SSE로는 `closed` 종료 이벤트가 전달된다.
     */
    @Test
    fun testApproveFinalizesAndKeepsChat(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("approver-${projectIdSeq.incrementAndGet()}")
        val projectId = createMemberProject(appUserId)
        val scenarioId = createScenario(client, token, projectId)

        val events = CopyOnWriteArrayList<ServerSentEvent<ScenarioStreamEvent>>()
        val disposable = subscribeSse(client, scenarioId, token) { events.add(it) }
        Thread.sleep(1000)

        // 세션을 열고 채팅을 쌓는다.
        postMessage(client, scenarioId, token, "튜토리얼 시나리오 만들어줘")
        Thread.sleep(500)
        assertThat(
            messageRepository.findByTestScenarioIdAndAppUserIdOrderByCreatedAtAsc(scenarioId, appUserId)
                .toList()
        ).isNotEmpty

        // 사용자가 canvas에서 편집한 최종 draft로 승인.
        val finalDraft = """{"title":"최종본","description":"final","steps":[{"step":1,"title":"t","state":"s","action":"a","expected":"e"}]}"""
        approveScenario(client, scenarioId, token, finalDraft)
        Thread.sleep(500)

        // 시나리오는 남고 payload는 최종본으로 저장됨.
        val persisted = scenarioRepository.findById(scenarioId)
        assertThat(persisted).isNotNull
        assertThat(persisted!!.payload.asString()).contains("최종본")

        // 채팅 내역은 그대로 남는다(부산물 삭제 없음).
        val remaining = messageRepository
            .findByTestScenarioIdAndAppUserIdOrderByCreatedAtAsc(scenarioId, appUserId)
            .toList()
        assertThat(remaining).isNotEmpty

        // SSE로 종료 이벤트가 전달됨.
        assertThat(events.map { it.event() }).contains("closed")

        disposable.dispose()
    }

    /** 비참여자는 approve 404. */
    @Test
    fun testNonMemberCannotApprove(): Unit = runBlocking {
        val client = webClient()
        val (ownerId, ownerToken) = issueUser("owner-${projectIdSeq.incrementAndGet()}")
        val projectId = createMemberProject(ownerId)
        val scenarioId = createScenario(client, ownerToken, projectId)

        val (_, outsiderToken) = issueUser("outsider-${projectIdSeq.incrementAndGet()}")

        val approveStatus = client.post()
            .uri("/api/test-scenario/$scenarioId/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", outsiderToken)
            .bodyValue("{}")
            .exchangeToMono { Mono.just(it.statusCode().value()) }
            .block(Duration.ofSeconds(5))
        assertThat(approveStatus).isEqualTo(404)

        // 원 소유자에겐 시나리오가 그대로 남아있다.
        assertThat(scenarioRepository.findById(scenarioId)).isNotNull
    }

    /**
     * 프로젝트 시나리오 목록/단건 조회: 참여자는 {items:[요약...]} + 단건, 비참여자는 404.
     */
    @Test
    fun testListScenariosByProject(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("lister-${projectIdSeq.incrementAndGet()}")
        val projectId = createMemberProject(appUserId)
        val first = createScenario(client, token, projectId)
        val second = createScenario(client, token, projectId)

        val list = client.get()
            .uri("/api/projects/$projectId/test-scenario")
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToMono(ScenarioListResponse::class.java)
            .block(Duration.ofSeconds(5))!!
        assertThat(list.items.map { it.testScenarioId }).contains(first, second)

        // 프로젝트 스코프 단건 조회.
        val one = client.get()
            .uri("/api/projects/$projectId/test-scenario/$first")
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToMono(ScenarioResponse::class.java)
            .block(Duration.ofSeconds(5))!!
        assertThat(one.testScenarioId).isEqualTo(first)

        // 비참여자는 목록 404.
        val (_, outsiderToken) = issueUser("outsider-${projectIdSeq.incrementAndGet()}")
        val status = client.get()
            .uri("/api/projects/$projectId/test-scenario")
            .cookie("artel_access_token", outsiderToken)
            .exchangeToMono { Mono.just(it.statusCode().value()) }
            .block(Duration.ofSeconds(5))
        assertThat(status).isEqualTo(404)
    }

    /** 인증 없이 접근하면 401. */
    @Test
    fun testUnauthenticatedIsRejected(): Unit = runBlocking {
        val client = webClient()
        val status = client.post()
            .uri("/api/test-scenario")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"projectId":1}""")
            .exchangeToMono { Mono.just(it.statusCode()) }
            .block(Duration.ofSeconds(5))

        assertThat(status?.value()).isEqualTo(401)
    }

    /** 인증은 됐지만 프로젝트 참여자가 아니면 생성은 404(존재하지 않는 것처럼). */
    @Test
    fun testNonMemberCannotCreate(): Unit = runBlocking {
        val client = webClient()
        val (_, token) = issueUser("outsider-${projectIdSeq.incrementAndGet()}")
        val otherProjectId = createEmptyProject()  // 이 사용자는 참여자가 아님

        val status = client.post()
            .uri("/api/test-scenario")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue("""{"projectId":$otherProjectId}""")
            .exchangeToMono { Mono.just(it.statusCode()) }
            .block(Duration.ofSeconds(5))

        assertThat(status?.value()).isEqualTo(404)
    }
}
