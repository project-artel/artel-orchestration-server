package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
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

// NON_NULL matters here: an axis nobody chose must be absent, not null. The Agent
// applies its own default for a missing field, and sending an explicit null would
// be Orchestration overriding that default with nothing.
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class QaSessionOpenRequest(
    val type: String = "QA",
    val model: String?,
    val reasoning: JsonNode?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("prompt_version") val promptVersion: String?,
    val arch: JsonNode?,
    val context: QaSessionOpenContext
)

internal data class QaSessionOpenScenario(
    @JsonProperty("qa_try_id") val qaTryId: String,
    @JsonProperty("test_scenario_id") val testScenarioId: String,
    val scenario: JsonNode
)

// NON_NULL: 런 단위(scenarios[])와 단일(qa_try_id/…) 중 채운 쪽만 나간다. Agent가 둘 다 받는다.
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class QaSessionOpenContext(
    @JsonProperty("game_instance_id") val gameInstanceId: String,
    @JsonProperty("qa_run_id") val qaRunId: String? = null,
    val scenarios: List<QaSessionOpenScenario>? = null,
    @JsonProperty("qa_try_id") val qaTryId: String? = null,
    @JsonProperty("test_scenario_id") val testScenarioId: String? = null,
    val scenario: JsonNode? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class QaSessionOpenResponse(
    @JsonProperty("session_id") val sessionId: String,
    // Absent from an Agent that predates reporting it. Null here means "not told",
    // which is why the try is still allowed to start — see QaTryPersistenceService.
    @JsonProperty("run_config") val runConfig: JsonNode? = null
)

/**
 * The QA Agent transport stays on Reactor internally: the WebSocket client, its
 * outbound/ready sinks, and the receive pipeline are all Reactor primitives. The
 * [QaAgentPort] surface is `suspend`, so the seam is bridged here — inbound
 * frames and the disconnect callback are driven through `mono { }`, and the HTTP
 * open + ready signal are awaited with `awaitSingle`/`awaitSingleOrNull`.
 */
@Service
class WebSocketQaAgentAdapter(
    @Value("\${artel.agent.base-url:http://localhost:8000}") private val baseUrl: String,
    @Value("\${artel.agent.ws-base-url:ws://localhost:8000}") private val wsBaseUrl: String,
    private val objectMapper: ObjectMapper
) : QaAgentPort {
    private val webClient = WebClient.create()
    private val wsClient = ReactorNettyWebSocketClient()
    private val sessions = ConcurrentHashMap<String, AgentConnection>()

    override suspend fun createSession(
        context: QaAgentSessionContext,
        onMessage: suspend (QaAgentEnvelope) -> Unit,
        onDisconnect: suspend () -> Unit
    ): QaAgentSession {
        val request = QaSessionOpenRequest(
            model = context.model,
            reasoning = context.reasoning,
            language = context.language,
            promptVersion = context.promptVersion,
            arch = context.arch,
            context = QaSessionOpenContext(
                gameInstanceId = context.gameInstanceId,
                qaRunId = context.qaRunId,
                scenarios = context.scenarios?.map {
                    QaSessionOpenScenario(it.qaTryId, it.testScenarioId, it.scenario)
                },
                qaTryId = context.qaTryId,
                testScenarioId = context.testScenarioId,
                scenario = context.scenario
            )
        )
        try {
            val response = webClient.post()
                .uri("$baseUrl/qa-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(QaSessionOpenResponse::class.java)
                .awaitSingle()
            openWebSocket(response.sessionId, onMessage, onDisconnect)
            return QaAgentSession(response.sessionId, response.runConfig)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw if (error is QaAgentUnavailableException) error
            else QaAgentUnavailableException("QA Agent session is unavailable: ${error.message}")
        }
    }

    override suspend fun send(sessionId: String, envelope: QaAgentEnvelope) {
        val json = objectMapper.writeValueAsString(envelope)
        val connection = sessions[sessionId]
            ?: throw QaAgentUnavailableException("QA Agent WebSocket is not connected")
        if (connection.outbound.tryEmitNext(json).isFailure) {
            throw QaAgentUnavailableException("QA Agent WebSocket send queue is closed")
        }
    }

    override suspend fun close(sessionId: String) {
        sessions.remove(sessionId)?.let { connection ->
            connection.closing = true
            connection.outbound.tryEmitComplete()
            connection.disposable?.dispose()
        }
    }

    private suspend fun openWebSocket(
        sessionId: String,
        onMessage: suspend (QaAgentEnvelope) -> Unit,
        onDisconnect: suspend () -> Unit
    ) {
        val ready = Sinks.one<Void>()
        val outbound = Sinks.many().unicast().onBackpressureBuffer<String>()
        val connection = AgentConnection(outbound)
        if (sessions.putIfAbsent(sessionId, connection) != null) {
            throw IllegalStateException("Duplicate QA Agent session: $sessionId")
        }

        val disposable = wsClient.execute(URI.create("$wsBaseUrl/qa-sessions/$sessionId")) { ws ->
            ready.tryEmitEmpty()
            val send = ws.send(outbound.asFlux().map(ws::textMessage))
            val receive = ws.receive()
                .concatMap { frame ->
                    val payload = frame.payloadAsText
                    mono {
                        val envelope = objectMapper.readValue(payload, QaAgentEnvelope::class.java)
                        onMessage(envelope)
                    }.then()
                        // An unparseable frame must not terminate the receive chain:
                        // that closes the socket and fails the whole run.
                        .onErrorResume { Mono.empty() }
                }
                .then()
            send.and(receive)
        }.doFinally {
            sessions.remove(sessionId, connection)
            if (!connection.closing) {
                mono { onDisconnect() }.subscribe()
            }
        }.subscribe(
            {},
            { error -> ready.tryEmitError(error) }
        )
        connection.disposable = disposable
        ready.asMono().awaitSingleOrNull()
    }

    private class AgentConnection(
        val outbound: Sinks.Many<String>,
        @Volatile var disposable: Disposable? = null,
        @Volatile var closing: Boolean = false
    )
}
