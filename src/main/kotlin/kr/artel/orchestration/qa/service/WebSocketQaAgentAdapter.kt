package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.artel.orchestration.testscenario.dto.AgentScenario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kr.artel.orchestration.settings.config.AgentServerProperties
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
    @param:JsonProperty("language") val language: String?,
    @param:JsonProperty("prompt_version") val promptVersion: String?,
    val arch: JsonNode?,
    // 사용자 key 로 도는 run 에만 싣는다. NON_NULL 이라 공용 key run 에는 필드가 아예 없다.
    @param:JsonProperty("llm_key") val llmKey: QaSessionLlmKey? = null,
    // 노트가 없는 프로젝트는 필드를 빼지 않고 null 을 싣는다. 이 값은 고를 수 있는 축이 아니라 프로젝트의 사실이라,
    // 덮어쓸 agent-server 기본값이 없다.
    @param:JsonProperty("game_notes") @get:JsonInclude(JsonInclude.Include.ALWAYS) val gameNotes: QaSessionGameNotes? = null,
    val context: QaSessionOpenContext
)

internal data class QaSessionOpenScenario(
    @param:JsonProperty("qa_try_id") val qaTryId: String,
    @param:JsonProperty("test_scenario_id") val testScenarioId: String,
    val scenario: AgentScenario
)

// NON_NULL: 런 단위(scenarios[])와 단일(qa_try_id/…) 중 채운 쪽만 나간다. Agent가 둘 다 받는다.
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class QaSessionOpenContext(
    @param:JsonProperty("game_instance_id") val gameInstanceId: String,
    // Agent 가 이 빌드의 scene context 를 조회하는 데 쓴다 (ARTEL-676). NON_NULL 이라
    // 값이 없으면 필드가 아예 나가지 않고, Agent 는 조회를 건너뛴 채 런을 시작한다.
    @param:JsonProperty("project_id") val projectId: String? = null,
    @param:JsonProperty("game_build_id") val gameBuildId: String? = null,
    @param:JsonProperty("qa_run_id") val qaRunId: String? = null,
    val scenarios: List<QaSessionOpenScenario>? = null,
    // 이 런에 열어 준 cheat · behavior 항목(ARTEL-1053). 비면 필드가 나가지 않는다.
    val cheats: List<JsonNode>? = null,
    val behaviors: List<JsonNode>? = null,
    @param:JsonProperty("qa_try_id") val qaTryId: String? = null,
    @param:JsonProperty("test_scenario_id") val testScenarioId: String? = null,
    val scenario: JsonNode? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class QaSessionOpenResponse(
    @param:JsonProperty("session_id") val sessionId: String,
    // Absent from an Agent that predates reporting it. Null here means "not told",
    // which is why the try is still allowed to start — see QaTryPersistenceService.
    @param:JsonProperty("run_config") val runConfig: JsonNode? = null
)

/**
 * 세션 개설 본문을 만든다.
 *
 * cheat · behavior 는 **고른 목록이 비어 있지 않을 때만** 싣는다(계약 4절). `arch.cheats = "on"` 은 `context.cheats` 가 있을 때만,
 * `arch.behaviors = "on"` 은 `context.behaviors` 가 있을 때만 나가고, 요청이 이미 가진 `arch` 객체에 합쳐진다. 키가 없으면
 * agent-server 는 기본값 `off` 로 읽는다. 목록이 비었는데 요청의 `arch` 가 `cheats` 를 들고 있으면 그 값은 건드리지 않는다.
 */
internal fun QaAgentSessionContext.toOpenRequest(): QaSessionOpenRequest {
    val sentCheats = cheats?.takeIf { it.isNotEmpty() }
    val sentBehaviors = behaviors?.takeIf { it.isNotEmpty() }
    val archNode = if (sentCheats == null && sentBehaviors == null) {
        arch
    } else {
        ((arch as? ObjectNode)?.deepCopy() ?: JsonNodeFactory.instance.objectNode()).apply {
            if (sentCheats != null) put("cheats", "on")
            if (sentBehaviors != null) put("behaviors", "on")
        }
    }
    return QaSessionOpenRequest(
        model = model,
        reasoning = reasoning,
        language = language,
        promptVersion = promptVersion,
        arch = archNode,
        llmKey = llmKey,
        gameNotes = gameNotes,
        context = QaSessionOpenContext(
            gameInstanceId = gameInstanceId,
            projectId = projectId,
            gameBuildId = gameBuildId,
            qaRunId = qaRunId,
            scenarios = scenarios?.map { QaSessionOpenScenario(it.qaTryId, it.testScenarioId, it.scenario) },
            cheats = sentCheats,
            behaviors = sentBehaviors,
            qaTryId = qaTryId,
            testScenarioId = testScenarioId,
            scenario = scenario
        )
    )
}

/**
 * The QA Agent transport stays on Reactor internally: the WebSocket client, its
 * outbound/ready sinks, and the receive pipeline are all Reactor primitives. The
 * [QaAgentPort] surface is `suspend`, so the seam is bridged here — inbound
 * frames and the disconnect callback are driven through `mono { }`, and the HTTP
 * open + ready signal are awaited with `awaitSingle`/`awaitSingleOrNull`.
 */
@Service
class WebSocketQaAgentAdapter(
    properties: AgentServerProperties,
    private val objectMapper: ObjectMapper
) : QaAgentPort {
    private val baseUrl = properties.baseUrl
    private val wsBaseUrl = properties.wsBaseUrl

    private val webClient = WebClient.create()
    private val wsClient = ReactorNettyWebSocketClient()
    private val sessions = ConcurrentHashMap<String, AgentConnection>()

    override suspend fun createSession(
        context: QaAgentSessionContext,
        onMessage: suspend (QaAgentEnvelope) -> Unit,
        onDisconnect: suspend () -> Unit
    ): QaAgentSession {
        val request = context.toOpenRequest()
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
            // 이쪽에서 닫는 것은 지금 끊는 것이다. 남은 프레임을 마저 처리하지 않는다.
            connection.handling?.dispose()
        }
    }

    private suspend fun openWebSocket(
        sessionId: String,
        onMessage: suspend (QaAgentEnvelope) -> Unit,
        onDisconnect: suspend () -> Unit
    ) {
        val ready = Sinks.one<Void>()
        val outbound = Sinks.many().unicast().onBackpressureBuffer<String>()
        val inbound = Sinks.many().unicast().onBackpressureBuffer<String>()
        val connection = AgentConnection(outbound)
        if (sessions.putIfAbsent(sessionId, connection) != null) {
            throw IllegalStateException("Duplicate QA Agent session: $sessionId")
        }

        // 받은 프레임의 처리는 소켓과 따로 돈다(ARTEL-1176). agent 는 런이 끝나면 종료 STATUS 를 보내고 곧바로
        // 닫는다. 그때 이쪽이 보내던 판독 중계가 닫힌 소켓에 쓰다 실패하면 reactor-netty 가 연결 handler 를
        // 취소하고, 처리가 그 안에 있으면 이미 받아 두고 아직 처리하지 못한 판정과 종료 STATUS 가 같이 버려진다.
        // stage 런 157 이 그렇게 끝났다 — 판정 101개 중 100개만 남고 런은 `onDisconnect` 로 실패 처리됐다.
        // 그래서 소켓은 받은 글자를 `inbound` 에 넣기만 하고, 끝나면 그것을 닫는다. 처리는 남은 것을 다 돈 뒤에
        // 끝나고, `onDisconnect` 도 그때 부른다.
        connection.handling = inbound.asFlux()
            .concatMap { payload ->
                mono {
                    val envelope = objectMapper.readValue(payload, QaAgentEnvelope::class.java)
                    onMessage(envelope)
                }.then()
                    // An unparseable frame must not terminate the receive chain:
                    // that closes the socket and fails the whole run.
                    .onErrorResume { Mono.empty() }
            }
            .doFinally {
                sessions.remove(sessionId, connection)
                if (!connection.closing) {
                    mono { onDisconnect() }.subscribe()
                }
            }
            .subscribe()

        val disposable = wsClient.execute(URI.create("$wsBaseUrl/qa-sessions/$sessionId")) { ws ->
            ready.tryEmitEmpty()
            val send = ws.send(outbound.asFlux().map(ws::textMessage))
            // 글자로 옮기는 것은 여기서 한다. frame 의 buffer 는 이 자리를 지나면 풀린다.
            val receive = ws.receive()
                .doOnNext { frame -> inbound.tryEmitNext(frame.payloadAsText) }
                .then()
            send.and(receive)
        }.doFinally {
            inbound.tryEmitComplete()
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
        @Volatile var handling: Disposable? = null,
        @Volatile var closing: Boolean = false
    )
}
