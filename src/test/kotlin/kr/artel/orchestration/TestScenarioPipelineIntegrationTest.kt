package kr.artel.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.testscenario.dto.CreateScenarioResponse
import kr.artel.orchestration.testscenario.dto.MessageResponse
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
 * Agent 세션 오픈(POST /sessions) + WS(/sessions/{id}) 왕복 → DB UPDATE.
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

    private fun webClient() = WebClient.create("http://localhost:$port")

    private val sseType = object : ParameterizedTypeReference<ServerSentEvent<ScenarioStreamEvent>>() {}

    companion object {
        private lateinit var mockAgent: DisposableServer
        private const val MOCK_SESSION_ID = "mock-sid-1"
        private const val RESULT_JSON =
            """{"type":"result","message":"ok","scenario":{"title":"튜토리얼 시나리오","description":"d","steps":[{"step":1,"title":"t1","state":"s","action":"a","expected":"e"}]}}"""

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
    private fun issueUser(providerUserId: String): Pair<Long, String> {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = "tester-$providerUserId",
                displayName = "Tester",
                avatarUrl = null,
                email = null
            )
        ).block()!!
        return user.userId.toLong() to jwtService.issue(user)
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
    fun testCreateOpenSessionRoundTripAndPersist() {
        val client = webClient()
        val projectId = projectIdSeq.incrementAndGet()
        val (appUserId, token) = issueUser("user-$projectId")
        val scenarioId = createScenario(client, token, projectId)

        val eventLatch = Sinks.one<ServerSentEvent<ScenarioStreamEvent>>()
        val disposable = subscribeSse(client, scenarioId, token) { eventLatch.tryEmitValue(it) }
        Thread.sleep(1000)

        // 첫 입력 → Agent 세션 오픈(POST /sessions) → WS 연결 시 첫 결과 수신
        postMessage(client, scenarioId, token, "튜토리얼 시나리오 만들어줘")

        val event = eventLatch.asMono().block(Duration.ofSeconds(5))
        assertThat(event).isNotNull
        assertThat(event?.event()).isEqualTo("result")
        assertThat(event?.data()?.scenario?.title).isEqualTo("튜토리얼 시나리오")

        // 세션 오픈 요청에 첫 user_input이 실렸는지
        Thread.sleep(300)
        val myOpen = openRequests.filter { it.contains("튜토리얼 시나리오 만들어줘") }
        assertThat(myOpen).isNotEmpty
        assertThat(objectMapper.readTree(myOpen[0]).get("user_input").asText()).contains("튜토리얼")

        // scenario가 DB에 저장(UPDATE)되었는지
        Thread.sleep(300)
        val persisted = scenarioRepository.findById(scenarioId).block()
        assertThat(persisted).isNotNull
        assertThat(persisted!!.payload.asString()).contains("튜토리얼 시나리오")

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
            .collectList().block()!!
        val roles = messages.map { it.role }
        assertThat(roles).contains("USER", "ASSISTANT")
        assertThat(messages.first { it.role == "USER" }.content).contains("튜토리얼")
        assertThat(messages.first { it.role == "ASSISTANT" }.content).isEqualTo("ok")

        // 재방문 조회 엔드포인트 — 시나리오 payload(canvas용)
        val scenario = client.get()
            .uri("/api/test-scenario/$scenarioId")
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToMono(ScenarioResponse::class.java)
            .block(Duration.ofSeconds(5))!!
        assertThat(scenario.payload.title).isEqualTo("튜토리얼 시나리오")
        assertThat(scenario.payload.steps).isNotEmpty

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

    /** 인증 없이 접근하면 401. */
    @Test
    fun testUnauthenticatedIsRejected() {
        val client = webClient()
        val status = client.post()
            .uri("/api/test-scenario")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"projectId":1}""")
            .exchangeToMono { Mono.just(it.statusCode()) }
            .block(Duration.ofSeconds(5))

        assertThat(status?.value()).isEqualTo(401)
    }
}
