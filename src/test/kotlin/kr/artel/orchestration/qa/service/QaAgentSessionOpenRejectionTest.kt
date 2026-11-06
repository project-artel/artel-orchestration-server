package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.config.ApiExceptionHandler
import kr.artel.orchestration.settings.config.AgentServerProperties
import kr.artel.orchestration.telemetry.service.TelemetryErrorRecorder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.net.ServerSocket

/**
 * agent-server 가 세션 개설을 거절했을 때의 응답(ARTEL-1236).
 *
 * 2026-10-22 에 저장된 arch 의 `"vision": true` 를 agent-server 가 422 로 거절했는데, orchestration 은 본문을 버리고
 * 503 `upstream_unavailable` 로 답했다. 사용자는 agent-server 가 죽은 것으로 읽었다. 4xx 는 그 status 와 `detail` 을
 * 넘기고, 503 은 연결 실패와 5xx 에만 남는다.
 */
class QaAgentSessionOpenRejectionTest {
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private var agent: DisposableServer? = null

    @AfterEach
    fun stopAgent() {
        agent?.disposeNow()
    }

    @Test
    fun `422 from agent-server becomes a 422 rejection carrying the field and reason`(): Unit = runBlocking {
        val adapter = adapterAnswering(422, VISION_REJECTION)

        val thrown = runCatching { adapter.createSession(context(), {}, {}) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(QaAgentRejectedException::class.java)
        val rejection = thrown as QaAgentRejectedException
        assertThat(rejection.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(rejection.code).isEqualTo("agent_rejected_request")
        assertThat(rejection.responseFields)
            .containsExactlyEntriesOf(mapOf("arch.vision" to "Input should be 'auto', 'on' or 'off'"))
        assertThat(rejection.message).contains("arch.vision: Input should be 'auto', 'on' or 'off'")
        // `input` 에 실린 요청 본문은 옮기지 않는다.
        assertThat(rejection.message).doesNotContain("**********")
    }

    @Test
    fun `a 4xx with a string detail keeps the status and the detail`(): Unit = runBlocking {
        val adapter = adapterAnswering(400, """{"detail":"unknown prompt_version: v99"}""")

        val thrown = runCatching { adapter.createSession(context(), {}, {}) }.exceptionOrNull()

        val rejection = thrown as QaAgentRejectedException
        assertThat(rejection.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(rejection.responseFields).isEmpty()
        assertThat(rejection.message).endsWith("unknown prompt_version: v99")
    }

    @Test
    fun `5xx from agent-server stays an unavailable agent`(): Unit = runBlocking {
        val adapter = adapterAnswering(503, """{"detail":"model catalog unavailable"}""")

        val thrown = runCatching { adapter.createSession(context(), {}, {}) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(QaAgentUnavailableException::class.java)
    }

    @Test
    fun `a refused connection stays an unavailable agent`(): Unit = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val adapter = WebSocketQaAgentAdapter(
            AgentServerProperties(baseUrl = "http://localhost:$closedPort", wsBaseUrl = "ws://localhost:$closedPort"),
            objectMapper
        )

        val thrown = runCatching { adapter.createSession(context(), {}, {}) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(QaAgentUnavailableException::class.java)
    }

    @Test
    fun `the API answers the rejection with the agent status, its reason and the field`() {
        val handler = ApiExceptionHandler(Mockito.mock(TelemetryErrorRecorder::class.java))

        val response = handler.handleApi(agentRejection(422, VISION_REJECTION))

        assertThat(response.statusCode.value()).isEqualTo(422)
        assertThat(response.body!!.code).isEqualTo("agent_rejected_request")
        assertThat(response.body!!.message).contains("arch.vision")
        assertThat(response.body!!.fields).containsKey("arch.vision")
    }

    @Test
    fun `an unreadable body still keeps the 4xx status`() {
        val rejection = agentRejection(422, "<html>bad gateway</html>")

        assertThat(rejection.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(rejection.responseFields).isEmpty()
        assertThat(rejection.message).isEqualTo("agent-server 가 run 설정을 거절했습니다.")
    }

    private fun adapterAnswering(status: Int, body: String): WebSocketQaAgentAdapter {
        agent = HttpServer.create().port(0).route { routes ->
            routes.post("/qa-sessions") { _, response ->
                response.status(status)
                    .header("Content-Type", "application/json")
                    .sendString(Mono.just(body))
                    .then()
            }
        }.bindNow()
        val port = agent!!.port()
        return WebSocketQaAgentAdapter(
            AgentServerProperties(baseUrl = "http://localhost:$port", wsBaseUrl = "ws://localhost:$port"),
            objectMapper
        )
    }

    private fun context() = QaAgentSessionContext(gameInstanceId = "1", model = null)

    private companion object {
        /** agent-server `_validation_error_without_api_keys` 가 `"vision": true` 에 답한 본문과 같은 모양이다. */
        const val VISION_REJECTION =
            """{"detail":[{"type":"literal_error","loc":["body","arch","vision"],""" +
                """"msg":"Input should be 'auto', 'on' or 'off'",""" +
                """"input":{"vision":true,"llm_key":{"api_key":"**********"}},""" +
                """"ctx":{"expected":"'auto', 'on' or 'off'"}}]}"""
    }
}
