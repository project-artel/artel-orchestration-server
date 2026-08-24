package kr.artel.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.common.embedding.agent.EmbedResponse
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.config.TestCaseEmbeddingProperties
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** V23의 vector(1024)와 같아야 한다. */
private const val DIMENSIONS = 1024

/** 좌표축 단위 벡터. 같은 축은 코사인 거리 0(완전 일치). */
private fun axis(index: Int): List<Double> = List(DIMENSIONS) { if (it == index) 1.0 else 0.0 }

/**
 * 작성 세션의 케이스 검색 루프와 결과 반영(재설계 2026-08-07 — 시나리오 = steps 리스트).
 *
 * 실제 Agent를 흉내내는 목 서버(POST /sessions + WS)로 검증한다. 목은 WS 연결 시 [framesToSend]에
 * 담긴 프레임을 그대로 보내고, Orchestration이 되돌린 프레임을 [receivedFrames]에 기록한다.
 *
 * 검증:
 * (a) 인입 `test_case_search` → `test_case_search_result`(correlationId + 히트).
 * (b) `result{scenarios:[…]}` → test_scenario(본문 steps) + test_run_scenario 올바른 position으로 INSERT.
 * (c) `result{scenarios:[]}` → DB 무변경(안전규칙 가드).
 * (d) `scenario_id` 있는 결과 → 기존 시나리오 본문 UPDATE.
 * (e) autoApply=false → 자동저장 안 함, 커밋 엔드포인트로만 반영.
 * (f) 실행 계약: `agentScenario`가 steps를 읽어 caseId 스텝에 TC 내용을 리졸브해 넘긴다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestScenarioReconcileIntegrationTest {

    /** 검색어를 미리 등록한 벡터로 바꾸는 대역. */
    class FixedQueryEmbeddingClient(private val model: String) : EmbeddingClient {
        val vectorsByQuery: MutableMap<String, List<Double>> = mutableMapOf()

        override suspend fun embed(texts: List<String>): EmbedResponse {
            val vectors = texts.map {
                vectorsByQuery[it] ?: throw IllegalStateException("등록되지 않은 검색어: $it")
            }
            return EmbedResponse(model = model, dimensions = DIMENSIONS, vectors = vectors)
        }
    }

    @TestConfiguration
    class FixedClientConfig {
        @Bean
        @Primary
        fun fixedQueryEmbeddingClient(properties: TestCaseEmbeddingProperties): FixedQueryEmbeddingClient =
            FixedQueryEmbeddingClient(properties.model)
    }

    @LocalServerPort private val port: Int = 0

    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var compositionService: kr.artel.orchestration.testscenario.service.ScenarioCompositionService
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var runMessageRepository: kr.artel.orchestration.testrun.repository.TestRunMessageRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var fake: FixedQueryEmbeddingClient
    @Autowired private lateinit var properties: TestCaseEmbeddingProperties

    private fun webClient() = WebClient.create("http://localhost:$port")
    private val model: String get() = properties.model

    private fun storedSteps(scenarioId: Long): List<kr.artel.orchestration.testscenario.dto.ScenarioStep> = runBlocking {
        scenarioRepository.findById(scenarioId)!!.toDraft(objectMapper).steps
    }

    companion object {
        private lateinit var mockAgent: DisposableServer
        private const val MOCK_SESSION_ID = "reconcile-sid"

        private val framesToSend = CopyOnWriteArrayList<String>()

        /**
         * 목이 **인입 턴에 답할** 프레임들. 하나 받을 때마다 앞에서 하나 꺼내 보낸다.
         *
         * 재작성 루프는 "결과 → (서버가 다시 시킴) → 결과"라 목이 대답을 할 줄 알아야 검증된다.
         * 비어 있으면 예전처럼 아무것도 답하지 않으므로 기존 테스트는 그대로 돈다.
         */
        private val turnReplies = java.util.concurrent.ConcurrentLinkedQueue<String>()
        private val receivedFrames = CopyOnWriteArrayList<String>()
        private val seq = AtomicLong(500_000)

        @JvmStatic
        @DynamicPropertySource
        fun registerAgentUrls(registry: DynamicPropertyRegistry) {
            mockAgent = HttpServer.create().port(0).route { routes ->
                routes.post("/sessions") { _, response ->
                    response.header("Content-Type", "application/json")
                        .sendString(Mono.just("""{"session_id":"$MOCK_SESSION_ID"}"""))
                        .then()
                }
                routes.ws("/sessions/{id}") { inbound, outbound ->
                    outbound.sendString(
                        Flux.concat(
                            Flux.fromIterable(framesToSend.toList()),
                            inbound.receive().asString()
                                .doOnNext { receivedFrames.add(it) }
                                .concatMap { Mono.justOrEmpty(turnReplies.poll()) }
                        )
                    ).then()
                }
            }.bindNow()
            registry.add("artel.agent.base-url") { "http://localhost:${mockAgent.port()}" }
            registry.add("artel.agent.ws-base-url") { "ws://localhost:${mockAgent.port()}" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            mockAgent.disposeNow()
        }
    }

    @BeforeEach
    fun reset() {
        framesToSend.clear()
        receivedFrames.clear()
        turnReplies.clear()
        fake.vectorsByQuery.clear()
    }

    // ---- (a) test_case_search → test_case_search_result -------------------------------------

    @Test
    fun `인입 test_case_search에 결과 프레임으로 답한다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val hitCase = insertCase(projectId, "RULE", "골드 차감")
        insertVector(hitCase, axis(0))
        fake.vectorsByQuery["골드"] = axis(0)

        framesToSend.add("""{"type":"test_case_search","messageId":"search-1","query":"골드","limit":5}""")
        postMessage(client, projectId, runId, token, "케이스 찾아줘")

        val frame = awaitFrame { it.contains("test_case_search_result") }
        assertThat(frame).isNotNull
        val node = objectMapper.readTree(frame)
        assertThat(node.get("type").asText()).isEqualTo("test_case_search_result")
        assertThat(node.get("correlationId").asText()).isEqualTo("search-1")
        val results = node.get("results")
        assertThat(results).hasSize(1)
        val hit = results.first()
        assertThat(hit.get("id").asText()).isEqualTo(hitCase.toString())
        assertThat(hit.get("step").asText()).isEqualTo("골드 차감")
        assertThat(hit.has("verificationStatus")).isTrue()
    }

    /**
     * 경로 조회 프레임에 답한다(ARTEL-466).
     *
     * 씬 명세가 없는 프로젝트라 답은 `UNKNOWN` 이다 — **그것이 정답이다.** 지어내지 않고
     * 무엇이 막는지를 말하는 것이 이 툴의 절반이고, 명세가 없는 프로젝트에서도 세션이 죽지
     * 않아야 한다.
     */
    @Test
    fun `인입 find_path에 결과 프레임으로 답한다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val a = insertCase(projectId, "Map_scene", "A")
        val b = insertCase(projectId, "Map_scene", "B")

        framesToSend.add(
            """{"type":"find_path","messageId":"path-1","from_case_id":$a,"to_case_id":$b}"""
        )
        postMessage(client, projectId, runId, token, "경로 좀")

        val frame = awaitFrame { it.contains("find_path_result") }
        assertThat(frame).isNotNull
        val node = objectMapper.readTree(frame)
        assertThat(node.get("type").asText()).isEqualTo("find_path_result")
        assertThat(node.get("correlationId").asText()).isEqualTo("path-1")
        assertThat(node.get("result").asText()).isEqualTo("UNKNOWN")
        assertThat(node.get("blockedBy").asText()).isEqualTo("content-map")
    }

    /** 인자가 빠진 프레임에도 에러로 답해 대기 중인 도구를 푼다. 세션은 죽지 않는다. */
    @Test
    fun `find_path에 케이스 id가 빠지면 에러 프레임으로 답한다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        framesToSend.add("""{"type":"find_path","messageId":"path-2"}""")
        postMessage(client, projectId, runId, token, "경로 좀")

        val frame = awaitFrame { it.contains("path-2") && it.contains("error") }
        assertThat(frame).isNotNull
        assertThat(objectMapper.readTree(frame).get("detail").asText()).contains("from_case_id")
    }

    // ---- (b) result{scenarios:[…]} → INSERT(본문 steps) ---------------------------------

    @Test
    fun `scenarios 결과를 런에 INSERT하고 position을 매긴다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val caseA = insertCase(projectId, "RULE", "A")
        val caseB = insertCase(projectId, "RULE", "B")
        val caseC = insertCase(projectId, "UI", "C")

        // 시나리오 = steps 리스트. 검증 스텝은 case_id를 달고, 사이 조작 스텝은 case_id 없음.
        framesToSend.add(
            """{"type":"result","message":"두 시나리오 만들었어","scenarios":[""" +
                """{"title":"구매 여정","description":"d1","steps":[{"action":"상점 이동"},{"action":"A확인","case_id":$caseA},{"action":"B확인","case_id":$caseB}]},""" +
                """{"title":"판매 여정","description":"d2","steps":[{"action":"C확인","case_id":$caseC}]}]}"""
        )
        postMessage(client, projectId, runId, token, "시나리오 만들어줘")

        awaitUntil { runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().size == 2 }

        val runLinks = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        assertThat(runLinks.map { it.position }).containsExactly(0, 1)

        val purchase = scenarioRepository.findById(runLinks[0].testScenarioId)!!
        val sale = scenarioRepository.findById(runLinks[1].testScenarioId)!!
        assertThat(purchase.projectId).isEqualTo(projectId)
        assertThat(purchase.title).isEqualTo("구매 여정")
        assertThat(sale.title).isEqualTo("판매 여정")

        // steps: 순서 유지, 검증 스텝의 caseId, 조작 스텝은 null.
        val purchaseSteps = storedSteps(purchase.id!!)
        assertThat(purchaseSteps.map { it.action }).containsExactly("상점 이동", "A확인", "B확인")
        assertThat(purchaseSteps.map { it.caseId }).containsExactly(null, caseA, caseB)
        assertThat(storedSteps(sale.id!!).map { it.caseId }).containsExactly(caseC)
    }

    // ---- (b2) 검수 누락 → 재작성 요청 → 합쳐서 저장 (ARTEL-403) ------------------------------

    /**
     * 판정해 놓고 안 담은 케이스가 있으면, 그것만 다시 쓰게 하고 **앞 결과와 합쳐 한 번에** 저장한다.
     *
     * 실측에서 나온 실패다(2026-08-13): word-venture 66건을 전부 쓰라고 시켰더니 에이전트가
     * "각 테스트 케이스를 빠짐없이 연결했습니다"라고 답하면서 1건을 빠뜨렸다. 그 1건이 자기가
     * `in`으로 판정한 케이스였다.
     *
     * E2E로는 이 경로를 부를 수 없다 — 누락이 확률적이라 같은 요청이 66/66으로 통과하기도 한다.
     * 그래서 여기서 못 박는다.
     */
    @Test
    fun `판정한 케이스가 빠지면 그것만 다시 쓰게 하고 합쳐서 저장한다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val caseA = insertCase(projectId, "RULE", "A")
        val caseB = insertCase(projectId, "RULE", "B")

        // 1차 결과: 둘 다 관련 있다고 판정해 놓고 A만 담았다.
        framesToSend.add(
            """{"type":"result","message":"빠짐없이 연결했습니다","reviewed":{"in":[$caseA,$caseB],"out":[]},""" +
                """"scenarios":[{"title":"첫 시나리오","description":"d","steps":[{"action":"A확인","case_id":$caseA}]}]}"""
        )
        // 재작성 턴에 대한 답: 빠졌던 B만 새 시나리오로.
        turnReplies.add(
            """{"type":"result","message":"B를 추가했습니다","reviewed":{"in":[$caseB],"out":[$caseA]},""" +
                """"scenarios":[{"title":"보강 시나리오","description":"d","steps":[{"action":"B확인","case_id":$caseB}]}]}"""
        )

        postMessage(client, projectId, runId, token, "전부 써줘")

        awaitUntil { runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().size == 2 }

        // 재작성 지시가 빠진 id를 지목했는지.
        val repairTurn = receivedFrames.firstOrNull { it.contains("\"type\":\"turn\"") }
        assertThat(repairTurn).isNotNull()
        assertThat(repairTurn!!).contains("$caseB")

        // 합쳐서 저장됐다: 앞 결과 + 보강분.
        val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        val titles = links.map { scenarioRepository.findById(it.testScenarioId)!!.title }
        assertThat(titles).containsExactly("첫 시나리오", "보강 시나리오")

        // 사용자에게 재작성 사실과 결과를 알렸는지 — 말하지 않으면 그냥 느린 것과 구분되지 않는다.
        val messages = runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId).toList()
            .map { it.content }
        assertThat(messages).anyMatch { it.contains("다시 작성하도록 요청") }
        assertThat(messages).anyMatch { it.contains("반영했습니다") }
    }

    /**
     * 재검수는 **처음 판정 기준**이다. 재작성 응답이 빠뜨린 케이스를 `out`으로 옮겨도 통과시키지 않는다 —
     * 그걸 허용하면 에이전트가 스스로 합격 기준을 낮출 수 있고, 검사가 있으나 마나가 된다.
     */
    @Test
    fun `재작성이 판정을 바꿔도 처음 선언 기준으로 다시 본다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val caseA = insertCase(projectId, "RULE", "A")
        val caseB = insertCase(projectId, "RULE", "B")

        framesToSend.add(
            """{"type":"result","message":"했습니다","reviewed":{"in":[$caseA,$caseB],"out":[]},""" +
                """"scenarios":[{"title":"첫 시나리오","description":"d","steps":[{"action":"A확인","case_id":$caseA}]}]}"""
        )
        // 재작성이랍시고 B를 out으로 옮겨 "이제 통과"라고 주장한다.
        turnReplies.add(
            """{"type":"result","message":"B는 관련 없었습니다","reviewed":{"in":[$caseA],"out":[$caseB]},"scenarios":[]}"""
        )

        postMessage(client, projectId, runId, token, "전부 써줘")

        awaitUntil {
            runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId).toList()
                .any { it.content.contains("저장하지 않았습니다") }
        }

        // 한 줄도 저장되지 않는다.
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()).isEmpty()
    }

    /**
     * **좁은 요청을 통째로 버리지 않는다.** 사용자가 일부만 짜 달라고 했을 때 에이전트가 나머지를
     * 판정하지 않으면 검토 누락이 뜨는데, 그 이유로 멀쩡한 시나리오를 거부하면 안 된다.
     * 판정만 더 받아 통과시킨다 — 스텝을 다시 쓸 필요가 없으니 값도 거의 안 든다.
     */
    @Test
    fun `좁은 요청에서 판정이 모자라면 판정만 더 받아 저장한다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val caseA = insertCase(projectId, "TitleScene", "A")
        val caseB = insertCase(projectId, "EndingScene", "B")

        // 타이틀만 짜 달라고 했고, 에이전트는 A만 판정하고 B는 아예 언급하지 않았다.
        framesToSend.add(
            """{"type":"result","message":"타이틀 흐름입니다","reviewed":{"in":[$caseA],"out":[]},""" +
                """"scenarios":[{"title":"타이틀","description":"d","steps":[{"action":"A확인","case_id":$caseA}]}]}"""
        )
        // 나머지에 대한 판정만 답한다(시나리오 없음).
        turnReplies.add(
            """{"type":"result","message":"B는 이번 요청과 무관합니다","reviewed":{"in":[],"out":[$caseB]},"scenarios":[]}"""
        )

        postMessage(client, projectId, runId, token, "타이틀 화면만 짜줘")

        awaitUntil { runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().size == 1 }

        // 판정만 보강해서 통과했고, 시나리오는 처음 것 그대로다.
        val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        assertThat(scenarioRepository.findById(links[0].testScenarioId)!!.title).isEqualTo("타이틀")
    }

    /**
     * 저작이 끝나면 아직 아무 시나리오도 건드리지 않은 케이스가 몇 건 남았는지 알린다.
     *
     * 이 수를 에이전트가 세게 하지 않는다 — 빠짐없이 세는 일이 에이전트가 못하는 일이라는 것이
     * 이 작업 전체의 전제다. 저장 직후의 DB가 답을 알고 있다.
     */
    @Test
    fun `저장 뒤 남은 미커버를 씬과 함께 알린다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val caseA = insertCase(projectId, "TitleScene", "A")
        val caseB = insertCase(projectId, "EndingScene", "B")
        val caseC = insertCase(projectId, "EndingScene", "C")

        framesToSend.add(
            """{"type":"result","message":"타이틀만 했습니다","reviewed":{"in":[$caseA],"out":[]},""" +
                """"scenarios":[{"title":"타이틀","description":"d","steps":[{"action":"A확인","case_id":$caseA}]}]}"""
        )
        turnReplies.add(
            """{"type":"result","message":"나머지는 이번 요청과 무관합니다","reviewed":{"in":[],"out":[$caseB,$caseC]},"scenarios":[]}"""
        )

        postMessage(client, projectId, runId, token, "타이틀만")

        awaitUntil {
            runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId).toList()
                .any { it.content.contains("남았습니다") }
        }

        val recommendation = runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList().first { it.content.contains("남았습니다") }.content
        // 건수와 씬을 함께 말한다 — id로 말하면 사람이 못 알아듣고, 번호는 화면에 내보내지도 않는다.
        assertThat(recommendation).contains("2건")
        assertThat(recommendation).contains("EndingScene")
        // 한 줄로 끝난다. 좁은 작업을 이어가는 사람에게 여러 줄은 소음이다.
        assertThat(recommendation.lines()).hasSize(1)
    }

    /**
     * 되묻고, 답을 받아 다음 턴에 잇는다(ARTEL-487).
     *
     * 지금까지 없던 것은 묻는 능력이 아니라 **질문을 담을 자리**였다. 산문 속 질문은 설명으로
     * 읽히고, 답해도 그 맥락이 다음 턴까지 살아남지 않았다.
     */
    @Test
    fun `덜 담긴 씬이 있으면 되묻고 답을 다음 턴에 잇는다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val caseA = insertCase(projectId, "TitleScene", "A")
        val caseB = insertCase(projectId, "TitleScene", "B")

        framesToSend.add(
            """{"type":"result","message":"A만 담았습니다","reviewed":{"in":[$caseA],"out":[$caseB]},""" +
                """"scenarios":[{"title":"타이틀","description":"d","steps":[{"action":"A확인","case_id":$caseA}]}]}"""
        )
        turnReplies.add("""{"type":"result","message":"넣었습니다","reviewed":{"in":[],"out":[]},"scenarios":[]}""")

        postMessage(client, projectId, runId, token, "타이틀 시나리오")

        awaitUntil {
            runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId).toList()
                .any { it.payload != null }
        }

        // 질문은 **저장된다.** SSE 로만 흘리면 새로고침 한 번에 선택지가 사라져, 질문은 기록에
        // 남았는데 답할 방법만 없어진다.
        val asked = runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList().first { it.payload != null }
        val payload = objectMapper.readTree(asked.payload!!.asString())
        assertThat(payload["kind"].asText()).isEqualTo("question")
        assertThat(payload["id"].asText()).startsWith("scope:")
        assertThat(payload["options"].map { it["id"].asText() }).contains("scene:TitleScene", "keep")

        // 저장은 막히지 않았다 — 답하지 않아도 그 턴의 결과물은 남는다.
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()).hasSize(1)

        // 답하면 **무엇에 대한 답인지** 함께 실려 나간다. 보기 id 만 보내면 모델은 뜻을 모른다.
        val questionId = payload["id"].asText()
        client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue(
                """{"message":"","answer":{"question_id":"$questionId","option_ids":["scene:TitleScene"]}}"""
            )
            .retrieve().toEntity(String::class.java).block(Duration.ofSeconds(5))

        awaitUntil { receivedFrames.any { it.contains("앞서 물어본 것") } }
        val relayed = receivedFrames.first { it.contains("앞서 물어본 것") }
        assertThat(relayed).contains("TitleScene 마저 담기")
    }

    /**
     * 말로 답해도 알아듣는다(ARTEL-487).
     *
     * **모델은 코드가 만든 질문을 본 적이 없다** — 오케가 저장하고 화면에 흘릴 뿐 모델의 대화에는
     * 들어가지 않는다. 그래서 "응" 한 마디가 무엇에 대한 것인지 알 길이 없었다(런 32에서 실제로
     * 그랬다). 물어본 것을 붙여 주고 판단은 모델에 맡긴다.
     */
    @Test
    fun `보기를 안 누르고 말로 답해도 물어본 것을 함께 보낸다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        val caseA = insertCase(projectId, "TitleScene", "A")
        val caseB = insertCase(projectId, "TitleScene", "B")

        // 전 건을 판정해야 저장까지 간다(검수에서 막히면 질문이 아니라 재작성 루프로 빠진다).
        framesToSend.add(
            """{"type":"result","message":"A만 담았습니다","reviewed":{"in":[$caseA],"out":[$caseB]},""" +
                """"scenarios":[{"title":"타이틀","description":"d","steps":[{"action":"A확인","case_id":$caseA}]}]}"""
        )

        postMessage(client, projectId, runId, token, "타이틀 시나리오")
        awaitUntil {
            runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId).toList()
                .any { it.payload != null }
        }

        // 보기를 누르지 않고 그냥 "응" 이라고 적는다.
        postMessage(client, projectId, runId, token, "응")

        awaitUntil { receivedFrames.any { it.contains("응") } }
        assertThat(receivedFrames.first { it.contains("응") }).contains("직전에 물어본 것")
    }

    /**
     * 카드로 저장해도 알림과 질문이 나간다(ARTEL-487).
     *
     * 카드 검토 모드는 챗봇이 아니라 REST 로 저장한다. 그 경로가 반영 건수만 돌려주는 바람에
     * 검수가 계산해 둔 것이 조용히 버려졌고, 사용자에게는 미상 스텝만 남았다(런 32).
     */
    @Test
    fun `카드로 커밋해도 되묻는다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        val caseA = insertCase(projectId, "TitleScene", "A")
        insertCase(projectId, "TitleScene", "B")

        client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/scenarios/commit")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue(
                """{"scenarios":[{"title":"타이틀","description":"d",""" +
                    """"steps":[{"action":"A확인","case_id":$caseA}]}]}"""
            )
            .retrieve().toEntity(String::class.java).block(Duration.ofSeconds(10))

        awaitUntil {
            runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId).toList()
                .any { it.payload != null }
        }
        val asked = runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList().first { it.payload != null }
        assertThat(objectMapper.readTree(asked.payload!!.asString())["id"].asText()).startsWith("scope:")
    }

    /**
     * 모델이 스스로 물은 것도 같은 자리로 나간다(ARTEL-487).
     *
     * 코드가 아는 것은 코드가 묻고(구간·갈래·범위), 요청의 뜻이 갈리는 것은 모델이 묻는다.
     * 화면이 두 벌을 그릴 이유가 없고 답이 돌아오는 길도 하나여야 한다.
     */
    @Test
    fun `모델이 낸 질문도 저장되고 출처가 모델로 남는다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        val caseA = insertCase(projectId, "TitleScene", "A")

        framesToSend.add(
            """{"type":"result","message":"어느 쪽을 뜻하시는지 확인이 필요합니다",""" +
                """"reviewed":{"in":[$caseA],"out":[]},""" +
                """"scenarios":[{"title":"타이틀","description":"d","steps":[{"action":"A확인","case_id":$caseA}]}],""" +
                """"question":{"id":"agent:scope","text":"전투는 어느 쪽을 뜻하나요?",""" +
                """"why":"요청이 두 가지로 읽힙니다","options":[{"id":"turn","label":"턴 전투만 담아 줘"}]}}"""
        )

        postMessage(client, projectId, runId, token, "전투 시나리오")

        awaitUntil {
            runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId).toList()
                .any { it.payload != null }
        }

        val asked = runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList().first { it.payload != null }
        val payload = objectMapper.readTree(asked.payload!!.asString())
        assertThat(payload["id"].asText()).isEqualTo("agent:scope")
        assertThat(asked.content).isEqualTo("전투는 어느 쪽을 뜻하나요?")
        // 출처는 프레임 값을 믿지 않고 여기서 못 박는다 — 모델이 자기 질문을 코드가 계산한
        // 것처럼 표시할 수 있고, 근거 있는 질문과의 구분이 이 필드 하나에 걸려 있다.
        assertThat(payload["source"].asText()).isEqualTo("agent")
        // 물음은 저장을 막지 않는다.
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()).hasSize(1)
    }

    /**
     * 숫자가 그대로면 다시 말하지 않는다.
     *
     * 좁은 범위를 여러 턴에 걸쳐 다듬는 대화가 정상적인 사용법인데, 매 턴 같은 잔량 줄이 붙으면
     * 출력이 읽히지 않는다. 새로 덮은 것이 없다 = 알릴 변화가 없다.
     */
    @Test
    fun `잔량이 그대로면 두 번 말하지 않는다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val caseA = insertCase(projectId, "TitleScene", "A")
        val caseB = insertCase(projectId, "EndingScene", "B")

        val authored =
            """{"type":"result","message":"타이틀","reviewed":{"in":[$caseA],"out":[$caseB]},""" +
                """"scenarios":[{"title":"타이틀","description":"d","steps":[{"action":"A확인","case_id":$caseA}]}]}"""
        framesToSend.add(authored)
        postMessage(client, projectId, runId, token, "타이틀만")
        awaitUntil {
            runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId).toList()
                .any { it.content.contains("남았습니다") }
        }

        // 같은 시나리오를 다시 저장한다 — 새로 덮이는 케이스가 없으므로 잔량은 그대로다.
        turnReplies.add(authored)
        postMessage(client, projectId, runId, token, "조금만 다듬어줘")
        awaitUntil {
            runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId).toList()
                .count { it.role == "USER" } == 2
        }
        // 두 번째 턴이 처리될 시간을 준 뒤에도 잔량 안내가 한 번뿐이어야 한다.
        awaitUntil {
            runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId).toList()
                .any { it.content.contains("타이틀") && it.role == "ASSISTANT" }
        }

        val notices = runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList().count { it.content.contains("남았습니다") }
        assertThat(notices).isEqualTo(1)
    }

    /**
     * 저장이 없는 턴에는 잔량을 알리지 않는다.
     *
     * 질문 턴에서는 에이전트가 방금 같은 값을 더 자세히 답했을 수 있는데, 그 뒤에 요약 한 줄을 더
     * 붙이면 같은 말을 두 번 하는 셈이 된다. 실측에서 실제로 그렇게 나왔다(2026-08-13).
     */
    @Test
    fun `질문만 한 턴에는 잔량을 알리지 않는다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        insertCase(projectId, "TitleScene", "A")

        // 시나리오 없이 답만 하는 정상 턴(질문·조회·거절).
        framesToSend.add("""{"type":"result","message":"남은 건 이러이러합니다","scenarios":[]}""")
        postMessage(client, projectId, runId, token, "뭐 남았어?")

        awaitUntil {
            runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId).toList()
                .any { it.role == "ASSISTANT" }
        }

        val messages = runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList().map { it.content }
        assertThat(messages).noneMatch { it.contains("남았습니다") }
    }

    // ---- (c) result{scenarios:[]} → DB 무변경 ------------------------------------------------

    @Test
    fun `빈 scenarios는 DB를 전혀 바꾸지 않는다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val before = scopedCounts(projectId, runId)
        assertThat(before).isEqualTo(Counts(scenarioInProject = 0, runLinks = 0))

        framesToSend.add("""{"type":"result","message":"그건 좀 애매한데요","scenarios":[]}""")
        postMessage(client, projectId, runId, token, "애매한 요청")

        Thread.sleep(1500)
        val after = scopedCounts(projectId, runId)
        assertThat(after).isEqualTo(before)
    }

    private data class Counts(val scenarioInProject: Int, val runLinks: Int)

    private suspend fun scopedCounts(projectId: Long, runId: Long) = Counts(
        scenarioInProject = scenarioRepository.findByProjectId(projectId).toList().size,
        runLinks = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().size
    )

    // ---- (d) result{scenario_id} → UPDATE(수정) --------------------------------------------

    @Test
    fun `scenario_id가 있는 결과는 기존 시나리오를 UPDATE한다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val caseA = insertCase(projectId, "RULE", "A")
        val caseB = insertCase(projectId, "RULE", "B")

        // 런에 기존 시나리오 1개(제목=old, steps=caseA).
        val existing = scenarioRepository.save(
            TestScenarioEntity(projectId = projectId).withDraft(
                ScenarioDraft(
                    title = "old",
                    description = "old",
                    steps = listOf(ScenarioStep(action = "a", caseId = caseA)),
                ),
                objectMapper,
            )
        ).id!!
        runScenarioRepository.save(
            TestRunScenarioEntity(testRunId = runId, testScenarioId = existing, position = 0)
        )

        // Agent가 그 시나리오를 겨냥해 수정(steps를 caseB로 교체).
        framesToSend.add(
            """{"type":"result","message":"수정했어","scenarios":[""" +
                """{"scenario_id":$existing,"title":"new","description":"nd","steps":[{"action":"b","case_id":$caseB}]}]}"""
        )
        postMessage(client, projectId, runId, token, "그 시나리오를 B로 바꿔줘")

        awaitUntil { scenarioRepository.findById(existing)!!.title == "new" }

        // 같은 시나리오 id 그대로, 본문(steps 포함)만 갱신됨.
        assertThat(storedSteps(existing).map { it.caseId }).containsExactly(caseB)
        // 런 링크는 그대로 1개(수정은 append하지 않음).
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().map { it.testScenarioId })
            .containsExactly(existing)
    }

    // ---- (e) autoApply=false → 자동저장 안 함, 커밋으로만 반영 -------------------------------

    @Test
    fun `autoApply false면 자동저장하지 않고 커밋 엔드포인트로 반영한다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        val caseA = insertCase(projectId, "RULE", "A")

        framesToSend.add(
            """{"type":"result","message":"제안이야","scenarios":[""" +
                """{"title":"제안 시나리오","description":"d","steps":[{"action":"a","case_id":$caseA}]}]}"""
        )
        postMessage(client, projectId, runId, token, "시나리오 제안해줘", autoApply = false)

        Thread.sleep(1500)
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()).isEmpty()

        commitScenarios(
            client, projectId, runId, token,
            """[{"title":"제안 시나리오","description":"d","steps":[{"action":"a","case_id":$caseA}]}]"""
        )
        awaitUntil { runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().size == 1 }
        val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        assertThat(links).hasSize(1)
        val committed = scenarioRepository.findById(links[0].testScenarioId)!!
        assertThat(committed.title).isEqualTo("제안 시나리오")
        assertThat(storedSteps(committed.id!!).map { it.caseId }).containsExactly(caseA)
    }

    // ---- (e2) 저작 단계 중계(ARTEL-419) --------------------------------------------------------

    /**
     * 도구를 부르지 않는 턴에도 **종착 단계가 온다.**
     *
     * 화면이 멈춰 보이지 않는 조건이 이것이다. 가운데 단계(케이스 확인·작성)는 없을 수 있고 없는 것이
     * 정상이지만, 시작하고 끝나지 않는 일은 있어서는 안 된다 — 그러면 스테퍼가 켜진 채 영영 남는다.
     */
    @Test
    fun `도구를 부르지 않은 턴도 sent로 열고 종착 단계로 닫는다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        val caseA = insertCase(projectId, "RULE", "A")

        val stream = openStream(client, projectId, runId, token)
        framesToSend.add(
            """{"type":"result","message":"했습니다","reviewed":{"in":[$caseA],"out":[]},""" +
                """"scenarios":[{"title":"시나리오","description":"d","steps":[{"action":"A확인","case_id":$caseA}]}]}"""
        )
        postMessage(client, projectId, runId, token, "만들어줘")

        assertThat(stream.awaitStage("saved")).isTrue()
        assertThat(stream.stages()).containsExactly("sent", "checking", "saved")
        stream.close()
    }

    /**
     * 도구를 부른 턴은 **부른 사실과 답을 넘긴 사실**이 각각 단계가 된다.
     *
     * 턴을 보낸 뒤 결과가 올 때까지 오케스트레이션은 아무것도 보지 못한다. 그 침묵 한가운데서 도구
     * 프레임 하나가 "멎지 않았다"의 유일한 증거라, 이 두 단계가 곧 이 이슈가 풀려는 문제 자체다.
     */
    @Test
    fun `도구를 부른 턴은 looking_up_cases와 writing을 거친다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        val caseA = insertCase(projectId, "RULE", "A")

        val stream = openStream(client, projectId, runId, token)
        // 목이 도구를 먼저 부르고, 우리가 답한 뒤에야 결과를 낸다 — 실제 순서 그대로다.
        framesToSend.add("""{"type":"uncovered_cases","messageId":"unc-1"}""")
        turnReplies.add(
            """{"type":"result","message":"했습니다","reviewed":{"in":[$caseA],"out":[]},""" +
                """"scenarios":[{"title":"시나리오","description":"d","steps":[{"action":"A확인","case_id":$caseA}]}]}"""
        )
        postMessage(client, projectId, runId, token, "남은 거 만들어줘")

        assertThat(stream.awaitStage("saved")).isTrue()
        assertThat(stream.stages())
            .containsExactly("sent", "looking_up_cases", "writing", "checking", "saved")
        stream.close()
    }

    /**
     * 검사에 걸린 턴은 **다시 쓰는 중**과 **저장 안 함**이 화면에서 갈린다.
     *
     * 그리고 서버가 쓴 문장이 그 자리에서 보인다. 이 문장들은 결과를 중계한 뒤에 만들어지는데, 그때
     * 화면은 이미 답을 다 받았다고 여겨 더 기다리지 않는다 — `notice`로 흘리지 않으면 "한 줄도
     * 저장하지 않았습니다"가 새로고침 전까지 보이지 않는다.
     */
    @Test
    fun `재작성과 저장 거부가 각각 단계와 notice로 온다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        val caseA = insertCase(projectId, "RULE", "A")
        val caseB = insertCase(projectId, "RULE", "B")

        val stream = openStream(client, projectId, runId, token)
        // B를 담겠다고 선언하고 A만 담았다. 재작성 응답도 B를 담지 않는다.
        framesToSend.add(
            """{"type":"result","message":"했습니다","reviewed":{"in":[$caseA,$caseB],"out":[]},""" +
                """"scenarios":[{"title":"시나리오","description":"d","steps":[{"action":"A확인","case_id":$caseA}]}]}"""
        )
        turnReplies.add("""{"type":"result","message":"더 못 쓰겠습니다","reviewed":{"in":[],"out":[]},"scenarios":[]}""")

        postMessage(client, projectId, runId, token, "전부 써줘")

        assertThat(stream.awaitStage("blocked")).isTrue()
        // 재작성을 한 번 시도한 뒤 막았다 — 두 상태가 별개로 보인다.
        assertThat(stream.stages()).containsSubsequence("repairing", "blocked")
        val notices = stream.notices()
        assertThat(notices).anyMatch { it.contains("다시 작성하도록 요청했습니다") }
        assertThat(notices).anyMatch { it.contains("저장하지 않았습니다") }
        // 말만 하고 저장은 하지 않는다.
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()).isEmpty()
        stream.close()
    }

    // ---- (f) 실행 계약: agentScenario가 steps를 TC 리졸브해 넘긴다 -------------------------------

    @Test
    fun `agentScenario가 steps를 읽어 caseId 스텝에 TC를 리졸브해 넘긴다`(): Unit = runBlocking {
        val (appUserId, _) = issueUser()
        val projectId = createMemberProject(appUserId)

        val caseA = insertCase(projectId, "RULE", "상점 진입")
        val caseB = insertCase(projectId, "UI", "구매 확인")

        val draft = ScenarioDraft(
            title = "구매",
            description = "d",
            steps = listOf(
                ScenarioStep(action = "상점으로 이동"),
                ScenarioStep(action = "검 구매", caseId = caseA),
                ScenarioStep(action = "구매 버튼 누름", caseId = caseB, hint = "Enter"),
            ),
        )
        val stored = scenarioRepository.save(
            TestScenarioEntity(projectId = projectId).withDraft(draft, objectMapper)
        )

        val scenario = compositionService.agentScenario(stored.toDraft(objectMapper))

        assertThat(scenario.title).isEqualTo("구매")
        assertThat(scenario.steps).hasSize(3)
        // 조작 스텝: caseId/case 없음(null).
        assertThat(scenario.steps[0].action).isEqualTo("상점으로 이동")
        assertThat(scenario.steps[0].caseId).isNull()
        assertThat(scenario.steps[0].case).isNull()
        // 검증 스텝: caseId + TC 내용(CSV 스펙 이름: scene/precondition/test_step/expected) 리졸브 동봉.
        assertThat(scenario.steps[1].caseId).isEqualTo(caseA)
        assertThat(scenario.steps[1].case!!.scene).isEqualTo("RULE")
        assertThat(scenario.steps[1].case!!.testStep).isEqualTo("상점 진입")
        assertThat(scenario.steps[1].case!!.expected).isEqualTo("상점 진입 기대결과")
        // hint 보존, 두 번째 검증 스텝.
        assertThat(scenario.steps[2].hint).isEqualTo("Enter")
        assertThat(scenario.steps[2].case!!.testStep).isEqualTo("구매 확인")
    }

    // ---- helpers ----------------------------------------------------------------------------

    /**
     * 구독 중인 SSE 스트림 하나(ARTEL-419 검증용). 받은 이벤트 본문을 순서대로 쌓아 둔다.
     *
     * 이벤트 **이름** 대신 본문의 `type`을 보는 이유는 `bodyToFlux(String)`이 `data:` 줄만 주기 때문이다.
     * 둘은 같은 값이다 — [kr.artel.orchestration.testscenario.service.TestScenarioStreamManager]가
     * `event.type`을 그대로 이벤트명으로 쓴다.
     */
    private inner class OpenStream(
        private val subscription: reactor.core.Disposable,
        private val received: CopyOnWriteArrayList<String>,
    ) {
        private fun typed(type: String) = received.mapNotNull { objectMapper.readTree(it) }
            .filter { it.path("type").asText() == type }

        fun stages(): List<String> = typed("progress").map { it.path("stage").asText() }

        fun notices(): List<String> = typed("notice").map { it.path("message").asText() }

        fun awaitStage(stage: String): Boolean {
            repeat(60) {
                if (stages().contains(stage)) return true
                Thread.sleep(100)
            }
            return stages().contains(stage)
        }

        fun close() = subscription.dispose()
    }

    /**
     * SSE를 구독하고 **스트림이 실제로 열릴 때까지 기다린다.**
     *
     * 기다리지 않으면 첫 단계(`sent`)를 놓친다. 스트림 sink는 구독 시점에 만들어지는데, 구독은
     * 비동기라 바로 다음 줄에서 메시지를 보내면 아직 등록 전일 수 있다. 열렸다는 신호가 따로 없어
     * 짧게 재운다 — 놓치면 단계 목록이 어긋나 테스트가 실패하므로 조용히 넘어가지는 않는다.
     */
    private fun openStream(client: WebClient, projectId: Long, runId: Long, token: String): OpenStream {
        val received = CopyOnWriteArrayList<String>()
        val subscription = client.get()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToFlux(String::class.java)
            .subscribe { received.add(it) }
        Thread.sleep(500)
        return OpenStream(subscription, received)
    }

    private suspend fun awaitFrame(predicate: (String) -> Boolean): String? {
        repeat(50) {
            receivedFrames.firstOrNull(predicate)?.let { return it }
            Thread.sleep(100)
        }
        return receivedFrames.firstOrNull(predicate)
    }

    private suspend fun awaitUntil(predicate: suspend () -> Boolean): Boolean {
        repeat(50) {
            if (predicate()) return true
            Thread.sleep(100)
        }
        return predicate()
    }

    private suspend fun issueUser(): Pair<Long, String> {
        val id = seq.incrementAndGet()
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "recon-$id",
                login = "recon-$id",
                displayName = "Recon",
                avatarUrl = null,
                email = null
            )
        )!!
        return user.userId.toLong() to jwtService.issue(user)
    }

    private suspend fun createMemberProject(appUserId: Long): Long {
        val now = java.time.Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "recon-project", genre = "RPG", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = appUserId, role = "OWNER", createdAt = now)
        )
        return project.id!!
    }

    private fun postMessage(
        client: WebClient, projectId: Long, runId: Long, token: String, msg: String, autoApply: Boolean = true
    ) =
        client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue("""{"message":"$msg","autoApply":$autoApply}""")
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))

    private fun commitScenarios(
        client: WebClient, projectId: Long, runId: Long, token: String, scenariosJson: String
    ) =
        client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/scenarios/commit")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue("""{"scenarios":$scenariosJson}""")
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))

    private suspend fun insertCase(projectId: Long, category: String, title: String): Long =
        testCaseRepository.save(
            TestCaseEntity(
                projectId = projectId,
                scene = category,
                step = title,
                precondition = null,
                expectedValue = "$title 기대결과",
            )
        ).id!!

    private suspend fun insertVector(caseId: Long, vector: List<Double>) {
        databaseClient.sql(
            """
            INSERT INTO test_case_embedding (test_case_id, kind, model, source_text, embedding)
            VALUES (:id, 'CONTENT', :model, :text, CAST(:emb AS vector))
            """.trimIndent()
        )
            .bind("id", caseId)
            .bind("model", model)
            .bind("text", "case-$caseId")
            .bind("emb", EmbeddedText("case-$caseId", vector).toVectorLiteral())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }
}
