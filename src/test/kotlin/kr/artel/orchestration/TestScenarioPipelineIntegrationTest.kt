package kr.artel.orchestration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import reactor.core.publisher.Sinks
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TestScenario 챗봇 파이프라인(leg #2 = Agent WebSocket)의 양방향 릴레이를 검증하는 통합 테스트.
 *
 * Orchestration이 Agent로 여는 WebSocket을, 테스트 내부에 띄운 목(mock) Agent WS 서버로 받는다.
 * 흐름: FE가 SSE 구독 → POST /message → Orch가 Agent WS로 중계 → 목 Agent가 응답 →
 *      Orch가 SSE로 FE에 전달.
 *
 * 테스트 계획: .plan/general/2026-07-21-testscenario-pipeline-test-plan.md
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestScenarioPipelineIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun webClient() = WebClient.create("http://localhost:$port")

    private val sseType = object : ParameterizedTypeReference<ServerSentEvent<JsonNode>>() {}

    companion object {
        private lateinit var mockAgent: DisposableServer

        /** 목 Agent가 수신한 아웃바운드 메시지(원본 JSON)를 기록 — 아웃바운드 검증용 */
        private val recordedMessages = CopyOnWriteArrayList<String>()

        private const val ISSUED_SESSION_ID = "agent-sid-abc123"

        /**
         * 목 Agent WebSocket 서버를 임의 포트로 띄우고, Orchestration이 이 서버로 붙도록
         * `artel.agent.ws-base-url`을 주입한다. 수신 메시지마다 step 응답(agentSessionId 포함)을 돌려준다.
         */
        @JvmStatic
        @DynamicPropertySource
        fun registerAgentWsUrl(registry: DynamicPropertyRegistry) {
            mockAgent = HttpServer.create().port(0).route { routes ->
                routes.ws("/testscenario") { inbound, outbound ->
                    outbound.sendString(
                        inbound.receive().asString().map { msg ->
                            recordedMessages.add(msg)
                            """{"type":"SCENARIO_STEP","agentSessionId":"$ISSUED_SESSION_ID","payload":{"echo":true}}"""
                        }
                    ).then()
                }
            }.bindNow()
            registry.add("artel.agent.ws-base-url") { "ws://localhost:${mockAgent.port()}" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            mockAgent.disposeNow()
        }
    }

    private fun subscribeSse(client: WebClient, clientId: String, onEvent: (ServerSentEvent<JsonNode>) -> Unit) =
        client.get()
            .uri("/api/testscenario/$clientId/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .retrieve()
            .bodyToFlux(sseType)
            .doOnNext(onEvent)
            .subscribe()

    private fun postMessage(client: WebClient, clientId: String, msg: String) {
        client.post()
            .uri("/api/testscenario/$clientId/message")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"type":"USER_MESSAGE","testscenariomsg":"$msg"}""")
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))
    }

    /**
     * 라운드 트립 + 세션 매핑:
     * 1) FE 메시지가 Agent WS로 중계되고, Agent 응답이 FE의 SSE 스트림으로 전달된다.
     * 2) Agent가 발급한 agentSessionId가 매핑되어, 이후 턴의 아웃바운드 요청에 실린다.
     */
    @Test
    fun testRoundTripAndSessionMapping() {
        val client = webClient()
        val clientId = UUID.randomUUID().toString()

        val eventLatch = Sinks.one<ServerSentEvent<JsonNode>>()
        val disposable = subscribeSse(client, clientId) { eventLatch.tryEmitValue(it) }
        Thread.sleep(1000) // SSE 구독 수립 대기

        // 1턴: 메시지 전송 → Agent WS 중계 → Agent 응답이 SSE로 도착
        postMessage(client, clientId, "로그인 시나리오 만들어줘")

        val event = eventLatch.asMono().block(Duration.ofSeconds(5))
        assertThat(event).isNotNull
        assertThat(event?.event()).isEqualTo("SCENARIO_STEP")
        assertThat(event?.data()?.get("echo")?.asBoolean()).isTrue()

        // 이벤트 도착 = agentSessionId 매핑 완료(handleInbound에서 bind 후 emit). 2턴 전송.
        postMessage(client, clientId, "다음 단계")
        Thread.sleep(300) // 목 Agent 기록 대기

        val mine = recordedMessages.filter { it.contains(clientId) }
        assertThat(mine).hasSizeGreaterThanOrEqualTo(2)

        val first = objectMapper.readTree(mine[0])
        assertThat(first.get("agentSessionId").isNull).isTrue() // 첫 턴은 null
        val second = objectMapper.readTree(mine[1])
        assertThat(second.get("agentSessionId").asText()).isEqualTo(ISSUED_SESSION_ID) // 이후 턴은 매핑값

        disposable.dispose()
    }

    /**
     * 스트림 격리: clientA만 메시지를 보내면 clientB의 SSE 스트림은 아무것도 받지 않는다.
     */
    @Test
    fun testStreamIsolationBetweenClients() {
        val client = webClient()
        val clientA = UUID.randomUUID().toString()
        val clientB = UUID.randomUUID().toString()

        val latchA = Sinks.one<ServerSentEvent<JsonNode>>()
        val receivedB = CopyOnWriteArrayList<ServerSentEvent<JsonNode>>()

        val disposableA = subscribeSse(client, clientA) { latchA.tryEmitValue(it) }
        val disposableB = subscribeSse(client, clientB) { receivedB.add(it) }
        Thread.sleep(1000)

        // clientA만 메시지 전송
        postMessage(client, clientA, "A의 시나리오")

        val eventA = latchA.asMono().block(Duration.ofSeconds(5))
        assertThat(eventA).isNotNull
        assertThat(eventA?.data()?.get("echo")?.asBoolean()).isTrue()

        // B는 아무것도 받지 않아야 함
        Thread.sleep(500)
        assertThat(receivedB).isEmpty()

        disposableA.dispose()
        disposableB.dispose()
    }
}
