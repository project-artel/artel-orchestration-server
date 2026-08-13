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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.TestCaseListItem
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.TestCaseSearchService
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testrun.entity.TestRunMessageEntity
import kr.artel.orchestration.testrun.repository.TestRunMessageRepository
import kr.artel.orchestration.testscenario.dto.AgentCloseMessage
import kr.artel.orchestration.testscenario.dto.AgentSessionOpenRequest
import kr.artel.orchestration.testscenario.dto.AgentSessionOpenResponse
import kr.artel.orchestration.testscenario.dto.AgentTurnMessage
import kr.artel.orchestration.testscenario.dto.CurrentScenario
import kr.artel.orchestration.testscenario.dto.ReviewedCases
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.dto.TestCaseSearchErrorFrame
import kr.artel.orchestration.testscenario.dto.UncoveredCasesResultFrame
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
 * 작성 챗봇의 Agent 서버 연동 서비스(코루틴). 실제 Agent 서버 계약(FastAPI)에 맞춘다:
 *
 * 1. 세션 오픈: `POST {base}/sessions {user_input, unity_context, game_context, test_case_list, uncovered_case_ids, model, project_id, run_id}` → `{session_id}`
 * 2. WS 연결: `WS {ws-base}/sessions/{session_id}`. 연결 시 Agent가 첫 결과를 보낸다(오픈 때 준 user_input 기반).
 * 3. 후속 턴: WS로 `{type:"turn", user_input, model?}` 전송.
 * 4. 결과 수신: `{type:"result", message, scenarios[]}` → SSE 중계 + scenarios를 test_scenario
 *    (payload.steps JSONB)·test_run_scenario에 반영(신규 작성. 빈 배열이면 DB 무변경).
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
    @Value("\${artel.agent.model:}") private val configuredModel: String,
    private val objectMapper: ObjectMapper,
    private val streamManager: TestScenarioStreamManager,
    private val runMessageRepository: TestRunMessageRepository,
    private val buildRepository: GameBuildRepository,
    private val appUserRepository: AppUserRepository,
    private val testCaseSearchService: TestCaseSearchService,
    private val testCaseService: TestCaseService,
    private val testCaseRepository: TestCaseRepository,
    private val projectAccessService: ProjectAccessService,
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
            testCaseList = testCaseList(projectId, appUserId),
            // 모델 선택의 기본값은 모델 카탈로그를 소유한 Agent가 결정한다. Orchestration은
            // 명시적 override가 있을 때만 model을 보내 모델 교체 때 구 slug를 강제하지 않는다.
            model = configuredModel.takeIf { it.isNotBlank() },
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

    /**
     * 프로젝트 TestCase 전량을 test_case_list로 만든다(ARTEL-318). Agent가 "무엇을 검증할 수 있는지"의
     * 전체 목록이며, 여기 없는 케이스는 애초에 지목될 수 없다.
     *
     * **세션 오픈 시점의 스냅샷이고 턴마다 갱신하지 않는다.** 이 목록은 Agent 프롬프트의 앞쪽 고정
     * 블록에 실려 프롬프트 캐시를 타는데, 턴마다 다시 실으면 블록이 바뀌어 캐시가 깨진다. 대화 중
     * 새로 만들어진 케이스는 다음 세션부터 보인다 — 저작 대화 한 번의 수명 안에서 목록이 바뀌는 것이
     * 정상적인 상황이 아니므로 그 대가로 캐시를 사는 편이 낫다.
     *
     * [gameContext]와 같은 자리·같은 성격이다: 툴 호출이 아니라 첫 턴부터 쥐고 있는 배경 지식.
     * 비참여자면 빈 목록이라 기존과 동일하게 동작한다.
     */
    private suspend fun testCaseList(projectId: Long, appUserId: Long): List<TestCaseListItem> =
        testCaseService.getAllTestCases(projectId, appUserId).items

    /**
     * 미커버 조회 프레임에 답한다(ARTEL-403).
     *
     * **밀어 넣지 않고 물어보게 하는 이유**: 이 값은 저작이 진행될수록 줄어든다. 세션 오픈 때 한 번
     * 실어 보내면 둘째 턴부터 틀린 값이 되고, 매 턴 다시 실으면 턴 메시지가 붓거나 — system 프롬프트에
     * 두면 더 나쁘게 — 전량 목록(74k) 캐시를 매 턴 통째로 버린다. 도구 호출은 물어볼 때만 값을 낸다.
     *
     * 검색과 같은 규칙으로 **실패해도 절대 throw하지 않는다.** 커버리지를 못 읽는 것이 WS나 세션을
     * 죽일 이유는 없다.
     */
    private suspend fun handleUncoveredRequest(sessionKey: String, session: AgentSession, node: JsonNode) {
        val correlationId = node.path("messageId").asText(null)
        try {
            val ids = testCaseRepository.findUncoveredIdsByProjectId(session.projectId).toList()
            val scenes = testCaseRepository.findScenesOfUncovered(session.projectId).toList()
            sendFrame(
                sessionKey, session,
                UncoveredCasesResultFrame(correlationId = correlationId, ids = ids, scenes = scenes)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("미커버 조회 실패 [sessionKey=$sessionKey]: ${e.message}")
            sendFrame(
                sessionKey, session,
                TestCaseSearchErrorFrame(correlationId = correlationId, detail = "미커버 조회에 실패했습니다.")
            )
        }
    }

    private fun sendFrame(sessionKey: String, session: AgentSession, frame: Any) {
        val result = session.outbound.tryEmitNext(objectMapper.writeValueAsString(frame))
        if (result.isFailure) {
            logger.warn("프레임 전송 실패 [sessionKey=$sessionKey, result=$result]")
        }
    }

    private suspend fun applyOrRepair(
        sessionKey: String,
        session: AgentSession,
        event: ScenarioStreamEvent,
    ) {
        val pending = session.repair
        session.repair = null

        val incoming = event.scenarios ?: emptyList()
        // 재작성 턴이면 앞서 막힌 결과에 새로 받은 것을 얹어 통째로 다시 본다.
        val scenarios = if (pending != null) pending.scenarios + incoming else incoming
        val reviewed = if (pending != null) mergeVerdicts(pending.reviewed, event.reviewed) else event.reviewed

        val outcome = reconcileService.reconcile(session.runId, session.projectId, scenarios, reviewed)
        if (!outcome.rejected) {
            if (pending != null) {
                saveMessage(
                    session.runId, session.appUserId, "ASSISTANT",
                    "빠졌던 부분을 다시 작성해 시나리오에 반영했습니다."
                )
            }
            // 저장이 있었던 턴에만 잔량을 알린다. 질문·거절 턴에서는 남은 수가 그대로일 뿐 아니라,
            // 방금 에이전트가 같은 값을 더 자세히 답했을 수 있다 — 그 뒤에 한 줄을 더 붙이면 같은
            // 말을 두 번 하는 셈이 된다.
            if (outcome.applied > 0) recommendRemaining(session)
            return
        }

        val attempts = (pending?.attempts ?: 0) + 1
        // 유령 번호는 결과 전체를 의심해야 하는 신호다 — 없는 케이스를 지어냈다면 나머지도 믿을
        // 근거가 없으므로 "더 써라"로 고칠 종류가 아니다. 나머지 둘은 고칠 수 있다:
        // 누락은 스텝을 더 쓰면 되고, 검토 누락은 판정만 더 받으면 된다.
        val repairable = outcome.findings.ghost.isEmpty() &&
            (outcome.findings.missing.isNotEmpty() || outcome.findings.unreviewed.isNotEmpty())

        if (!repairable || attempts > MAX_REPAIR_ATTEMPTS || reviewed == null) {
            // 더 못 고친다. 저장하지 않고 사람에게 넘긴다 — 사용자는 시나리오가 나왔다고 믿고
            // 있으므로, 여기서 말하지 않으면 저장되지 않았다는 사실을 알 길이 없다.
            saveMessage(
                session.runId, session.appUserId, "ASSISTANT",
                outcome.findings.rejectionMessage()
            )
            return
        }

        session.repair = PendingRepair(scenarios, reviewed, attempts)
        // 재작성을 시켰다는 사실을 사용자에게 알린다. 이 시간 동안 화면은 답을 기다리는 것처럼
        // 보이는데, 무슨 일이 일어나는지 말하지 않으면 그냥 느린 것과 구분되지 않는다.
        saveMessage(
            session.runId, session.appUserId, "ASSISTANT",
            "검토 결과 ${outcome.findings.summary()} — 그 부분만 다시 작성하도록 요청했습니다."
        )
        sendTurn(sessionKey, session, repairPrompt(outcome.findings), emptyList())
    }

    /**
     * 재작성 응답의 판정을 처음 선언에 **더하기만** 한다.
     *
     * 처음 `in`은 줄어들지 않는다. 재작성이 판정을 통째로 갈아치우게 두면 에이전트가 빠뜨린 케이스를
     * `out`으로 옮겨 스스로 통과시킬 수 있고, 그러면 검사가 있으나 마나가 된다.
     *
     * 반대로 **새로 판정된 id는 받아들인다.** 좁은 요청에서 에이전트가 전량을 판정하지 않아 검토
     * 누락이 뜬 경우, 나머지에 대한 판정을 받는 것이 곧 그 지적을 고치는 일이다.
     */
    private fun mergeVerdicts(first: ReviewedCases, extra: ReviewedCases?): ReviewedCases {
        if (extra == null) return first
        val locked = first.included.toSet()
        val newlyExcluded = (first.excluded + extra.excluded).distinct().filterNot { it in locked }
        return ReviewedCases(
            included = (first.included + extra.included).distinct(),
            excluded = newlyExcluded,
        )
    }

    /**
     * 재작성 지시문. 지적된 것만 다루게 하고 앞서 쓴 것은 다시 쓰지 말라고 못 박는다 — 전체를 다시
     * 만들면 출력 예산을 통째로 한 번 더 쓰고, 이미 통과한 부분까지 흔들린다.
     */
    private fun repairPrompt(findings: ScenarioCoverageAudit.Findings): String = buildString {
        if (findings.missing.isNotEmpty()) {
            append("이전 응답에서 관련 있다고 판단한 케이스 중 ")
            append(findings.missing.joinToString(", "))
            append("번이 어떤 스텝에도 담기지 않았습니다. 이 케이스들만 검증하는 시나리오를 새로 작성해 주세요(scenario_id는 null). ")
        }
        if (findings.unreviewed.isNotEmpty()) {
            append("그리고 ")
            append(findings.unreviewed.joinToString(", "))
            append("번은 in에도 out에도 없습니다. 이번 요청과 관련이 있는지 판단해 reviewed에 넣어 주세요. ")
            append("관련이 없다면 out이면 충분하고 시나리오를 쓸 필요는 없습니다. ")
        }
        append("앞서 작성한 시나리오는 다시 보내지 마세요 — 그대로 유지됩니다.")
    }

    /**
     * 저장이 끝난 뒤 **아직 아무 시나리오도 건드리지 않은 케이스**를 사용자에게 알린다(ARTEL-403).
     *
     * 이 수를 에이전트가 세게 하지 않는 이유는 두 가지다. 세션에 실은 미커버 목록은 세션을 열 때의
     * 스냅샷이라 방금 저장한 것이 반영돼 있지 않고, 무엇보다 **빠짐없이 세는 일은 에이전트가 못하는
     * 일**이다 — 이 작업 전체가 그 전제 위에 있다. 저장 직후의 DB가 답을 알고 있으니 거기서 읽는다.
     *
     * 전부 덮였으면 아무 말도 하지 않는다. "남은 것 없음"은 매번 붙으면 소음이 된다.
     */
    private suspend fun recommendRemaining(session: AgentSession) {
        val uncovered = try {
            testCaseRepository.findScenesOfUncovered(session.projectId).toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("미커버 조회 실패 — 추천 생략 [runId=${session.runId}]: ${e.message}")
            return
        }
        val total = uncovered.sumOf { it.count }
        if (total == 0L) return

        // 숫자가 그대로면 말하지 않는다. 이번 턴이 새로 덮은 것이 없다는 뜻이고(질문·편집·거절),
        // 같은 수를 매 턴 반복하면 좁은 작업을 이어가는 사람에게는 그게 소음이다.
        if (total == session.lastReportedUncovered) return
        session.lastReportedUncovered = total

        // 씬이 여섯 개인 프로젝트에서 여섯 줄이 매번 붙으면 읽히지 않는다. 많은 순 셋까지만.
        val shown = uncovered.take(MAX_SCENES_IN_RECOMMENDATION)
        val rest = uncovered.size - shown.size
        val breakdown = shown.joinToString(", ") { "${it.scene} ${it.count}" } +
            if (rest > 0) " 외 ${rest}개 씬" else ""

        saveMessage(
            session.runId, session.appUserId, "ASSISTANT",
            "아직 어떤 시나리오에도 담기지 않은 케이스가 ${total}건 남았습니다 — $breakdown. 이어서 만들까요?"
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
            if (node.path("type").asText() == "uncovered_cases") {
                if (session == null) {
                    logger.warn("uncovered_cases를 받았지만 세션이 없어 무시 [sessionKey=$sessionKey]")
                    return
                }
                scope.launch { handleUncoveredRequest(sessionKey, session, node) }
                return
            }
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
                            applyOrRepair(sessionKey, session, event)
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
        @Volatile var disposable: Disposable? = null,
        /** 검수에서 막혀 재작성을 기다리는 중인 결과. null이면 평범한 턴이다. */
        @Volatile var repair: PendingRepair? = null,
        /**
         * 마지막으로 사용자에게 알린 미커버 건수. 같은 수를 두 번 말하지 않기 위한 값이다 —
         * 좁은 작업을 이어가는 대화에서 매 턴 같은 줄이 붙으면 읽히지 않는다.
         */
        @Volatile var lastReportedUncovered: Long? = null
    )

    /**
     * 검수에 걸린 결과를 들고 재작성을 기다리는 상태(ARTEL-403).
     *
     * **막힌 시나리오를 여기 들고 있는 이유**: 저장을 안 했으니 DB에 `scenario_id`가 없고, 그러면
     * 에이전트에게 "그 시나리오를 고쳐라"라고 지목할 방법이 없다. 그렇다고 통과한 것만 먼저 저장하면
     * 부분 저장이 되는데, 그건 "일부만 검증된 시나리오"를 남기므로 검사를 안 한 것보다 나쁘다.
     *
     * 그래서 앞 결과를 메모리에 쥐고, 빠진 것만 새로 받아 **합쳐서 다시 검수한 뒤 한 번에** 저장한다.
     * 재작성 출력이 작아지는 것은 덤이고, 본질은 저장이 여전히 전부-아니면-전무라는 점이다.
     *
     * @property reviewed 처음 판정. 재검수도 **이 선언 기준**이다 — 재작성 때 판정을 다시 받으면
     *   에이전트가 빠뜨린 것을 out으로 옮겨 스스로 통과시킬 수 있다.
     */
    private class PendingRepair(
        val scenarios: List<ScenarioResult>,
        val reviewed: ReviewedCases,
        val attempts: Int,
    )

    companion object {
        /**
         * 재작성을 몇 번까지 시킬지.
         *
         * 1회다. 같은 지적을 두 번 받고도 못 고치면 세 번째라고 달라질 이유가 없고, 그동안 사용자는
         * 답을 기다린다. 상한에 걸리면 저장하지 않고 무엇이 빠졌는지 사람에게 넘긴다.
         */
        private const val MAX_REPAIR_ATTEMPTS = 1

        /** 남은 씬을 몇 개까지 나열할지. 나머지는 "외 N개 씬"으로 접는다. */
        private const val MAX_SCENES_IN_RECOMMENDATION = 3
    }
}
