package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.testcase.service.TestCaseSearchService
import kr.artel.orchestration.testrun.entity.TestRunMessageEntity
import kr.artel.orchestration.testrun.repository.TestRunMessageRepository
import kr.artel.orchestration.testscenario.dto.AgentCloseMessage
import kr.artel.orchestration.testscenario.dto.AgentSessionOpenRequest
import kr.artel.orchestration.testscenario.dto.AgentSessionOpenResponse
import kr.artel.orchestration.testscenario.dto.AgentTurnMessage
import kr.artel.orchestration.testscenario.dto.CurrentScenario
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.dto.TestCaseSearchErrorFrame
import kr.artel.orchestration.testscenario.dto.TestCaseSearchResultFrame
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
 * 저작 챗봇의 Agent 서버 연동 서비스(코루틴). 실제 Agent 서버 계약(FastAPI)에 맞춘다:
 *
 * 1. 세션 오픈: `POST {base}/sessions {user_input, unity_context, game_context, model, project_id, run_id}` → `{session_id}`
 * 2. WS 연결: `WS {ws-base}/sessions/{session_id}`. 연결 시 Agent가 첫 결과를 보낸다(오픈 때 준 user_input 기반).
 * 3. 후속 턴: WS로 `{type:"turn", user_input, model?}` 전송.
 * 4. 결과 수신: `{type:"result", message, scenarios[]}` → SSE 중계 + scenarios를 test_scenario
 *    (payload.steps JSONB)·test_run_scenario에 반영(신규 저작. 빈 배열이면 DB 무변경).
 * 5. 인입 검색: `{type:"test_case_search", ...}` → TestCaseSearchService로 답(`test_case_search_result`).
 *
 * **세션은 런 단위다**(ARTEL-206 Step 6): 세션 키(`sessionKey` = userId:runId)로 Agent 세션(session_id + WS)을
 * 식별한다. 한 번의 대화로 여러 시나리오를 추가·수정하므로 대화의 주체가 시나리오가 아니라 런이며, 대화·저장·
 * 검색 스코프가 모두 런이다. 오간 채팅 메시지는 사용자별 프라이빗 스레드로 test_run_message에 저장한다(USER/ASSISTANT).
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
    private val runMessageRepository: TestRunMessageRepository,
    private val buildRepository: GameBuildRepository,
    private val appUserRepository: AppUserRepository,
    private val testCaseSearchService: TestCaseSearchService,
    private val reconcileService: ScenarioReconcileService
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
     * 사용자 입력은 USER 메시지로 런의 채팅 스레드에 저장된다.
     */
    suspend fun sendMessage(
        sessionKey: String,
        runId: Long,
        projectId: Long,
        appUserId: Long,
        userInput: String,
        autoApply: Boolean,
        currentScenarios: List<CurrentScenario>,
    ) {
        // 원래 순서대로: 먼저 USER 메시지를 저장한 뒤 Agent로 보낸다.
        saveMessage(runId, appUserId, "USER", userInput)
        val existing = sessions[sessionKey]
        if (existing != null) {
            // 토글을 매 턴 반영해 대화 중 변경도 다음 결과부터 적용되게 한다.
            existing.autoApply = autoApply
            sendTurn(sessionKey, existing, userInput, currentScenarios)
        } else {
            openSession(sessionKey, runId, projectId, appUserId, userInput, autoApply, currentScenarios)
        }
    }

    /**
     * Agent 세션을 종료한다(런 편집 종료 시). Agent에 `{type:"close"}`를 통보해 WS와 session_id(Redis)를
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
        runId: Long,
        projectId: Long,
        appUserId: Long,
        userInput: String,
        autoApply: Boolean,
        currentScenarios: List<CurrentScenario>,
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
            locale = locale,
            projectId = projectId,
            runId = runId,
            currentScenarios = currentScenarios
        )
        val resp = webClient.post()
            .uri("$agentBaseUrl/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(AgentSessionOpenResponse::class.java)
            .awaitSingle()
        openWebSocket(sessionKey, runId, projectId, appUserId, resp.sessionId, autoApply)
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
        currentScenarios: List<CurrentScenario>,
    ) {
        val json = objectMapper.writeValueAsString(
            AgentTurnMessage(userInput = userInput, currentScenarios = currentScenarios)
        )
        val result = session.outbound.tryEmitNext(json)
        if (result.isFailure) {
            throw IllegalStateException("Agent WS 턴 전송 실패 [sessionKey=$sessionKey, result=$result]")
        }
    }

    private fun openWebSocket(
        sessionKey: String,
        runId: Long,
        projectId: Long,
        appUserId: Long,
        agentSessionId: String,
        autoApply: Boolean,
    ) {
        val outbound = Sinks.many().unicast().onBackpressureBuffer<String>()
        val session = AgentSession(outbound, runId, projectId, appUserId, agentSessionId, autoApply)
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
     * WS 수신 메시지를 처리한다. Reactor WS 콜백에서 동기적으로 불리므로, DB 저장·검색(suspend)은 [scope]로
     * 넘겨 fire-and-forget 코루틴에서 수행한다.
     *
     * 인입 프레임은 두 갈래다:
     * - `test_case_search`: Agent가 케이스 검색을 요청한다. 여기서 답할 뿐 SSE로 중계하지 않는다.
     *   검색 라우팅과 같은 규칙 — 실패해도 절대 throw하지 않아 WS/세션이 죽지 않는다.
     * - `result`/`error`: Agent 턴 결과. SSE로 FE에 중계하고, `result`면 ASSISTANT 채팅 저장 + 시나리오 반영.
     */
    private fun handleInbound(sessionKey: String, payloadText: String) {
        try {
            val node = objectMapper.readTree(payloadText)
            val session = sessions[sessionKey]
            if (node.path("type").asText() == "test_case_search") {
                if (session == null) {
                    logger.warn("test_case_search를 받았지만 세션이 없어 무시 [sessionKey=$sessionKey]")
                    return
                }
                // 검색은 임베딩(Agent /embed) 호출을 동반하는 suspend라 코루틴으로 넘긴다.
                scope.launch { handleCaseSearch(sessionKey, session, node) }
                return
            }

            // Agent 응답을 타입화한 봉투로 파싱해 SSE로 중계한다(type=result|error).
            val event = objectMapper.readValue(payloadText, ScenarioStreamEvent::class.java)
            streamManager.emit(sessionKey, event)

            if (event.type == "result" && session != null) {
                scope.launch {
                    // Agent 메시지를 ASSISTANT 채팅으로 저장.
                    try {
                        saveMessage(session.runId, session.appUserId, "ASSISTANT", event.message ?: "")
                    } catch (err: CancellationException) {
                        throw err
                    } catch (err: Exception) {
                        logger.error("ASSISTANT 메시지 저장 실패 [sessionKey=$sessionKey]: ${err.message}")
                    }
                    // 결과는 항상 SSE로 중계된다(위). autoApply가 켜져 있을 때만 서버가 즉시 upsert한다.
                    // 꺼져 있으면(카드 검토 모드) 저장하지 않고 제안으로만 두고, 사용자가 카드로 커밋한다
                    // (TestRunChatService.commitScenarios). 어느 경우든 빈 배열은 무동작.
                    if (session.autoApply) {
                        try {
                            reconcileService.reconcile(
                                session.runId,
                                session.projectId,
                                event.scenarios ?: emptyList()
                            )
                        } catch (err: CancellationException) {
                            throw err
                        } catch (err: Exception) {
                            logger.error("시나리오 반영 실패 [sessionKey=$sessionKey]: ${err.message}")
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Agent WS 수신 메시지 처리 실패 [sessionKey=$sessionKey]: ${e.message}", e)
        }
    }

    /**
     * Agent의 `test_case_search` 프레임을 처리해 `test_case_search_result`로 답한다(ARTEL-206 Step 5).
     *
     * **검색 범위는 프레임이 아니라 세션 바인딩의 projectId에서 나온다.** Agent가 프로젝트를 지목할 수
     * 있으면 프레임 하나로 남의 프로젝트 케이스를 읽게 된다([TestCaseVectorSearchRepository]와 같은 판단).
     *
     * 어떤 실패도 throw하지 않는다 — 검색 하나가 receive 체인을 끊어 WS/세션을 죽이면 안 된다. 대기 중인
     * Agent 도구를 풀기 위해 실패는 `error` 프레임(correlationId 포함)으로 되돌린다.
     */
    private suspend fun handleCaseSearch(sessionKey: String, session: AgentSession, node: JsonNode) {
        val messageId = node.path("messageId").takeIf { it.isTextual }?.asText()
        try {
            val query = node.path("query").takeIf { it.isTextual }?.asText()
            if (query.isNullOrBlank()) {
                sendCaseSearchError(sessionKey, session, messageId, "test_case_search query is required")
                return
            }
            val category = node.path("category").takeIf { it.isTextual }?.asText()
            val limit = node.path("limit").takeIf { it.isNumber }?.asInt()
            val response = testCaseSearchService.search(session.projectId, query, category, limit)
            emitFrame(
                sessionKey,
                session,
                TestCaseSearchResultFrame(correlationId = messageId, results = response.results)
            )
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            // Agent /embed 실패·모델 불일치·DB 오류가 여기로 온다. 세션을 죽이지 않고 error 프레임으로 답한다.
            logger.error("test_case_search 처리 실패 [sessionKey=$sessionKey]: ${err.message}")
            sendCaseSearchError(sessionKey, session, messageId, err.message ?: "test_case_search failed")
        }
    }

    private fun sendCaseSearchError(
        sessionKey: String,
        session: AgentSession,
        messageId: String?,
        detail: String
    ) {
        emitFrame(sessionKey, session, TestCaseSearchErrorFrame(correlationId = messageId, detail = detail))
    }

    /** 프레임을 세션 outbound 싱크로 내보낸다. 전송 실패는 로그만 남기고 멈춘다(WS를 죽이지 않는다). */
    private fun emitFrame(sessionKey: String, session: AgentSession, frame: Any) {
        val result = session.outbound.tryEmitNext(objectMapper.writeValueAsString(frame))
        if (result.isFailure) {
            logger.warn("Agent WS 프레임 전송 실패 [sessionKey=$sessionKey, result=$result]")
        }
    }

    /** 채팅 메시지를 저장한다(런 단위, 사용자별 프라이빗 스레드). */
    private suspend fun saveMessage(runId: Long, appUserId: Long, role: String, content: String) {
        runMessageRepository.save(
            TestRunMessageEntity(
                testRunId = runId,
                appUserId = appUserId,
                role = role,
                content = content
            )
        )
    }

    /**
     * Agent 세션 상태: 송신 싱크, 결과 시나리오가 붙고 채팅이 저장될 runId, 검색·저장 스코프 projectId,
     * 채팅 소유자 appUserId, Agent가 발급한 session_id, 결과를 서버가 즉시 반영할지(autoApply, 매 턴 갱신).
     */
    private class AgentSession(
        val outbound: Sinks.Many<String>,
        val runId: Long,
        val projectId: Long,
        val appUserId: Long,
        val agentSessionId: String,
        @Volatile var autoApply: Boolean,
        @Volatile var disposable: Disposable? = null
    )
}
