package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.util.Collections

/**
 * agent 가 마지막 프레임들을 몰아 보낸 뒤 곧바로 소켓을 닫는 경우(ARTEL-1176).
 *
 * agent-server 는 런이 끝나면 종료 STATUS 를 보내고 바로 닫는다. FREE 시나리오는 끝에 남은 case 의 판정을
 * 한꺼번에 보내므로, 닫힐 때 이쪽에는 아직 처리하지 못한 프레임이 쌓여 있다. stage 런 157 에서 그 프레임들과
 * 종료 STATUS 가 버려지고 `onDisconnect` 가 런을 실패로 닫았다.
 */
class WebSocketQaAgentAdapterTest {
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private var agent: DisposableServer? = null

    @AfterEach
    fun stopAgent() {
        agent?.disposeNow()
    }

    @Test
    fun `every frame sent before the agent closes is handled before onDisconnect`(): Unit = runBlocking {
        val frames = (1..FRAME_COUNT).map(::frame)
        agent = HttpServer.create().port(0).route { routes ->
            routes.post("/qa-sessions") { _, response ->
                response.header("Content-Type", "application/json")
                    .sendString(Mono.just("""{"session_id":"$SESSION_ID"}"""))
                    .then()
            }
            // 다 보내자마자 닫는다. agent-server 의 `qa_session_ws` 가 런 뒤에 하는 일이다.
            routes.ws("/qa-sessions/{id}") { _, outbound ->
                outbound.sendString(Flux.fromIterable(frames)).then()
            }
        }.bindNow()
        val port = agent!!.port()
        val adapter = WebSocketQaAgentAdapter("http://localhost:$port", "ws://localhost:$port", objectMapper)

        val handled = Collections.synchronizedList(mutableListOf<Long>())
        val handledAtDisconnect = CompletableDeferred<Int>()
        adapter.createSession(
            QaAgentSessionContext(gameInstanceId = "1", model = null),
            onMessage = { envelope ->
                // stage 에서 STATUS 하나를 처리하는 데 걸린 시간(판정 기록과 로그 쓰기)과 비슷하게 둔다.
                delay(HANDLING_MILLIS)
                handled.add(envelope.sequence!!)
            },
            onDisconnect = { handledAtDisconnect.complete(handled.size) }
        )

        // 런이 도는 동안 이쪽도 agent 에게 계속 보낸다 — 판독(PULSE) 중계가 그렇다. 닫힌 소켓에 쓰는 일이
        // 생겨야 실제 런과 같다.
        val relaying = launch {
            while (isActive) {
                runCatching { adapter.send(SESSION_ID, relay()) }
                delay(5)
            }
        }
        val count = withTimeout(10_000) { handledAtDisconnect.await() }
        relaying.cancel()

        assertThat(count).isEqualTo(FRAME_COUNT)
        assertThat(handled).containsExactlyElementsOf((1L..FRAME_COUNT).toList())
    }

    /** 이쪽에서 닫는 것은 지금 끊는 것이다. 남은 프레임을 마저 돌지 않고, 끊김으로 치지도 않는다. */
    @Test
    fun `closing from this side stops handling and does not count as a disconnect`(): Unit = runBlocking {
        val frames = (1..FRAME_COUNT).map(::frame)
        agent = HttpServer.create().port(0).route { routes ->
            routes.post("/qa-sessions") { _, response ->
                response.header("Content-Type", "application/json")
                    .sendString(Mono.just("""{"session_id":"$SESSION_ID"}"""))
                    .then()
            }
            routes.ws("/qa-sessions/{id}") { inbound, outbound ->
                outbound.sendString(Flux.fromIterable(frames)).then().then(inbound.receive().then())
            }
        }.bindNow()
        val port = agent!!.port()
        val adapter = WebSocketQaAgentAdapter("http://localhost:$port", "ws://localhost:$port", objectMapper)

        val handled = Collections.synchronizedList(mutableListOf<Long>())
        val first = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        adapter.createSession(
            QaAgentSessionContext(gameInstanceId = "1", model = null),
            onMessage = { envelope ->
                delay(HANDLING_MILLIS)
                handled.add(envelope.sequence!!)
                first.complete(Unit)
            },
            onDisconnect = { disconnected.complete(Unit) }
        )

        withTimeout(10_000) { first.await() }
        adapter.close(SESSION_ID)
        delay(HANDLING_MILLIS * 5)
        val afterClose = handled.size
        delay(HANDLING_MILLIS * 10)

        assertThat(handled.size).isEqualTo(afterClose).isLessThan(FRAME_COUNT)
        assertThat(disconnected.isCompleted).isFalse()
    }

    private fun relay() = QaAgentEnvelope(
        messageId = java.util.UUID.randomUUID().toString(),
        type = "PULSE",
        qaTryId = "1",
        timestamp = java.time.Instant.now(),
        payload = objectMapper.createObjectNode()
    )

    private fun frame(sequence: Int): String =
        """{"messageId":"00000000-0000-0000-0000-${"%012d".format(sequence)}","type":"STATUS",""" +
            """"qaTryId":"1","sequence":$sequence,"timestamp":"2026-10-21T03:46:55Z",""" +
            """"payload":{"status":"FAILED","message":"case $sequence"}}"""

    private companion object {
        const val SESSION_ID = "drain-test"
        const val FRAME_COUNT = 40
        const val HANDLING_MILLIS = 20L
    }
}
