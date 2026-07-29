package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.testscenario.dto.AgentCloseMessage
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
import reactor.core.publisher.Sinks
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * TestScenario 챗봇의 Agent 서버 연동 서비스(코루틴). 실제 Agent 서버 계약(FastAPI)에 맞춘다:
 *
 * 1. 세션 오픈: `POST {base}/sessions {user_input, unity_context, game_context, model}` → `{session_id}`
 * 2. WS 연결: `WS {ws-base}/sessions/{session_id}`. 연결 시 Agent가 첫 결과를 보낸다(오픈 때 준 user_input 기반).
 * 3. 후속 턴: WS로 `{type:"turn", user_input, draft?, model?}` 전송.
 * 4. 결과 수신: `{type:"result", message, scenario}` → SSE 중계 + scenario를 test_scenario에 저장(UPDATE).
 *
 * 세션 키(`sessionKey` = userId:testScenarioId)로 Agent 세션(session_id + WS)을 식별한다.
 * 오간 채팅 메시지는 사용자별 프라이빗 스레드로 test_scenario_message에 저장한다(USER/ASSISTANT).
 *
 * WS 클라이언트는 [ReactorNettyWebSocketClient](Reactor)를 그대로 쓴다(코틀린 코루틴 WS 클라이언트 대체 없음).
 * WS 송신 싱크(`outbound`)는 Reactor [Sinks]로 유지하고, WS 수신 콜백 내부의 DB 저장만 [scope]로 코루틴 브리지한다.
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
    private val buildRepository: GameBuildRepository,
    private val appUserRepository: AppUserRepository
) {
    private val logger = LoggerFactory.getLogger(TestScenarioAgentService::class.java)
    private val webClient = WebClient.create()
    private val wsClient = ReactorNettyWebSocketClient()
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    // WS 수신 콜백(Reactor 컨텍스트)에서 발생하는 DB 저장을 fire-and-forget 코루틴으로 흘리기 위한 스코프.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 사용자 입력을 Agent로 전달한다. 세션이 없으면 오픈(POST /sessions + WS)하고, 있으면 WS 턴으로 보낸다.
     * 첫 입력은 세션 오픈에 실려 처리되므로 별도 턴을 보내지 않는다(연결 시 Agent가 첫 결과를 반환).
     * `draft`는 사용자가 편집한 현재 시나리오로, 후속 턴에서 Agent에 전달된다(첫 입력에는 적용되지 않음).
     * 사용자 입력은 USER 메시지로 채팅 스레드에 저장된다.
     */
    suspend fun sendMessage(
        sessionKey: String,
        testScenarioId: Long,
        projectId: Long,
        appUserId: Long,
        userInput: String,
        draft: ScenarioDraft? = null
    ) {
        // 원래 순서대로: 먼저 USER 메시지를 저장한 뒤 Agent로 보낸다.
        saveMessage(testScenarioId, appUserId, "USER", userInput)
        val existing = sessions[sessionKey]
        if (existing != null) {
            sendTurn(sessionKey, existing, userInput, draft)
        } else {
            openSession(sessionKey, testScenarioId, projectId, appUserId, userInput)
        }
    }

    /**
     * Agent 세션을 종료한다(Approve/Delete 시). Agent에 `{type:"close"}`를 통보해 WS와 session_id(Redis)를
     * 만료시키도록 하고, 우리 쪽 WS 구독도 정리한다. 세션이 없으면(이미 종료) 조용히 무시한다.
     */
    fun closeSession(sessionKey: String) {
        val session = sessions.remove(sessionKey) ?: return
        try {
            val json = objectMapper.writeValueAsString(AgentCloseMessage())
            session.outbound.tryEmitNext(json)
            session.outbound.tryEmitComplete()
        } catch (e: Exception) {
            logger.warn("Agent WS close 통보 실패 [sessionKey=$sessionKey]: ${e.message}")
        } finally {
            session.disposable?.dispose()
            logger.info("Agent 세션 종료 [sessionKey=$sessionKey]")
        }
    }

    private suspend fun openSession(
        sessionKey: String,
        testScenarioId: Long,
        projectId: Long,
        appUserId: Long,
        userInput: String
    ) {
        logger.info("Agent 세션 오픈 시도 [sessionKey=$sessionKey, url=$agentBaseUrl/sessions]")
        // 사용자의 계정 locale을 Agent에 함께 전달해 응답 언어를 맞춘다. locale 미설정(또는
        // ko가 아닌 값)은 en으로 보내 Agent 계약의 허용 값(ko|en)을 벗어나지 않게 한다.
        val locale = appUserRepository.findById(appUserId)
            ?.let { if (it.locale == "ko") "ko" else "en" }
            ?: "en"
        val body = AgentSessionOpenRequest(
            userInput = userInput,
            gameContext = gameContext(projectId, appUserId),
            model = defaultModel,
            locale = locale
        )
        val resp = webClient.post()
            .uri("$agentBaseUrl/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(AgentSessionOpenResponse::class.java)
            .awaitSingle()
        openWebSocket(sessionKey, testScenarioId, appUserId, resp.sessionId)
    }

    /**
     * 프로젝트의 가장 최근 씬 스캔을 game_context로 만든다. SDK가 등록 때 보고한 빌드의 UI 구성이며,
     * Agent가 어떤 화면을 대상으로 시나리오를 짜는지 참조한다.
     *
     * 최신 빌드가 스캔 없이 등록됐을 수도 있어(구버전 SDK), 스캔을 가진 가장 최근 빌드를 고른다.
     * 그런 빌드가 하나도 없으면 빈 맵이라 기존과 동일하게 빈 game_context를 보낸다.
     */
    private suspend fun gameContext(projectId: Long, appUserId: Long): Map<String, Any> {
        val build = buildRepository.findAllForMember(projectId, appUserId)
            .filter { it.sceneScan != null }
            .firstOrNull()
            ?: return emptyMap()
        return objectMapper.readValue(
            build.sceneScan!!.asString(),
            object : TypeReference<Map<String, Any>>() {}
        )
    }

    private fun sendTurn(
        sessionKey: String,
        session: AgentSession,
        userInput: String,
        draft: ScenarioDraft?
    ) {
        val json = objectMapper.writeValueAsString(AgentTurnMessage(userInput = userInput, draft = draft))
        val result = session.outbound.tryEmitNext(json)
        if (result.isFailure) {
            throw IllegalStateException("Agent WS 턴 전송 실패 [sessionKey=$sessionKey, result=$result]")
        }
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

    /**
     * WS 수신 메시지를 처리한다. Reactor WS 콜백에서 동기적으로 불리므로, DB 저장(suspend)은 [scope]로 넘겨
     * fire-and-forget 코루틴에서 수행한다.
     */
    private fun handleInbound(sessionKey: String, payloadText: String) {
        try {
            // Agent 응답을 타입화한 봉투로 파싱해 SSE로 중계한다(type=result|error).
            val event = objectMapper.readValue(payloadText, ScenarioStreamEvent::class.java)
            streamManager.emit(sessionKey, event)

            val session = sessions[sessionKey]
            if (event.type == "result" && session != null) {
                scope.launch {
                    // Agent 메시지를 ASSISTANT 채팅으로 저장.
                    try {
                        saveMessage(session.testScenarioId, session.appUserId, "ASSISTANT", event.message ?: "")
                    } catch (err: Exception) {
                        logger.error("ASSISTANT 메시지 저장 실패 [sessionKey=$sessionKey]: ${err.message}")
                    }
                    // scenario를 test_scenario에 저장(UPDATE).
                    event.scenario?.let {
                        try {
                            persistScenario(sessionKey, session.testScenarioId, it)
                        } catch (err: Exception) {
                            logger.error("시나리오 저장 실패 [sessionKey=$sessionKey]: ${err.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Agent WS 수신 메시지 처리 실패 [sessionKey=$sessionKey]: ${e.message}", e)
        }
    }

    /** 채팅 메시지를 저장한다(사용자별 프라이빗 스레드). */
    private suspend fun saveMessage(testScenarioId: Long, appUserId: Long, role: String, content: String) {
        messageRepository.save(
            TestScenarioMessageEntity(
                testScenarioId = testScenarioId,
                appUserId = appUserId,
                role = role,
                content = content
            )
        )
    }

    /** scenario를 JSONB로 직렬화해 testScenarioId로 UPDATE한다. 시나리오는 세션 전에 이미 생성돼 있다. */
    private suspend fun persistScenario(sessionKey: String, testScenarioId: Long, scenario: ScenarioDraft) {
        val payloadJson = Json.of(objectMapper.writeValueAsString(scenario))
        val existing = scenarioRepository.findById(testScenarioId) ?: return
        val saved = scenarioRepository.save(existing.copy(payload = payloadJson))
        logger.info("시나리오 저장 완료 [sessionKey=$sessionKey, id=${saved.id}]")
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
