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
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunMessageRepository
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.dto.CreateScenarioResponse
import kr.artel.orchestration.testscenario.dto.MessageResponse
import kr.artel.orchestration.testscenario.dto.ScenarioListResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
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
 * TestScenario 파이프라인 통합 테스트: 인증(JWT) → 런 생성 → 작성 챗봇 SSE →
 * Agent 세션 오픈(POST /sessions) + WS(/sessions/{id}) 왕복 → SSE 중계.
 *
 * 작성 챗봇 대화는 **런 단위**다(ARTEL-206 Step 6): 세션/SSE/채팅이 (userId, runId)로 스코프된다.
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
    private lateinit var runMessageRepository: TestRunMessageRepository

    @Autowired
    private lateinit var runRepository: TestRunRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var projectMemberRepository: ProjectMemberRepository

    @Autowired
    private lateinit var gameInstanceRepository: GameInstanceRepository

    @Autowired
    private lateinit var qaTryRepository: QaTryRepository

    /**
     * 이 스위트가 남긴 행을 치운다.
     *
     * 없으면 만들어진 `qa_try` 가 스위트 끝까지 살아남아, **뒤에 도는 다른 클래스의**
     * `DELETE FROM app_user` · `DELETE FROM project` 가 `qa_try_started_by_fkey` ·
     * `qa_try_game_instance_id_fkey` 로 막힌다. 실패가 이 파일이 아니라 남의 파일에서 나므로
     * 원인을 찾기 어렵고, 클래스 실행 순서가 바뀔 때마다 피해자가 달라진다.
     *
     * 리액티브 트랜잭션은 롤백되지 않고 DB 를 공유하므로 FK 순서대로 직접 비운다.
     */
    @AfterEach
    fun cleanRuntimeRows(): Unit = runBlocking {
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
    }

    private fun webClient() = WebClient.create("http://localhost:$port")

    private val sseType = object : ParameterizedTypeReference<ServerSentEvent<ScenarioStreamEvent>>() {}

    companion object {
        private lateinit var mockAgent: DisposableServer
        private const val MOCK_SESSION_ID = "mock-sid-1"
        private const val RESULT_JSON =
            """{"type":"result","message":"ok","scenarios":[{"title":"튜토리얼 시나리오","description":"d","steps":[]}]}"""

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

    private suspend fun createRun(projectId: Long): Long =
        runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

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
        client: WebClient, projectId: Long, runId: Long, token: String,
        onEvent: (ServerSentEvent<ScenarioStreamEvent>) -> Unit
    ) = client.get()
        .uri("/api/projects/$projectId/test-runs/$runId/chat/stream")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .cookie("artel_access_token", token)
        .retrieve()
        .bodyToFlux(sseType)
        .doOnNext(onEvent)
        .subscribe()

    private fun postMessage(client: WebClient, projectId: Long, runId: Long, token: String, msg: String) {
        client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue("""{"message":"$msg"}""")
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))
    }

    /**
     * 런 첫 입력(세션 오픈) → Agent 결과가 SSE로 전달 → 후속 입력은 WS 턴으로. 채팅은 런 스레드에 저장된다.
     */
    @Test
    fun testCreateOpenSessionRoundTripAndPersist(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("user-${projectIdSeq.incrementAndGet()}")
        val projectId = createMemberProject(appUserId)
        val runId = createRun(projectId)

        // **`result` 만 기다린다.** 저작 단계 표시(ARTEL-419)가 붙은 뒤로 첫 이벤트는 `progress` 다 —
        // 첫 이벤트를 결과로 단정하면 진행 표시를 하나 더 낼 때마다 이 테스트가 깨진다.
        val eventLatch = Sinks.one<ServerSentEvent<ScenarioStreamEvent>>()
        val disposable = subscribeSse(client, projectId, runId, token) {
            if (it.event() == "result") eventLatch.tryEmitValue(it)
        }
        Thread.sleep(1000)

        // 첫 입력 → Agent 세션 오픈(POST /sessions) → WS 연결 시 첫 결과 수신.
        // openRequests는 companion(테스트 간 공유)이라 run 마커를 넣어 이 테스트의 요청만 골라낸다.
        val firstMsg = "튜토리얼 시나리오 만들어줘 run$runId"
        postMessage(client, projectId, runId, token, firstMsg)

        val event = eventLatch.asMono().block(Duration.ofSeconds(5))
        assertThat(event).isNotNull
        assertThat(event?.event()).isEqualTo("result")
        assertThat(event?.data()?.scenarios?.first()?.title).isEqualTo("튜토리얼 시나리오")

        // 세션 오픈 요청에 첫 user_input + run_id가 실렸는지
        Thread.sleep(300)
        val myOpen = openRequests.filter { it.contains("run$runId") }
        assertThat(myOpen).isNotEmpty
        val openNode = objectMapper.readTree(myOpen[0])
        assertThat(openNode.get("user_input").asText()).contains("튜토리얼")
        assertThat(openNode.get("run_id").asLong()).isEqualTo(runId)
        // locale 미설정 사용자는 en으로 전달된다(계정에 locale을 고른 적 없음).
        assertThat(openNode.get("locale").asText()).isEqualTo("en")
        // 설정하지 않은 모델은 보내지 않는다. 모델 카탈로그를 소유한 Agent가 기본값을 정한다.
        assertThat(openNode.has("model")).isFalse()
        // 빈 런으로 열었으니 current_scenarios는 빈 배열로 실린다.
        assertThat(openNode.get("current_scenarios").isArray).isTrue()
        assertThat(openNode.get("current_scenarios")).isEmpty()

        // 후속 입력은 WS 턴으로 전송된다. 첫 결과(RESULT_JSON)가 자동저장(autoApply 기본 true)되어
        // 런에 시나리오 1개가 생겼으므로, 턴의 current_scenarios에 그게 반영돼야 한다.
        postMessage(client, projectId, runId, token, "2단계 더 구체적으로")
        Thread.sleep(500)
        val myTurns = turnMessages.filter { it.contains("2단계 더 구체적으로") }
        assertThat(myTurns).isNotEmpty
        val turnNode = objectMapper.readTree(myTurns[0])
        assertThat(turnNode.get("type").asText()).isEqualTo("turn")
        assertThat(turnNode.get("current_scenarios")).hasSize(1)
        assertThat(turnNode.get("current_scenarios").first().get("title").asText()).isEqualTo("튜토리얼 시나리오")

        // 채팅이 사용자별 프라이빗 스레드(런 단위)로 저장됐는지 (USER/ASSISTANT 구분)
        Thread.sleep(500)
        val messages = runMessageRepository
            .findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList()
        val roles = messages.map { it.role }
        assertThat(roles).contains("USER", "ASSISTANT")
        assertThat(messages.first { it.role == "USER" }.content).contains("튜토리얼")
        assertThat(messages.first { it.role == "ASSISTANT" }.content).isEqualTo("ok")

        // 재방문 조회 엔드포인트 — 런 스코프 사용자 프라이빗 채팅
        val fetched = client.get()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/messages")
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToFlux(MessageResponse::class.java)
            .collectList()
            .block(Duration.ofSeconds(5))!!
        assertThat(fetched.map { it.role }).contains("USER", "ASSISTANT")

        disposable.dispose()
    }

    /**
     * 실시간 자동저장(PUT): Agent를 거치지 않은 순수 canvas 편집이 본문 컬럼으로 저장되고,
     * 응답으로 저장된 본문이 되돌아와 FE가 정합성을 맞출 수 있다. 비참여자는 404.
     */
    @Test
    fun testUpdateAutosavesDraft(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("editor-${projectIdSeq.incrementAndGet()}")
        val projectId = createMemberProject(appUserId)
        val scenarioId = createScenario(client, token, projectId)

        // 사용자가 canvas에서 스텝을 편집(Agent 미경유). 스텝 = 행위 하나(재설계); 검증 스텝은 caseId를 단다.
        val edited = """{"title":"드래그로 편집","description":"reordered","steps":[{"action":"두번째였음"},{"action":"첫번째였음"}]}"""
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
        assertThat(saved.payload.steps.first().action).isEqualTo("두번째였음")

        // DB에도 반영됨 — 제목은 컬럼, 스텝은 JSONB.
        val persisted = scenarioRepository.findById(scenarioId)!!
        assertThat(persisted.title).isEqualTo("드래그로 편집")
        assertThat(persisted.description).isEqualTo("reordered")
        assertThat(persisted.steps.asString()).contains("두번째였음")

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
     * Approve: 최종 draft가 payload로 확정 저장된다. 대화 세션은 런 단위라 시나리오 하나의 승인으로
     * 닫히지 않으며, 채팅 내역과 시나리오는 그대로 남는다.
     */
    @Test
    fun testApproveFinalizesAndKeepsChat(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("approver-${projectIdSeq.incrementAndGet()}")
        val projectId = createMemberProject(appUserId)
        val runId = createRun(projectId)
        val scenarioId = createScenario(client, token, projectId)

        // 런 세션을 열고 채팅을 쌓는다.
        postMessage(client, projectId, runId, token, "튜토리얼 시나리오 만들어줘")
        Thread.sleep(500)
        assertThat(
            runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
                .toList()
        ).isNotEmpty

        // 사용자가 canvas에서 편집한 최종 draft로 승인.
        val finalDraft = """{"title":"최종본","description":"final","steps":[{"action":"로비 진입","case_id":3}]}"""
        approveScenario(client, scenarioId, token, finalDraft)
        Thread.sleep(500)

        // 시나리오는 남고 본문은 최종본으로 저장됨.
        val persisted = scenarioRepository.findById(scenarioId)
        assertThat(persisted).isNotNull
        assertThat(persisted!!.title).isEqualTo("최종본")
        assertThat(persisted.steps.asString()).contains("로비 진입")

        // 채팅 내역은 그대로 남는다(부산물 삭제 없음).
        val remaining = runMessageRepository
            .findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList()
        assertThat(remaining).isNotEmpty
    }

    /**
     * 작성 세션 종료(chat/close): SSE로 `closed` 종료 이벤트가 전달되고, 채팅 내역은 그대로 남는다.
     */
    @Test
    fun testChatCloseEmitsClosedAndKeepsChat(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("closer-${projectIdSeq.incrementAndGet()}")
        val projectId = createMemberProject(appUserId)
        val runId = createRun(projectId)

        val events = CopyOnWriteArrayList<ServerSentEvent<ScenarioStreamEvent>>()
        val disposable = subscribeSse(client, projectId, runId, token) { events.add(it) }
        Thread.sleep(1000)

        postMessage(client, projectId, runId, token, "튜토리얼 시나리오 만들어줘")
        Thread.sleep(500)

        // 세션 종료 → SSE로 closed 이벤트.
        client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/close")
            .cookie("artel_access_token", token)
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))
        Thread.sleep(500)

        assertThat(events.map { it.event() }).contains("closed")

        // 채팅 내역은 그대로 남는다.
        val remaining = runMessageRepository
            .findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList()
        assertThat(remaining).isNotEmpty

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

    private fun deleteScenario(client: WebClient, token: String, testScenarioId: Long, force: Boolean = false): Int {
        val suffix = if (force) "?force=true" else ""
        return client.delete()
            .uri("/api/test-scenario/$testScenarioId$suffix")
            .cookie("artel_access_token", token)
            .exchangeToMono { Mono.just(it.statusCode()) }
            .block(Duration.ofSeconds(5))!!
            .value()
    }

    private fun getScenarioStatus(client: WebClient, token: String, testScenarioId: Long): Int =
        client.get()
            .uri("/api/test-scenario/$testScenarioId")
            .cookie("artel_access_token", token)
            .exchangeToMono { Mono.just(it.statusCode()) }
            .block(Duration.ofSeconds(5))!!
            .value()

    /** 실행 이력이 없는 시나리오는 그냥 삭제된다(204) → 재조회 404. */
    @Test
    fun testDeleteWithoutRunHistorySucceeds(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("del-plain-${projectIdSeq.incrementAndGet()}")
        val projectId = createMemberProject(appUserId)
        val scenarioId = createScenario(client, token, projectId)

        assertThat(deleteScenario(client, token, scenarioId)).isEqualTo(204)
        assertThat(getScenarioStatus(client, token, scenarioId)).isEqualTo(404)
    }

    /**
     * QA 실행 이력(qa_try)이 있는 시나리오는 기본 삭제가 409(scenario_has_qa_history)로 막히고,
     * force=true면 이력까지 지우며 삭제된다(204) → 재조회 404, qa_try도 사라진다(ARTEL-207).
     */
    @Test
    fun testDeleteBlockedByRunHistoryThenForced(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("del-run-${projectIdSeq.incrementAndGet()}")
        val projectId = createMemberProject(appUserId)
        val scenarioId = createScenario(client, token, projectId)

        val now = java.time.Instant.now()
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId, name = "inst", platform = "UNITY",
                createdAt = now, updatedAt = now
            )
        )!!
        // 종료된(active 아닌) 실행 이력 한 건: uk_qa_try_active_instance(STARTING/RUNNING)와 무관.
        qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenarioId, gameInstanceId = instance.id!!,
                startedBy = appUserId, status = "COMPLETED", startedAt = now, completedAt = now
            )
        )

        // 기본 삭제는 실행 이력 때문에 409로 막힌다.
        assertThat(deleteScenario(client, token, scenarioId, force = false)).isEqualTo(409)
        assertThat(getScenarioStatus(client, token, scenarioId)).isEqualTo(200)
        assertThat(qaTryRepository.countByTestScenarioId(scenarioId)).isEqualTo(1L)

        // force면 이력까지 지우고 삭제된다.
        assertThat(deleteScenario(client, token, scenarioId, force = true)).isEqualTo(204)
        assertThat(getScenarioStatus(client, token, scenarioId)).isEqualTo(404)
        assertThat(qaTryRepository.countByTestScenarioId(scenarioId)).isEqualTo(0L)
    }
}
