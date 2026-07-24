package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.testscenario.dto.AgentSessionOpenRequest
import kr.artel.orchestration.testscenario.dto.AgentSessionOpenResponse
import kr.artel.orchestration.testscenario.dto.AgentTurnMessage
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.entity.TestScenarioMessageEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioMessageRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.slf4j.LoggerFactory
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

/**
 * TestScenario 챗봇의 Agent 서버 연동 서비스. 실제 Agent 서버 계약(FastAPI)에 맞춘다:
 *
 * 1. 세션 오픈: `POST {base}/sessions {user_input, unity_context, game_context, model}` → `{session_id}`
 * 2. WS 연결: `WS {ws-base}/sessions/{session_id}`. 연결 시 Agent가 첫 결과를 보낸다(오픈 때 준 user_input 기반).
 * 3. 후속 턴: WS로 `{type:"turn", user_input, draft?, model?}` 전송.
 * 4. 결과 수신: `{type:"result", message, scenario}` → SSE 중계 + scenario를 test_scenario에 저장(UPDATE).
 *
 * 세션 키(`sessionKey` = userId:testScenarioId)로 Agent 세션(session_id + WS)을 식별한다.
 * 오간 채팅 메시지는 사용자별 프라이빗 스레드로 test_scenario_message에 저장한다(USER/ASSISTANT).
 */
@Service
class TestScenarioAgentService(
    @Value("\${artel.agent.base-url:http://localhost:8000}") private val agentBaseUrl: String,
    @Value("\${artel.agent.ws-base-url:ws://localhost:8000}") private val agentWsBaseUrl: String,
    @Value("\${artel.agent.model:openai/gpt-4o-mini}") private val defaultModel: String,
    private val objectMapper: ObjectMapper,
    private val streamManager: TestScenarioStreamManager,
    private val scenarioRepository: TestScenarioRepository,
    private val messageRepository: TestScenarioMessageRepository,
    private val buildRepository: GameBuildRepository
) {
    private val logger = LoggerFactory.getLogger(TestScenarioAgentService::class.java)
    private val webClient = WebClient.create()
    private val wsClient = ReactorNettyWebSocketClient()
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    /**
     * 사용자 입력을 Agent로 전달한다. 세션이 없으면 오픈(POST /sessions + WS)하고, 있으면 WS 턴으로 보낸다.
     * 첫 입력은 세션 오픈에 실려 처리되므로 별도 턴을 보내지 않는다(연결 시 Agent가 첫 결과를 반환).
     * `draft`는 사용자가 편집한 현재 시나리오로, 후속 턴에서 Agent에 전달된다(첫 입력에는 적용되지 않음).
     * 사용자 입력은 USER 메시지로 채팅 스레드에 저장된다.
     */
    fun sendMessage(
        sessionKey: String,
        testScenarioId: Long,
        projectId: Long,
        appUserId: Long,
        userInput: String,
        draft: ScenarioDraft? = null
    ): Mono<Void> {
        val existing = sessions[sessionKey]
        val send = if (existing != null) sendTurn(sessionKey, existing, userInput, draft)
        else openSession(sessionKey, testScenarioId, projectId, appUserId, userInput)
        return saveMessage(testScenarioId, appUserId, "USER", userInput).then(send)
    }

    /** 세션의 Agent 커넥션을 닫는다(세션 종료 시). */
    fun closeConnection(sessionKey: String) {
        sessions.remove(sessionKey)?.disposable?.dispose()
    }

    private fun openSession(
        sessionKey: String,
        testScenarioId: Long,
        projectId: Long,
        appUserId: Long,
        userInput: String
    ): Mono<Void> {
        logger.info("Agent 세션 오픈 시도 [sessionKey=$sessionKey, url=$agentBaseUrl/sessions]")
        return gameContext(projectId, appUserId)
            .map { context ->
                AgentSessionOpenRequest(userInput = userInput, gameContext = context, model = defaultModel)
            }
            .flatMap { body ->
                webClient.post()
                    .uri("$agentBaseUrl/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(AgentSessionOpenResponse::class.java)
            }
            .doOnNext { resp -> openWebSocket(sessionKey, testScenarioId, appUserId, resp.sessionId) }
            .then()
    }

    /**
     * 프로젝트의 가장 최근 씬 스캔을 game_context로 만든다. SDK가 등록 때 보고한 빌드의 UI 구성이며,
     * Agent가 어떤 화면을 대상으로 시나리오를 짜는지 참조한다.
     *
     * 최신 빌드가 스캔 없이 등록됐을 수도 있어(구버전 SDK), 스캔을 가진 가장 최근 빌드를 고른다.
     * 그런 빌드가 하나도 없으면 빈 맵이라 기존과 동일하게 빈 game_context를 보낸다.
     */
    private fun gameContext(projectId: Long, appUserId: Long): Mono<Map<String, Any>> =
        buildRepository.findAllForMember(projectId, appUserId)
            .filter { it.sceneScan != null }
            .next()
            .map { build ->
                objectMapper.readValue(
                    build.sceneScan!!.asString(),
                    object : TypeReference<Map<String, Any>>() {}
                )
            }
            .defaultIfEmpty(emptyMap())

    private fun sendTurn(
        sessionKey: String,
        session: AgentSession,
        userInput: String,
        draft: ScenarioDraft?
    ): Mono<Void> {
        return Mono.fromCallable {
            val json = objectMapper.writeValueAsString(AgentTurnMessage(userInput = userInput, draft = draft))
            val result = session.outbound.tryEmitNext(json)
            if (result.isFailure) {
                throw IllegalStateException("Agent WS 턴 전송 실패 [sessionKey=$sessionKey, result=$result]")
            }
        }.then()
    }

    private fun openWebSocket(
        sessionKey: String,
        testScenarioId: Long,
        appUserId: Long,
        agentSessionId: String
    ) {
        val outbound = Sinks.many().unicast().onBackpressureBuffer<String>()
        val session = AgentSession(outbound, testScenarioId, appUserId, agentSessionId)
        sessions[sessionKey] = session

        val uri = URI.create("$agentWsBaseUrl/sessions/$agentSessionId")
        logger.info("Agent WS 연결 시도 [sessionKey=$sessionKey, uri=$uri]")

        val sessionMono = wsClient.execute(uri) { ws ->
            val send = ws.send(outbound.asFlux().map(ws::textMessage))
            val receive = ws.receive()
                .doOnNext { message -> handleInbound(sessionKey, message.payloadAsText) }
                .then()
            send.and(receive)
        }.doFinally {
            sessions.remove(sessionKey)
            logger.info("Agent WS 연결 종료 및 정리 [sessionKey=$sessionKey]")
        }

        session.disposable = sessionMono.subscribe(
            null,
            { error -> logger.error("Agent WS 세션 에러 [sessionKey=$sessionKey]: ${error.message}") }
        )
    }

    private fun handleInbound(sessionKey: String, payloadText: String) {
        try {
            // Agent 응답을 타입화한 봉투로 파싱해 SSE로 중계한다(type=result|error).
            val event = objectMapper.readValue(payloadText, ScenarioStreamEvent::class.java)
            streamManager.emit(sessionKey, event)

            val session = sessions[sessionKey]
            if (event.type == "result" && session != null) {
                // Agent 메시지를 ASSISTANT 채팅으로 저장.
                saveMessage(session.testScenarioId, session.appUserId, "ASSISTANT", event.message ?: "")
                    .subscribe({}, { err -> logger.error("ASSISTANT 메시지 저장 실패 [sessionKey=$sessionKey]: ${err.message}") })

                // scenario를 test_scenario에 저장(UPDATE).
                event.scenario?.let { persistScenario(sessionKey, session.testScenarioId, it) }
            }
        } catch (e: Exception) {
            logger.error("Agent WS 수신 메시지 처리 실패 [sessionKey=$sessionKey]: ${e.message}", e)
        }
    }

    /** 채팅 메시지를 저장한다(사용자별 프라이빗 스레드). */
    private fun saveMessage(testScenarioId: Long, appUserId: Long, role: String, content: String): Mono<Void> {
        return messageRepository.save(
            TestScenarioMessageEntity(
                testScenarioId = testScenarioId,
                appUserId = appUserId,
                role = role,
                content = content
            )
        ).then()
    }

    /** scenario를 JSONB로 직렬화해 testScenarioId로 UPDATE한다. 시나리오는 세션 전에 이미 생성돼 있다. */
    private fun persistScenario(sessionKey: String, testScenarioId: Long, scenario: ScenarioDraft) {
        val payloadJson = Json.of(objectMapper.writeValueAsString(scenario))
        scenarioRepository.findById(testScenarioId)
            .flatMap { existing -> scenarioRepository.save(existing.copy(payload = payloadJson)) }
            .subscribe(
                { logger.info("시나리오 저장 완료 [sessionKey=$sessionKey, id=${it.id}]") },
                { err -> logger.error("시나리오 저장 실패 [sessionKey=$sessionKey]: ${err.message}") }
            )
    }

    /** Agent 세션 상태: 송신 싱크, 저장 대상 시나리오 id, 채팅 소유자 appUserId, Agent가 발급한 session_id. */
    private class AgentSession(
        val outbound: Sinks.Many<String>,
        val testScenarioId: Long,
        val appUserId: Long,
        val agentSessionId: String,
        @Volatile var disposable: Disposable? = null
    )
}
