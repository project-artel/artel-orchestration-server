package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import io.r2dbc.postgresql.codec.Json
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kr.artel.orchestration.testcase.dto.AuthoringTestCase
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.TestCaseSearchService
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testrun.entity.TestRunMessageEntity
import kr.artel.orchestration.testrun.repository.TestRunMessageRepository
import kr.artel.orchestration.testscenario.agent.PhrasedStep
import kr.artel.orchestration.testscenario.agent.ScenarioStepPhrasingClient
import kr.artel.orchestration.testscenario.dto.AgentCloseMessage
import kr.artel.orchestration.testscenario.dto.AuthoringStage
import kr.artel.orchestration.testscenario.dto.AgentSessionOpenRequest
import kr.artel.orchestration.testscenario.dto.AgentSessionOpenResponse
import kr.artel.orchestration.testscenario.dto.AgentTurnMessage
import kr.artel.orchestration.testscenario.dto.CurrentScenario
import kr.artel.orchestration.testscenario.dto.ReviewedCases
import kr.artel.orchestration.testscenario.dto.CaseFactsResultFrame
import kr.artel.orchestration.testscenario.dto.CaseGuardFrame
import kr.artel.orchestration.testscenario.dto.CaseOperationFrame
import kr.artel.orchestration.testscenario.dto.ScenarioPathResultFrame
import kr.artel.orchestration.testscenario.dto.ScenarioQuestion
import kr.artel.orchestration.testscenario.dto.ScenarioQuestionAnswer
import kr.artel.orchestration.testscenario.dto.ScenarioQuestionOption
import kr.artel.orchestration.testscenario.dto.ScenarioQuestionSource
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
    private val reconcileService: ScenarioReconcileService,
    private val pathService: ScenarioPathService,
    private val caseFactService: ScenarioCaseFactService,
    private val gapFiller: ScenarioGapFiller,
    private val phrasingClient: ScenarioStepPhrasingClient,
    private val trace: AuthoringTrace,
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
        answer: ScenarioQuestionAnswer? = null,
    ) {
        // 되묻는 질문에 대한 답이면 **무엇에 대한 답인지 함께 실어 보낸다**(ARTEL-487). 보기 id만
        // 보내면 모델은 그것이 무슨 뜻인지 모른다 — 질문과 보기 문구를 여기서 다시 붙인다.
        // 세션에 없으면 **저장된 질문**에서 찾는다. 카드 저장으로 물었거나, 서버가 다시 떴거나,
        // 사용자가 새로고침한 화면에서 답할 수 있다 — 그때 답을 잃으면 물어본 보람이 없다.
        // **답이 지목한 질문을 찾는다**(ARTEL-630). 한 번에 여럿을 내므로 세션이 문 첫 질문이
        // 아닐 수 있다 — 그때 첫 것으로 답하면 엉뚱한 질문에 답한 것이 된다.
        val pending = sessions[sessionKey]?.question?.takeIf { answer == null || it.id == answer.questionId }
            ?: lastQuestion(runId, appUserId, answer?.questionId)

        // 미상 구간에 대한 답은 **코드가 그 자리에 넣는다**(ARTEL-487). 모델에게 넘겨 다시 쓰게
        // 했더니 "StoryScene→Map_scene 을 어떻게 가나요"의 답이 엉뚱한 6번 자리에 들어가고 정작
        // 그 구간의 알림은 그대로 남았다 — 사용자가 보기에는 답을 했는데 아무 일도 안 일어났다.
        //
        // **자리는 코드가, 문장은 모델이.** 어느 자리인지는 질문 id 에 들어 있어 계산으로 끝나고,
        // 사용자가 적은 말을 앞뒤 스텝과 같은 결로 다듬는 것은 모델이 낫다. 다듬기가 실패하면
        // 적은 말을 그대로 넣는다 — 어색한 문장보다 알려준 것이 사라지는 쪽이 나쁘다.
        gapHowTo(pending, answer)?.let { howTo ->
            val blockedBy = pending!!.id.removePrefix(ScenarioQuestionBuilder.GAP_PREFIX)
            // 다듬는 데도 모델이 한 번 돌아 몇 초가 걸린다. 말없이 기다리게 두면 답이 먹히지
            // 않은 것처럼 보인다 — 실제로 일어나는 일이 모델 호출이므로 같은 단계로 말한다.
            progress(sessionKey, AuthoringStage.THINKING)
            val written = mutableListOf<String>()
            val filled = gapFiller.fill(runId, blockedBy) { before, after ->
                val lines = phrasingClient.phrase(howTo, blockedBy, before, after, localeOf(appUserId))
                    ?: listOf(PhrasedStep(action = howTo))
                written += lines.map { it.action }
                lines
            }
            if (filled > 0) {
                sessions[sessionKey]?.question = null
                saveMessage(runId, appUserId, "USER", userInput.ifBlank { answerSummary(pending, answer) })
                notifyGapFilled(sessionKey, runId, appUserId, blockedBy, written)
                return
            }
            // 채울 자리가 없거나(이미 채워졌다) 적은 말이 통과 방법이 아니었다("잘 모르겠는데").
            // 어느 쪽이든 답을 삼키지 말고 평소대로 대화로 넘긴다.
            logger.info("미상 구간을 채우지 않았다 — 대화로 넘긴다 [runId={}] {}", runId, blockedBy)
        }

        // **"안 할래"는 코드가 아는 답이다**(ARTEL-487). 거절을 모델에게 넘기면 값을 치르고도
        // 얻는 것이 없다 — 모델이 시나리오를 그대로 돌려주면 검수가 다시 돌고, 같은 조건이 또
        // 걸려 **방금 거절한 질문이 다시 나갔다.** 여기서 받아서, 무엇을 그대로 뒀는지와 마음이
        // 바뀌면 무엇을 하면 되는지만 말한다.
        if (declined(pending, answer, userInput)) {
            sessions[sessionKey]?.question = null
            saveMessage(runId, appUserId, "USER", answerSummary(pending, answer))
            notifyDeclined(sessionKey, runId, appUserId, pending!!, askedBatch(sessionKey, pending!!))
            return
        }

        val turnInput = when {
            // 보기를 눌렀다 — 무엇에 대한 답인지 붙여 보낸다.
            answerText(pending, answer) != null ->
                answerText(pending, answer)!!.let { if (userInput.isBlank()) it else "$it\n\n$userInput" }
            // 그냥 말로 답했다. **모델은 그 질문을 본 적이 없다** — 코드가 만든 질문은 오케가
            // 저장·전송하고 모델의 대화에는 들어가지 않는다. 그래서 "응" 한 마디가 무엇에 대한
            // 것인지 알 길이 없었다. 물어본 것을 붙여 주고 판단은 모델에 맡긴다 — 이어지는 말이
            // 아닐 수도 있어서 "답"이라고 단정하지 않는다.
            pending != null && userInput.isNotBlank() ->
                "(직전에 물어본 것: ${pending.text})\n\n$userInput"
            else -> userInput
        }
        // **보낼 말이 없으면 보내지 않는다**(ARTEL-487). 이미 답한 질문의 보기를 한 번 더 누르면
        // 여기까지 온다 — 새로고침한 화면이 그 버튼을 되살려 주기 때문이다(런 150). 그때 빈 턴을
        // 모델에게 넘기면 "실행 목표가 비어 있어 판단할 수 없다"는 답이 돌아오고, 사용자는 자기가
        // 무엇을 잘못했는지 모른 채 그 말을 읽는다. 답이 이미 끝난 질문이면 그때 한 말을 되풀이한다.
        if (turnInput.isBlank()) {
            answer?.questionId?.takeIf { it in answeredQuestionIds(runId, appUserId) }?.let { id ->
                saveMessage(runId, appUserId, "ASSISTANT", ScenarioDeclineReply.advice(ScenarioQuestion(id, "")))
                logger.info("이미 답한 질문에 다시 답했다 — 같은 안내를 되풀이한다 [runId={}] {}", runId, id)
            } ?: logger.info("보낼 말이 없는 턴 — 모델을 부르지 않는다 [runId={}]", runId)
            return
        }

        // **앞선 턴이 도는 중이면 받지 않는다**(ARTEL-510). 에이전트는 이미 `busy` 로 거절하고
        // 있었지만, 여기서 사용자의 말을 **먼저 저장한 뒤** 보내고 있었다 — 그래서 대화에 답이
        // 오지 않는 말풍선이 남았다(런 152: 같은 요청이 세 줄, 답은 한 번). 새로고침으로 화면이
        // 진행 상태를 잃으면 사람은 당연히 다시 보내므로, 이건 드문 경우가 아니다.
        sessions[sessionKey]?.takeIf { it.busy }?.let {
            val message = "앞서 보낸 요청을 아직 처리하고 있습니다. 끝나면 이어서 말씀해 주세요."
            streamManager.emit(sessionKey, ScenarioStreamEvent(type = "notice", message = message))
            logger.info("앞선 턴이 도는 중 — 이 요청은 받지 않는다 [runId={}]", runId)
            return
        }

        // 물어본 것은 한 턴만 산다. 답이든 딴 얘기든 다음 턴까지 끌고 가면, 한참 뒤의 "응"이
        // 엉뚱한 질문에 붙는다. 화면의 보기는 저장된 질문으로 계속 답할 수 있다.
        sessions[sessionKey]?.question = null

        // **대화에는 사용자가 한 말만 남긴다.** 모델에게 붙여 보내는 맥락(직전 질문, 고른 보기)은
        // 전달을 위한 것이지 사용자가 쓴 문장이 아니다 — 말풍선에 그대로 뜨면 자기가 하지 않은
        // 말을 한 것처럼 보인다.
        saveMessage(runId, appUserId, "USER", userInput.ifBlank { answerSummary(pending, answer) })
        val existing = sessions[sessionKey]
        if (existing != null) {
            // 토글을 매 턴 반영해 대화 중 변경도 다음 결과부터 적용되게 한다.
            existing.autoApply = autoApply
            sendTurn(sessionKey, existing, turnInput, currentScenarios)
        } else {
            openSession(sessionKey, runId, projectId, appUserId, turnInput, autoApply, currentScenarios)
        }
        // 보낸 뒤에 알린다. 세션 오픈이나 턴 전송이 실패하면 "보냈다"가 거짓이 되고,
        // 그 경우 사용자는 진행 중인 것처럼 보이는 화면 앞에서 오지 않을 답을 기다리게 된다.
        progress(sessionKey, AuthoringStage.SENT)
        watch(sessionKey, runId, appUserId)
    }

    /**
     * 답이 오지 않는 턴을 **멎었다고 말해 준다**(ARTEL-510).
     *
     * 저작 턴에는 시한이 없었다. 실측(런 152)에서 요청 하나가 결과도 오류도 없이 끝나지 않았고,
     * 사용자가 본 것은 500초가 지나도 "요청 중"인 화면이었다 — 벗어나는 방법은 새로고침뿐이었다.
     * 로그에는 도구 프레임 뒤로 아무것도 없었다.
     *
     * **턴을 취소하지는 않는다.** 늦게라도 결과가 오면 그건 반영되는 것이 맞다. 여기서 하는 일은
     * 기다림에 끝을 주는 것뿐이다 — 화면이 풀리고, 다음 말을 보낼 수 있게 된다.
     *
     * ## 재는 것은 "죽었나"이지 "느린가"가 아니다(ARTEL-632)
     *
     * 시한을 턴 시작부터 고정으로 세면, **살아서 일하는 턴**도 끊긴다. 실측(런 179)에서 저작이
     * 도구를 47번 부르며 일하는 동안 정각 5분에 시한이 났다 — 사용자가 본 것은 "끝나지 않았습니다"
     * 였고, 결과는 그 뒤에 도착해 조용히 저장됐다. 물어야 할 것 셋도 그 턴과 함께 사라졌다.
     *
     * 그래서 **에이전트가 무언가 할 때마다 시한을 미룬다**([touch]). 아무 소식도 없는 5분만
     * 멎은 것으로 본다. 조회가 많은 턴은 오래 걸릴 뿐 멎은 것이 아니다.
     */
    private fun watch(sessionKey: String, runId: Long, appUserId: Long) {
        val session = sessions[sessionKey] ?: return
        session.watchdog?.cancel()
        session.lastHeard = System.currentTimeMillis()
        session.watchdog = scope.launch {
            // **소식이 있으면 다시 기다린다.** 아무것도 안 오는 시간만 센다 — 마지막으로 들은 때에서
            // 시한이 차는 자리까지 자고, 그 사이 무언가 들렸으면 그만큼 더 잔다.
            while (true) {
                val wait = TurnDeadline.remainingWait(
                    session.lastHeard, System.currentTimeMillis(), TURN_DEADLINE_MILLIS,
                ) ?: break
                delay(wait)
            }
            val message = "${TURN_DEADLINE_MILLIS / 60_000}분간 아무 소식이 없어 기다림을 끝냅니다. " +
                "다시 말씀해 주시면 새로 시도합니다 — 늦게라도 결과가 오면 그때 반영됩니다."
            logger.warn("턴이 시한을 넘겼다 — 기다림을 끝낸다 [sessionKey={}, runId={}]", sessionKey, runId)
            runCatching { saveMessage(runId, appUserId, "ASSISTANT", message) }
                .onFailure { logger.warn("시한 초과 안내 저장 실패: ${it.message}") }
            streamManager.emit(
                sessionKey,
                ScenarioStreamEvent(type = "error", code = "turn_timeout", detail = message),
            )
        }
    }

    /** 소식이 왔다 — 기다림이 끝났다. */
    private fun stopWatching(sessionKey: String) {
        sessions[sessionKey]?.let {
            it.watchdog?.cancel()
            it.watchdog = null
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
        val locale = localeOf(appUserId)
        val cases = testCaseList(projectId, appUserId)
        trace.record(
            runId, "판을 연다",
            "말: $userInput\n" +
                "케이스 ${cases.size}건 ${trace.blob(runId, "cases.json", objectMapper.writeValueAsString(cases))}\n" +
                "모델: ${configuredModel.ifBlank { "에이전트 기본값" }} · 말투: $locale · " +
                "이미 있는 시나리오 ${currentScenarios.size}개",
        )
        val body = AgentSessionOpenRequest(
            userInput = userInput,
            gameContext = gameContext(),
            testCaseList = cases,
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
     * 사용자 계정 locale 을 Agent 계약의 허용 값으로 좁힌다(`ko`|`en`). 미설정이나 그 밖의 값은
     * `en` 이다 — 계약에 없는 값을 보내 세션이 열리지 않는 것보다 낫다.
     */
    private suspend fun localeOf(appUserId: Long): String =
        appUserRepository.findById(appUserId)?.let { if (it.locale == "ko") "ko" else "en" } ?: "en"

    /**
     * 저작 세션은 **씬 스캔을 싣지 않는다**(ARTEL-466).
     *
     * 예전에는 빌드의 `scene_scan` 을 `game_context` 로 실어 보냈다. 그 내용이 "어떤 씬이 있고
     * 거기서 무엇을 할 수 있나"인데, 그것은 지금 씬 명세가 답하는 질문과 **같은 질문**이다. 같은
     * 것을 두 곳에서 주면 둘이 갈라지고, 갈라진 뒤에는 어느 쪽이 맞는지 알 수 없다 — 실제로 로컬
     * 빌드에는 프로토타입 때 손으로 넣은 낡은 요약이 남아 매 세션 7.4KB 씩 나가고 있었다.
     *
     * 프롬프트에 싣는 형태가 더 나쁘다는 것도 실측(2026-08-18)에서 나왔다. 같은 지식을 프롬프트
     * 텍스트로 준 조건이 아예 주지 않은 것보다 나빴고(도달 불가 75.0% vs 66.7%), 계산된 답만
     * 툴로 주자 0%가 됐다. 그래서 이 자리는 비워 두고 `find_path` · `explain_case` 가 답한다.
     *
     * 컬럼 자체는 남는다 — SDK 가 보내는 값이고 다른 소비자가 생길 수 있다. 저작이 안 읽을 뿐이다.
     */
    private fun gameContext(): Map<String, Any> = emptyMap()

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
    private suspend fun testCaseList(projectId: Long, appUserId: Long): List<AuthoringTestCase> =
        testCaseService.getAuthoringCases(projectId, appUserId)

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
            // 성공도 남긴다. 이 경로가 조용하면 "도구를 안 불렀다"와 "불렀는지 알 수 없다"가
            // 로그에서 같아 보이고, 에이전트가 숫자를 지어냈는지 확인할 방법이 사라진다.
            logger.info(
                "미커버 조회 응답 [sessionKey={}, projectId={}] {}건 · {}",
                sessionKey, session.projectId, ids.size,
                scenes.joinToString(", ") { "${it.scene} ${it.count}" }
            )
            sendFrame(
                sessionKey, session,
                UncoveredCasesResultFrame(correlationId = correlationId, ids = ids, scenes = scenes)
            )
            progress(sessionKey, AuthoringStage.WRITING)
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

    /**
     * 경로 조회 프레임에 답한다(ARTEL-466).
     *
     * 미커버 조회와 같은 규칙이다 — **실패해도 절대 throw하지 않는다.** 길을 못 읽는 것이 WS나
     * 세션을 죽일 이유는 없다. 그리고 성공도 로그로 남긴다. 이 경로가 조용하면 "안 불렀다"와
     * "불렀는지 알 수 없다"가 로그에서 같아 보이는데, ARTEL-403 에서 실제로 그 때문에 도구를
     * 안 부른다고 잘못 진단한 적이 있다.
     */
    private suspend fun handleFindPathRequest(sessionKey: String, session: AgentSession, node: JsonNode) {
        val correlationId = node.path("messageId").asText(null)
        try {
            val from = node.path("from_case_id").asLong(0)
            val to = node.path("to_case_id").asLong(0)
            if (from == 0L || to == 0L) {
                sendFrame(
                    sessionKey, session,
                    TestCaseSearchErrorFrame(
                        correlationId = correlationId,
                        detail = "find_path 에는 from_case_id 와 to_case_id 가 모두 필요합니다.",
                    )
                )
                return
            }
            val answer = pathService.findPath(session.projectId, session.appUserId, from, to)
            logger.info(
                "경로 조회 응답 [sessionKey={}, projectId={}] {}→{} {} {} 순서={}",
                sessionKey, session.projectId, from, to, answer.result,
                answer.blockedBy?.let { "막힘=$it" } ?: answer.capabilityIds.toString(),
                answer.ordering,
            )
            sendFrame(
                sessionKey, session,
                ScenarioPathResultFrame(
                    correlationId = correlationId,
                    result = answer.result.name,
                    capabilityIds = answer.capabilityIds,
                    actions = answer.actions,
                    inputs = answer.inputs,
                    ordering = answer.ordering.name,
                    blockedBy = answer.blockedBy,
                    note = answer.note,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("경로 조회 실패 [sessionKey=$sessionKey]: ${e.message}")
            sendFrame(
                sessionKey, session,
                TestCaseSearchErrorFrame(correlationId = correlationId, detail = "경로 조회에 실패했습니다.")
            )
        }
    }

    /**
     * 케이스 하나를 지도에 비춰 답한다(ARTEL-466).
     *
     * 경로 조회와 같은 규칙이다 — **실패해도 절대 throw하지 않는다.** 지도를 못 읽는 것이 WS나
     * 세션을 죽일 이유는 없고, 성공도 로그로 남긴다(안 불렀다와 못 읽었다가 같아 보이면 안 된다).
     */
    private suspend fun handleExplainCaseRequest(sessionKey: String, session: AgentSession, node: JsonNode) {
        val correlationId = node.path("messageId").asText(null)
        val testCaseId = node.path("testCaseId").asLong(0)
        try {
            val facts = caseFactService.explain(session.projectId, session.appUserId, testCaseId)
            logger.info(
                "케이스 설명 [sessionKey={}, caseId={}] 조작 {}건 · 관측가능={}",
                sessionKey, testCaseId, facts.operations.size, facts.observable,
            )
            sendFrame(
                sessionKey, session,
                CaseFactsResultFrame(
                    correlationId = correlationId,
                    testCaseId = facts.testCaseId,
                    scene = facts.scene,
                    stateBefore = facts.stateBefore.map { CaseGuardFrame(it.variable, it.operator, it.value) },
                    stateAfter = facts.stateAfter,
                    operations = facts.operations.map {
                        CaseOperationFrame(
                            capabilityId = it.capabilityId, input = it.input, label = it.label,
                            summary = it.summary, given = it.given, actionability = it.actionability,
                            matchedBy = it.matchedBy,
                        )
                    },
                    observable = facts.observable,
                    note = facts.note,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("케이스 설명 실패 [sessionKey=$sessionKey]: ${e.message}")
            sendFrame(
                sessionKey, session,
                TestCaseSearchErrorFrame(correlationId = correlationId, detail = "케이스 설명에 실패했습니다.")
            )
        }
    }

    /**
     * 툴 답 프레임을 보낸다. **한 세션에서는 한 번에 하나씩만 나간다** — 동시에 내보내면 싱크가
     * 거절하고 그 답이 사라진다(위 [AgentSession.sendLock] 참고).
     */
    private suspend fun sendFrame(sessionKey: String, session: AgentSession, frame: Any) {
        val json = objectMapper.writeValueAsString(frame)
        trace.record(session.runId, "  ◀ 답한다", json)
        val result = session.sendLock.withLock { session.outbound.tryEmitNext(json) }
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
        val scenarios = if (pending != null) repaired(pending.scenarios, incoming) else incoming
        val reviewed = if (pending != null) mergeVerdicts(pending.reviewed, event.reviewed) else event.reviewed

        progress(sessionKey, AuthoringStage.CHECKING)
        val outcome = reconcileService.reconcile(
            session.runId, session.projectId, session.appUserId, scenarios, reviewed,
        )
        if (!outcome.rejected) {
            progress(sessionKey, AuthoringStage.SAVED)
            if (pending != null) {
                saveAndNotify(
                    sessionKey, session,
                    "빠졌던 부분을 다시 작성해 시나리오에 반영했습니다."
                )
            }
            // 메우지 못한 구간은 **말로도** 알린다(ARTEL-468). 스텝에만 미상이라고 적어 두면
            // 사용자는 스크롤해서 찾기 전까지 모르고, 프로토타입에서 코드가 조용히 채우자 "모른다"고
            // 말한 실행이 4/5에서 1/5로 줄었다.
            if (outcome.notices.isNotEmpty()) {
                saveMessage(
                    session.runId, session.appUserId, "ASSISTANT",
                    outcome.notices.joinToString("\n")
                )
            }
            // 되묻는다(ARTEL-487). **저장은 이미 끝났다** — 답하지 않아도 결과물은 남고, 답하면
            // 다음 턴이 그 답을 받아 고친다. 코드가 아는 것이라 보기까지 계산돼 있다.
            ask(sessionKey, session, outcome.questions.ifEmpty { listOfNotNull(outcome.question) })
            // 저장이 있었던 턴에만 잔량을 알린다. 질문·거절 턴에서는 남은 수가 그대로일 뿐 아니라,
            // 방금 에이전트가 같은 값을 더 자세히 답했을 수 있다 — 그 뒤에 한 줄을 더 붙이면 같은
            // 말을 두 번 하는 셈이 된다.
            if (outcome.applied > 0) recommendRemaining(sessionKey, session)
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
            progress(sessionKey, AuthoringStage.BLOCKED)
            saveAndNotify(sessionKey, session, outcome.findings.rejectionMessage())
            return
        }

        session.repair = PendingRepair(scenarios, reviewed, attempts)
        // 재작성을 시켰다는 사실을 사용자에게 알린다. 이 시간 동안 화면은 답을 기다리는 것처럼
        // 보이는데, 무슨 일이 일어나는지 말하지 않으면 그냥 느린 것과 구분되지 않는다.
        progress(sessionKey, AuthoringStage.REPAIRING)
        saveAndNotify(
            sessionKey, session,
            "검토 결과 ${outcome.findings.summary()} — 그 부분만 다시 작성하도록 요청했습니다."
        )
        // **앞서 낸 것을 구조로 보여 준다**(ARTEL-633). 산문으로 적었더니 모델이 구조 필드를
        // 먼저 보고 "목록이 비어 있다"며 손을 놓았다(런 181).
        sendTurn(sessionKey, session, repairPrompt(outcome.findings), asCurrent(scenarios))
        // 재작성도 턴이다 — 답이 오지 않으면 똑같이 멎는다(ARTEL-510).
        watch(sessionKey, session.runId, session.appUserId)
    }

    /**
     * 재작성 결과를 앞서 낸 것과 합친다 — **제목이 같으면 갈아끼운다**(ARTEL-629).
     *
     * 앞서는 그냥 이어 붙였다. 그러면 빠진 케이스가 **자기만 담은 새 시나리오**로 떨어져 나온다 —
     * 실측(런 177)에서 모델이 여정 7개를 냈는데 검수가 2건 누락을 지적했고, 재작성이 그 둘을 각각
     * 새 카드로 만들어 최종 13개가 됐다. 1~2스텝짜리가 그렇게 생긴다.
     *
     * 여정에 스텝을 더하는 것은 **그 여정을 고치는 일**이지 새 여정을 만드는 일이 아니다. 저장이
     * 안 된 상태라 `scenario_id` 가 없으므로 제목으로 맞춘다 — 재작성 지시문이 "제목을 그대로 두고
     * 통째로 다시 내라"고 시키는 것과 짝이다.
     *
     * 제목이 안 맞으면 예전처럼 새로 붙인다. 정말 새 여정일 수도 있고, 그때 잃는 것보다 억지로
     * 갖다 붙일 때 잃는 것이 크다.
     */
    private fun repaired(
        before: List<ScenarioResult>,
        incoming: List<ScenarioResult>,
    ): List<ScenarioResult> {
        val byTitle = incoming.associateBy { it.title.trim() }
        val replaced = before.map { byTitle[it.title.trim()] ?: it }
        val taken = before.map { it.title.trim() }.toSet()
        return replaced + incoming.filterNot { it.title.trim() in taken }
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
    /**
     * 앞서 낸 것을 **`current_scenarios` 로** 보여 준다(ARTEL-633).
     *
     * 저장 전이라 번호가 없다 — `scenario_id` 는 null 이고, 제목이 짝을 맞추는 열쇠다.
     */
    private fun asCurrent(authored: List<ScenarioResult>): List<CurrentScenario> =
        authored.map { CurrentScenario(null, it.title, it.description, it.steps) }

    private fun repairPrompt(findings: ScenarioCoverageAudit.Findings): String = buildString {
        append("앞서 낸 시나리오는 `current_scenarios` 에 있습니다 — 아직 저장 전이라 ")
        append("`scenario_id` 가 없고, **제목이 짝을 맞추는 열쇠**입니다.\n\n")
        if (findings.missing.isNotEmpty()) {
            append("이전 응답에서 관련 있다고 판단한 케이스 중 ")
            append(findings.missing.joinToString(", "))
            append("번이 어떤 스텝에도 담기지 않았습니다. ")
            append("**앞서 낸 시나리오 중 그 케이스가 속하는 흐름에 스텝을 더해, 그 시나리오를 제목 그대로 통째로 다시 내 주세요** ")
            append("— 제목이 같으면 앞서 낸 것을 갈아끼웁니다. 어디에도 속하지 않을 때만 새 시나리오로 내 주세요(scenario_id는 null). ")
            append("스텝 한둘짜리 카드가 따로 생기면 읽는 사람이 무엇을 검증하는 흐름인지 알 수 없습니다. ")
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
    private suspend fun recommendRemaining(sessionKey: String, session: AgentSession) {
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

        // **어디로 가면 되는지로 말한다**(ARTEL-631). 씬별 개수를 늘어놓으면 읽는 사람이 그것을
        // 다시 "그럼 무엇을 하지"로 옮겨야 한다 — 카테고리 나열이 연결을 끊는 그 자리다. 코드가
        // 여정 이름을 지어낼 수는 없으므로, **가장 많이 남은 화면 하나를 시작점으로** 가리키고
        // 흐름을 짜는 일은 저작에 맡긴다.
        val biggest = uncovered.first()

        saveAndNotify(
            sessionKey, session,
            "아직 어떤 시나리오에도 담기지 않은 케이스가 ${total}건 남았습니다 — " +
                "${biggest.scene} 에 ${biggest.count}건이 몰려 있습니다. " +
                "거기서 이어지는 흐름부터 만들까요?"
        )
    }

    /**
     * 저작 한 턴이 지금 어느 단계인지 알린다(ARTEL-419).
     *
     * 실패해도 아무 일도 하지 않는다. 스트림이 없다는 것은 그 화면을 보는 사람이 없다는 뜻이고,
     * **보는 사람이 없다는 이유로 저작이 멈춰서는 안 된다.** [TestScenarioStreamManager.emit]이 이미
     * 경고를 남기므로 여기서 더 할 말도 없다.
     */
    /**
     * 에이전트에서 들어온 프레임을 기록에 남긴다(ARTEL-650).
     *
     * **단계 알림은 적지 않는다.** 한 판에 수십 번 오고, 무엇이 일어났는지는 그 앞뒤의 물음과
     * 답이 이미 말한다 — 적으면 읽을 것이 그것으로 덮인다.
     *
     * 턴 결과는 옆 파일로 뺀다. 모델이 낸 원문이 **이 서버가 손대기 전의 모양**이라, 나중에
     * 저장된 것과 나란히 놓고 무엇이 바뀌었는지 보는 자리가 바로 여기다.
     */
    private fun traceInbound(session: AgentSession, node: JsonNode, payloadText: String) {
        when (val type = node.path("type").asText()) {
            "progress" -> return
            "result" -> {
                val scenarios = node.path("scenarios")
                val titles = scenarios.joinToString("\n") { s ->
                    "  · ${s.path("title").asText()} — 스텝 ${s.path("steps").size()}"
                }
                trace.record(
                    session.runId, "◀ 답을 냈다",
                    "시나리오 ${scenarios.size()}개 " +
                        trace.blob(session.runId, "answer-${session.answers++}.json", payloadText) +
                        (if (titles.isBlank()) "" else "\n$titles") +
                        "\n말: ${node.path("message").asText("")}",
                )
            }
            else -> trace.record(session.runId, "  ▶ 묻는다 ($type)", payloadText)
        }
    }

    private fun progress(sessionKey: String, stage: AuthoringStage) {
        streamManager.emit(sessionKey, ScenarioStreamEvent(type = "progress", stage = stage))
    }

    /**
     * **서버가** 쓴 ASSISTANT 메시지를 저장하고 동시에 화면으로 흘린다(ARTEL-419).
     *
     * 저장만 하던 것을 고친다. 재작성 통보·검사 실패·잔량 안내는 모두 결과(`result`)를 중계한 **뒤에**
     * 만들어지는데, 그때 화면은 이미 답을 다 받았다고 여기고 더 기다리지 않는다. 그래서 이 문장들은
     * 새로고침하기 전에는 보이지 않았다 — 특히 "한 줄도 저장하지 않았습니다"가 그랬고, 그건 사용자가
     * 가장 즉시 알아야 하는 문장이다.
     *
     * `result` 대신 `notice`를 쓰는 이유는 이 이벤트가 턴의 끝이 아니기 때문이다. `result`로 보내면
     * 화면이 기다림을 끝내 버리는데, 재작성 통보 뒤에는 결과가 한 번 더 온다.
     */
    private suspend fun saveAndNotify(sessionKey: String, session: AgentSession, content: String) {
        saveMessage(session.runId, session.appUserId, "ASSISTANT", content)
        streamManager.emit(sessionKey, ScenarioStreamEvent(type = "notice", message = content))
    }

    /**
     * 이 런에 마지막으로 저장된 질문. 세션이 끊겨도 답을 잇기 위한 자리다.
     *
     * **트리에서 직접 읽는다.** 저장 본문은 화면이 읽는 모양(`kind`·`source: "code"`)이라 타입에
     * 그대로 붙지 않는다 — 클래스에 맞춰 저장 모양을 바꾸면 이번엔 화면이 못 읽는다. 읽는 쪽이
     * 하나뿐인 값이므로 여기서 풀어 쓴다.
     */
    private suspend fun lastQuestion(runId: Long, appUserId: Long, wanted: String? = null): ScenarioQuestion? = runCatching {
        val payload = runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList()
            .lastOrNull { it.payload != null }
            ?.payload ?: return null
        val whole = objectMapper.readTree(payload.asString())
        if (whole.path("kind").asText() != "question") return null
        // **묶음에서 그 질문을 찾는다**(ARTEL-630). 대화에는 첫 질문만 한 줄로 남지만 payload 는
        // 함께 낸 것을 다 들고 있다 — 그러지 않으면 둘째부터는 답할 길이 없다.
        val node = whole.path("questions").takeIf { it.isArray && !it.isEmpty }
            ?.firstOrNull { wanted == null || it.path("id").asText() == wanted }
            ?: whole
        val id = node.path("id").asText("")
        val text = node.path("text").asText("")
        if (id.isBlank() || text.isBlank()) return null
        ScenarioQuestion(
            id = id,
            text = text,
            why = node.path("why").asText(null),
            options = node.path("options").map {
                ScenarioQuestionOption(
                    id = it.path("id").asText(""),
                    label = it.path("label").asText(""),
                    detail = it.path("detail").asText(null),
                )
            },
            allowFreeText = node.path("allow_free_text").asBoolean(true),
            source = if (node.path("source").asText() == "agent") ScenarioQuestionSource.AGENT
            else ScenarioQuestionSource.CODE,
        )
    }.onFailure { logger.warn("저장된 질문을 읽지 못했다 — 평범한 메시지로 다룬다: ${it.message}") }
        .getOrNull()

    /**
     * 질문 카드로 답한 턴에 대화에 남길 말. 고른 문구와 적은 문장이 곧 사용자가 한 말이다.
     *
     * 적은 문장도 남긴다. 보기만 세다가 자유 서술을 빠뜨렸더니 말풍선이 **빈 줄**로 저장됐다 —
     * 사용자는 방법을 적었는데 대화에는 아무 말도 하지 않은 것으로 남았다.
     */
    private fun answerSummary(pending: ScenarioQuestion?, answer: ScenarioQuestionAnswer?): String {
        if (answer == null || pending == null || pending.id != answer.questionId) return ""
        val chosen = pending.options.filter { it.id in answer.optionIds }.map { it.label }
        val said = answer.text?.trim().orEmpty()
        return (chosen + listOfNotNull(said.ifBlank { null })).joinToString(" / ")
    }

    /**
     * 이 런에서 이미 답한 질문의 id. 대화에 `kind=answered` 로 남아 있다(ARTEL-487).
     *
     * 읽지 못하면 빈 집합이다 — 그때는 되풀이 안내가 없을 뿐, 빈 턴을 보내지는 않는다.
     */
    private suspend fun answeredQuestionIds(runId: Long, appUserId: Long): Set<String> = runCatching {
        runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList()
            .mapNotNull { row -> row.payload?.let { objectMapper.readTree(it.asString()) } }
            .filter { it.path("kind").asText() == "answered" }
            .mapNotNull { it.path("id").asText().takeIf(String::isNotBlank) }
            .toSet()
    }.getOrDefault(emptySet())

    /** 이번 답이 거절인가. 규칙은 [ScenarioDeclineReply] 에 있다. */
    private fun declined(pending: ScenarioQuestion?, answer: ScenarioQuestionAnswer?, userInput: String): Boolean {
        if (answer == null || pending == null || answer.questionId != pending.id) return false
        val said = userInput.isNotBlank() || !answer.text.isNullOrBlank()
        return ScenarioDeclineReply.isDecline(pending, answer.optionIds, said)
    }

    /**
     * 그대로 두기로 했다고 알린다. **다음 수를 함께 준다** — 거절이 막다른 길이 되면, 사용자는
     * 마음이 바뀌었을 때 무엇을 말해야 하는지 스스로 지어내야 한다.
     *
     * `answered` 로 남겨 **같은 질문이 다시 나가지 않게 한다**(ScenarioReconcileService 가 읽는다).
     */
    /** 이번에 함께 낸 질문들. 세션이 없으면(재접속 뒤) 거절한 그 하나만 덮는다. */
    private fun askedBatch(sessionKey: String, question: ScenarioQuestion): List<String> =
        sessions[sessionKey]?.asked?.takeIf { question.id in it } ?: listOf(question.id)

    private suspend fun notifyDeclined(
        sessionKey: String,
        runId: Long,
        appUserId: Long,
        question: ScenarioQuestion,
        batch: List<String> = listOf(question.id),
    ) {
        val message = ScenarioDeclineReply.advice(question)
        // **거절은 함께 낸 묶음 전체를 덮는다**(ARTEL-630). 한 번에 다 보여 준 뒤 "그대로 두기"를
        // 누른 것이므로, 다음 턴에 그 묶음의 다른 질문을 다시 내면 같은 것을 또 묻는 셈이다 —
        // 하나만 묻던 때 지키던 약속이 목록이 되면서 깨지는 자리다(ARTEL-487).
        batch.forEach { id ->
            saveMessage(runId, appUserId, "ASSISTANT", message, mapOf("kind" to "answered", "id" to id))
        }
        streamManager.emit(sessionKey, ScenarioStreamEvent(type = "notice", message = message))
        progress(sessionKey, AuthoringStage.SAVED)
        logger.info("되묻기 거절 — 모델을 부르지 않는다 [runId={}, id={}]", runId, question.id)
    }

    /**
     * 미상 구간을 물었고 **방법을 들었으면** 그 말을 돌려준다(ARTEL-487). 아니면 null.
     *
     * "그대로 두기"는 방법이 아니다 — 채우지 않는 것이 답이므로 평소대로 모델에게 넘긴다.
     *
     * **질문 카드로 답한 것만** 본다. 대화창에 그냥 쓴 말은 답일 수도, 딴 요청일 수도 있어서
     * 코드가 시나리오를 고칠 근거가 되지 못한다 — 그건 지금처럼 모델이 판단한다.
     */
    private fun gapHowTo(pending: ScenarioQuestion?, answer: ScenarioQuestionAnswer?): String? {
        if (pending == null || !pending.id.startsWith(ScenarioQuestionBuilder.GAP_PREFIX)) return null
        if (answer == null || answer.questionId != pending.id) return null
        if (ScenarioQuestionBuilder.GAP_LEAVE in answer.optionIds) return null
        val said = answer.text?.trim().orEmpty()
        if (ScenarioQuestionBuilder.GAP_AUTO in answer.optionIds) {
            return said.ifBlank { ScenarioQuestionBuilder.AUTOMATIC_HOP }
        }
        return said.ifBlank { null }
    }

    /**
     * 고른 보기를 **모델이 읽을 수 있는 한 줄**로 만든다(ARTEL-487).
     *
     * 보기 id 는 화면과 오케 사이의 값이지 모델의 어휘가 아니다. 질문과 고른 문구를 다시 붙여야
     * 모델이 무엇에 답한 것인지 안다.
     *
     * 질문이 남아 있지 않거나 id 가 어긋나면 **답으로 다루지 않는다** — 오래된 화면이 지난 질문의
     * 답을 보낼 수 있고, 그때 턴을 잃는 것보다 사용자가 적은 말만 그대로 보내는 편이 낫다.
     */
    private fun answerText(pending: ScenarioQuestion?, answer: ScenarioQuestionAnswer?): String? {
        if (answer == null || pending == null || pending.id != answer.questionId) return null
        val chosen = pending.options.filter { it.id in answer.optionIds }.map { it.label }
        val said = answer.text?.trim().orEmpty()
        val body = (chosen + listOfNotNull(said.ifBlank { null })).joinToString(" / ")
        if (body.isBlank()) return null
        return "앞서 물어본 것(${pending.text})에 대한 답: $body"
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
        trace.record(
            session.runId, "▶ 턴을 보낸다",
            "$userInput\n지금 시나리오 ${currentScenarios.size}개를 함께 보여 준다",
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
            // **무엇을 듣든 살아 있다는 뜻이다**(ARTEL-632). 시한은 이 시각을 기준으로 잰다 —
            // 도구를 마흔 번 부르며 일하는 턴을 "멎었다"고 말하면 안 된다.
            session?.lastHeard = System.currentTimeMillis()
            session?.let { traceInbound(it, node, payloadText) }
            if (node.path("type").asText() == "uncovered_cases") {
                if (session == null) {
                    logger.warn("uncovered_cases를 받았지만 세션이 없어 무시 [sessionKey=$sessionKey]")
                    return
                }
                // 도구 프레임은 "멎지 않았다"의 첫 증거다(ARTEL-419). 턴을 보낸 뒤 여기까지는
                // 오케스트레이션이 아무것도 보지 못하므로, 이 한 줄이 침묵과 진행을 가른다.
                progress(sessionKey, AuthoringStage.LOOKING_UP_CASES)
                scope.launch { handleUncoveredRequest(sessionKey, session, node) }
                return
            }
            if (node.path("type").asText() == "explain_case") {
                if (session == null) {
                    logger.warn("explain_case를 받았지만 세션이 없어 무시 [sessionKey=$sessionKey]")
                    return
                }
                progress(sessionKey, AuthoringStage.READING_CASE)
                scope.launch { handleExplainCaseRequest(sessionKey, session, node) }
                return
            }
            if (node.path("type").asText() == "find_path") {
                if (session == null) {
                    logger.warn("find_path를 받았지만 세션이 없어 무시 [sessionKey=$sessionKey]")
                    return
                }
                progress(sessionKey, AuthoringStage.FINDING_PATH)
                scope.launch { handleFindPathRequest(sessionKey, session, node) }
                return
            }
            // Agent 가 알려 주는 단계(ARTEL-487). **모르는 값은 버린다** — 단계는 알리는 것이지
            // 시키는 것이 아니라서, 구버전 오케가 새 단계를 받아 터지느니 그 줄만 없는 편이 낫다.
            if (node.path("type").asText() == "progress") {
                val wire = node.path("stage").asText("")
                val stage = AuthoringStage.entries.firstOrNull { it.wire == wire }
                if (stage == null) logger.debug("모르는 단계라 흘려보낸다 [{}] {}", sessionKey, wire)
                else progress(sessionKey, stage)
                return
            }
            if (node.path("type").asText() == "test_case_search") {
                if (session == null) {
                    logger.warn("test_case_search를 받았지만 세션이 없어 무시 [sessionKey=$sessionKey]")
                    return
                }
                progress(sessionKey, AuthoringStage.LOOKING_UP_CASES)
                // 검색은 임베딩(Agent /embed) 호출을 동반하는 suspend라 코루틴으로 넘긴다.
                scope.launch { handleCaseSearch(sessionKey, session, node) }
                return
            }

            // Agent 응답을 타입화한 봉투로 파싱해 SSE로 중계한다(type=result|error).
            val event = objectMapper.readValue(payloadText, ScenarioStreamEvent::class.java)
            // 결과든 오류든 **턴은 여기서 끝난다.** 감시를 풀지 않으면 잠시 뒤 "멎었다"고
            // 말하게 되고, 그건 방금 답을 받은 사용자에게 거짓말이다.
            stopWatching(sessionKey)
            streamManager.emit(sessionKey, event)

            if (event.type == "result" && session != null) {
                scope.launch {
                    // Agent 메시지를 ASSISTANT 채팅으로 저장.
                    try {
                        saveMessage(session.runId, session.appUserId, "ASSISTANT", event.message ?: "")
                        // 모델이 스스로 물은 것도 같은 모양으로 나간다 — 화면이 두 벌을 그릴
                        // 이유가 없고, 답이 돌아오는 길도 하나여야 한다.
                        fromAgent(event.question)?.let { ask(sessionKey, session, listOf(it)) }
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
            progress(sessionKey, AuthoringStage.WRITING)
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
    private suspend fun saveMessage(
        runId: Long,
        appUserId: Long,
        role: String,
        content: String,
        payload: Map<String, Any?>? = null,
    ) {
        runMessageRepository.save(
            TestRunMessageEntity(
                testRunId = runId,
                appUserId = appUserId,
                role = role,
                content = content,
                payload = payload?.let { Json.of(objectMapper.writeValueAsString(it)) },
            )
        )
    }

    /**
     * 모델이 낸 질문을 우리 것과 같은 모양으로 맞춘다(ARTEL-487).
     *
     * 출처를 여기서 못 박는 이유는, 프레임에 적힌 값을 그대로 믿으면 모델이 자기 질문을 코드가
     * 계산한 것처럼 표시할 수 있기 때문이다 — 근거 있는 질문과 그렇지 않은 질문의 구분이 이
     * 한 필드에 걸려 있다.
     *
     * 물음이 비었거나 id 가 없으면 **묻지 않는다.** 빈 질문은 사용자에게 답할 수 없는 것을
     * 내미는 일이고, id 가 없으면 답이 돌아와도 무엇에 대한 것인지 잇지 못한다.
     */
    private fun fromAgent(question: ScenarioQuestion?): ScenarioQuestion? {
        if (question == null) return null
        if (question.text.isBlank()) {
            logger.warn("빈 질문이 와서 묻지 않는다")
            return null
        }
        val id = question.id.ifBlank { "agent:" + question.text.hashCode().toString(16) }
        return question.copy(id = id, source = ScenarioQuestionSource.AGENT)
    }

    /**
     * 미상 구간을 사용자 말로 채웠다고 알린다.
     *
     * 채팅에 한 줄 남기고 화면에 `applied` 를 흘린다. 시나리오가 바뀌었는데 화면이 그대로면
     * 사용자는 알림 블록이 여전히 거기 있는 줄 알고 같은 답을 또 하게 된다.
     */
    private suspend fun notifyGapFilled(
        sessionKey: String,
        runId: Long,
        appUserId: Long,
        blockedBy: String,
        written: List<String>,
    ) {
        // 무엇이 들어갔는지 그대로 보여 준다. 다듬은 문장이 사용자가 적은 말과 다를 수 있고,
        // 그 차이를 감추면 시나리오를 열어 보기 전까지 무엇이 저장됐는지 알 수 없다.
        val quoted = written.joinToString("\n") { "· $it" }
        val message = "$blockedBy 구간을 알려 주신 대로 채웠습니다.\n$quoted\n" +
            "이 스텝은 명세가 아니라 사용자가 알려 준 것이라 실행이 통과/실패를 매기지 않습니다."
        // **답이 끝났다고 기록에 남긴다.** 저장된 질문은 `payload` 가 붙은 마지막 메시지로 찾으므로,
        // 여기서 다른 `kind` 를 남기지 않으면 이미 답한 질문이 다음 턴의 맥락으로 또 따라붙는다.
        saveMessage(
            runId, appUserId, "ASSISTANT", message,
            mapOf("kind" to "answered", "id" to "${ScenarioQuestionBuilder.GAP_PREFIX}$blockedBy"),
        )
        streamManager.emit(sessionKey, ScenarioStreamEvent(type = "notice", message = message))
        streamManager.emit(sessionKey, ScenarioStreamEvent(type = "applied"))
        progress(sessionKey, AuthoringStage.SAVED)
    }

    /**
     * 저장 경로가 어디였든 **알림과 질문을 같은 자리로 흘린다**(ARTEL-487).
     *
     * 카드 검토 모드는 챗봇이 아니라 REST 로 저장한다(`/scenarios/commit`). 그 경로가 반영 건수만
     * 돌려주는 바람에, 검수가 계산해 둔 알림과 질문이 조용히 버려지고 있었다 — 사용자에게는 GAP
     * 스텝만 남고 왜 그런지도, 무엇을 답하면 되는지도 보이지 않았다. 실제로 런 32 에서 그렇게 나왔다.
     *
     * 세션이 없어도 저장은 한다. 대화 기록에 남아야 새로고침한 화면이 그 질문을 다시 띄운다.
     */
    suspend fun deliver(
        appUserId: Long,
        runId: Long,
        notices: List<String>,
        question: ScenarioQuestion?,
    ) {
        val sessionKey = "$appUserId:$runId"
        val session = sessions[sessionKey]
        if (notices.isNotEmpty()) {
            saveMessage(runId, appUserId, "ASSISTANT", notices.joinToString("\n"))
            streamManager.emit(
                sessionKey,
                ScenarioStreamEvent(type = "notice", message = notices.joinToString("\n")),
            )
        }
        if (question == null) return
        session?.question = question
        saveMessage(runId, appUserId, "ASSISTANT", question.text, question.payload())
        streamManager.emit(sessionKey, ScenarioStreamEvent(type = "question", question = question))
        logger.info(
            "되물음(카드 저장) [runId={}, id={}, 보기={}건]", runId, question.id, question.options.size,
        )
    }

    /**
     * 사용자에게 되묻는다(ARTEL-487).
     *
     * **저장하고 흘린다.** 선택지를 SSE 로만 보내면 새로고침 한 번에 사라져, 질문은 기록에 남았는데
     * 답할 방법만 없어진다. 문장은 채팅 본문으로 그대로 남고 누를 것은 payload 가 든다.
     *
     * 세션에 하나만 매단다. 여러 개를 쌓으면 사용자는 어느 것에 답한 것인지 말해 줄 방법이 없다.
     */
    /**
     * **모르는 자리를 한 번에 낸다**(ARTEL-630).
     *
     * 앞서는 하나만 물었다. 막힌 자리가 여럿이면 나머지는 아무 말 없이 미상으로 남고, 사용자는
     * 시나리오가 완성된 줄 안다 — 실측(런 178)에서 못 간다고 적은 자리가 일곱인데 물은 것은
     * 하나였다. 한 번에 보여 주면 아는 것만 답하고 나머지는 그대로 둘 수 있다.
     *
     * 대화에는 **첫 질문만** 한 줄로 남긴다. 일곱 줄이 붙으면 대화가 질문지에 묻히고, 목록은
     * 화면이 한 자리에서 그릴 것이라 대화에 되풀이할 이유가 없다.
     *
     * [AgentSession.question] 도 첫 것을 문다 — 답을 받아 다음 턴에 넘기는 경로가 하나짜리다.
     * 나머지에 답하는 길은 화면이 그 id 로 보내는 것이고, 그 자리는 아직 없다.
     */
    private suspend fun ask(sessionKey: String, session: AgentSession, questions: List<ScenarioQuestion>) {
        val first = questions.firstOrNull() ?: return
        session.question = first
        session.asked = questions.map { it.id }
        saveMessage(session.runId, session.appUserId, "ASSISTANT", first.text, ScenarioQuestion.batchPayload(questions))
        streamManager.emit(
            sessionKey,
            ScenarioStreamEvent(type = "question", question = first, questions = questions),
        )
        logger.info(
            "되물음 [sessionKey={}, {}건, 첫 id={}, 출처={}]",
            sessionKey, questions.size, first.id, first.source,
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
        /**
         * WS 송신 직렬화용 잠금.
         *
         * Reactor 싱크는 **동시 방출을 허용하지 않는다.** 한 턴에 툴 호출이 여러 개 오면 각 답을
         * 별도 코루틴이 내보내고, 그때 `tryEmitNext` 가 `FAIL_NON_SERIALIZED` 로 떨어져 **답 프레임이
         * 조용히 사라진다.** 에이전트는 그 답을 못 받은 채 타임아웃까지 기다렸다가 "조회가 안 됐다"로
         * 진행한다 — 모델 탓이 아닌 변동이고, 같은 요청이 매번 다른 결과를 내는 원인이 된다.
         */
        val sendLock: Mutex = Mutex(),

        @Volatile var disposable: Disposable? = null,
        /** 검수에서 막혀 재작성을 기다리는 중인 결과. null이면 평범한 턴이다. */
        @Volatile var repair: PendingRepair? = null,
        /**
         * 답을 기다리는 질문(ARTEL-487). **턴을 넘어 산다** — 질문은 이번 턴에 나가고 답은 다음
         * 턴에 오므로, 여기 없으면 답이 무엇에 대한 것인지 잇지 못한다.
         *
         * 하나만 둔다. 여러 개를 쌓으면 사용자는 어느 것에 답한 것인지 말해 줄 방법이 없다.
         */
        @Volatile var question: ScenarioQuestion? = null,
        /**
         * 에이전트에게서 **마지막으로 무언가 들은 때**(ARTEL-632). 시한은 이것을 기준으로 잰다 —
         * 재는 것은 "죽었나"이지 "느린가"가 아니다.
         */
        @Volatile var lastHeard: Long = System.currentTimeMillis(),
        /**
         * 이번에 **함께 낸 질문들의 id**(ARTEL-630).
         *
         * 거절은 묶음 전체를 덮는다 — 한 번에 다 보여 준 뒤 "그대로 두기"를 누른 것이므로, 다음
         * 턴에 같은 묶음의 다른 질문을 내면 같은 것을 또 묻는 셈이다.
         */
        @Volatile var asked: List<String> = emptyList(),
        /**
         * 마지막으로 사용자에게 알린 미커버 건수. 같은 수를 두 번 말하지 않기 위한 값이다 —
         * 좁은 작업을 이어가는 대화에서 매 턴 같은 줄이 붙으면 읽히지 않는다.
         */
        @Volatile var lastReportedUncovered: Long? = null,
        /**
         * 답을 기다리는 턴의 감시 타이머(ARTEL-510). null 이면 **이 세션은 지금 한가하다.**
         *
         * 두 가지를 이것 하나로 안다: 턴이 도는 중인지(겹친 요청을 받지 않는다), 그리고 언제부터
         * 아무 소식도 없는지(멎었다고 말해 준다).
         */
        @Volatile var watchdog: Job? = null,
        /**
         * 이 판에서 에이전트가 답을 낸 횟수(ARTEL-650). 기록의 첨부 파일 이름을 가른다 — 한 판에
         * 답이 여럿 나오고(재작성), 그 둘을 나란히 놓고 보는 것이 이 기록의 쓸모다.
         */
        @Volatile var answers: Int = 0,
    ) {
        val busy: Boolean get() = watchdog?.isActive == true
    }

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

        /**
         * 답을 얼마나 기다릴지(ARTEL-510).
         *
         * 배포마다 다를 값이 아니라 **사람이 아무 말 없이 기다릴 수 있는 시간의 상한**이다. 실측한
         * 저작 턴은 66건 프로젝트에서 30~70초였고, 에이전트 쪽 모델 호출 자체가 180초에서 끊긴다.
         * 5분은 그 위에 넉넉히 얹은 값이라, 여기 걸린 턴은 느린 것이 아니라 오지 않는 것이다.
         */
        private const val TURN_DEADLINE_MILLIS = 5L * 60 * 1000
    }
}
