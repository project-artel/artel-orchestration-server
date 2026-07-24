package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

private data class QaSessionOpenRequest(
    val type: String = "QA",
    val model: String,
    val context: QaSessionOpenContext
)

private data class QaSessionOpenContext(
    @JsonProperty("qa_try_id") val qaTryId: String,
    @JsonProperty("game_instance_id") val gameInstanceId: String,
    @JsonProperty("test_scenario_id") val testScenarioId: String,
    val scenario: JsonNode
)

private data class QaSessionOpenResponse(
    @JsonProperty("session_id") val sessionId: String
)

@Service
class WebSocketQaAgentAdapter(
    @Value("\${artel.agent.base-url:http://localhost:8000}") private val baseUrl: String,
    @Value("\${artel.agent.ws-base-url:ws://localhost:8000}") private val wsBaseUrl: String,
    @Value("\${artel.agent.model:openai/gpt-4o-mini}") private val model: String,
    private val objectMapper: ObjectMapper
) : QaAgentPort {
    private val webClient = WebClient.create()
    private val wsClient = ReactorNettyWebSocketClient()
    private val sessions = ConcurrentHashMap<String, AgentConnection>()

    override fun createSession(
        context: QaAgentSessionContext,
        onMessage: (QaAgentEnvelope) -> Mono<Void>,
        onDisconnect: () -> Mono<Void>
    ): Mono<QaAgentSession> {
        val request = QaSessionOpenRequest(
            model = model,
            context = QaSessionOpenContext(
                qaTryId = context.qaTryId,
                gameInstanceId = context.gameInstanceId,
                testScenarioId = context.testScenarioId,
                scenario = context.scenario
            )
        )
        return webClient.post()
            .uri("$baseUrl/qa-sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(QaSessionOpenResponse::class.java)
            .flatMap { response ->
                openWebSocket(response.sessionId, onMessage, onDisconnect)
                    .thenReturn(QaAgentSession(response.sessionId))
            }
            .onErrorMap { error ->
                if (error is QaAgentUnavailableException) error
                else QaAgentUnavailableException("QA Agent session is unavailable: ${error.message}")
            }
    }

    override fun send(sessionId: String, envelope: QaAgentEnvelope): Mono<Void> =
        Mono.fromCallable { objectMapper.writeValueAsString(envelope) }
            .flatMap { json ->
                val connection = sessions[sessionId]
                    ?: return@flatMap Mono.error(QaAgentUnavailableException("QA Agent WebSocket is not connected"))
                if (connection.outbound.tryEmitNext(json).isFailure) {
                    Mono.error(QaAgentUnavailableException("QA Agent WebSocket send queue is closed"))
                } else {
                    Mono.empty()
                }
            }

    override fun close(sessionId: String): Mono<Void> =
        Mono.fromRunnable {
            sessions.remove(sessionId)?.let { connection ->
                connection.closing = true
                connection.outbound.tryEmitComplete()
                connection.disposable?.dispose()
            }
        }

    private fun openWebSocket(
        sessionId: String,
        onMessage: (QaAgentEnvelope) -> Mono<Void>,
        onDisconnect: () -> Mono<Void>
    ): Mono<Void> {
        val ready = Sinks.one<Void>()
        val outbound = Sinks.many().unicast().onBackpressureBuffer<String>()
        val connection = AgentConnection(outbound)
        if (sessions.putIfAbsent(sessionId, connection) != null) {
            return Mono.error(IllegalStateException("Duplicate QA Agent session: $sessionId"))
        }

        val disposable = wsClient.execute(URI.create("$wsBaseUrl/qa-sessions/$sessionId")) { ws ->
            ready.tryEmitEmpty()
            val send = ws.send(outbound.asFlux().map(ws::textMessage))
            val receive = ws.receive()
                .concatMap { frame ->
                    Mono.fromCallable {
                        objectMapper.readValue(frame.payloadAsText, QaAgentEnvelope::class.java)
                    }.flatMap(onMessage)
                }
                .then()
            send.and(receive)
        }.doFinally {
            sessions.remove(sessionId, connection)
            if (!connection.closing) {
                onDisconnect().subscribe()
            }
        }.subscribe(
            {},
            { error -> ready.tryEmitError(error) }
        )
        connection.disposable = disposable
        return ready.asMono()
    }

    private class AgentConnection(
        val outbound: Sinks.Many<String>,
        @Volatile var disposable: Disposable? = null,
        @Volatile var closing: Boolean = false
    )
}
