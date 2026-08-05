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
import kr.artel.orchestration.testscenario.entity.TestScenarioCaseEntity
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioCaseRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import io.r2dbc.postgresql.codec.Json
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
 * ARTEL-206 Step 5 Layer 1 통합 테스트 — 저작 세션의 케이스 검색 루프와 결과 반영(INSERT 전용).
 *
 * 실제 Agent를 흉내내는 목 서버(POST /sessions + WS)로 검증한다. 목은 WS 연결 시 [framesToSend]에
 * 담긴 프레임을 그대로 보내고, Orchestration이 되돌린 프레임을 [receivedFrames]에 기록한다.
 *
 * 검증:
 * (a) 인입 `test_case_search` → `test_case_search_result`(correlationId + 히트).
 * (b) `result{scenarios:[…]}` → test_scenario/test_scenario_case/test_run_scenario 올바른 position으로 INSERT.
 * (c) `result{scenarios:[]}` → DB 무변경(안전규칙 가드).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestScenarioReconcileIntegrationTest {

    /** 검색어를 미리 등록한 벡터로 바꾸는 대역(TestCaseVectorSearchIntegrationTest와 동일 패턴). */
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
    @Autowired private lateinit var scenarioCaseRepository: TestScenarioCaseRepository
    @Autowired private lateinit var compositionService: kr.artel.orchestration.testscenario.service.ScenarioCompositionService
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var fake: FixedQueryEmbeddingClient
    @Autowired private lateinit var properties: TestCaseEmbeddingProperties

    private fun webClient() = WebClient.create("http://localhost:$port")
    private val model: String get() = properties.model

    companion object {
        private lateinit var mockAgent: DisposableServer
        private const val MOCK_SESSION_ID = "reconcile-sid"

        /** WS 연결 시 목이 보낼 프레임(각 테스트가 세션 열기 전에 설정). */
        private val framesToSend = CopyOnWriteArrayList<String>()
        /** Orchestration이 목에게 되돌린 프레임(응답 검증용). */
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
                    // 연결 시 준비된 프레임을 보내고, 이후 연결을 열어 둔 채 인입 프레임을 기록만 한다.
                    outbound.sendString(
                        Flux.concat(
                            Flux.fromIterable(framesToSend.toList()),
                            inbound.receive().asString()
                                .doOnNext { receivedFrames.add(it) }
                                .flatMap { Mono.empty<String>() }
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
        fake.vectorsByQuery.clear()
    }

    // ---- (a) test_case_search → test_case_search_result -------------------------------------

    @Test
    fun `인입 test_case_search에 결과 프레임으로 답한다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        // 프로젝트 안에 검색으로 잡힐 케이스(벡터 axis(0))를 심는다.
        val hitCase = insertCase(projectId, "RULE", "골드 차감")
        insertVector(hitCase, axis(0))
        fake.vectorsByQuery["골드"] = axis(0)

        // 목이 WS 연결 시 케이스 검색을 요청하도록 프레임을 큐잉한다.
        framesToSend.add("""{"type":"test_case_search","messageId":"search-1","query":"골드","limit":5}""")

        // 첫 메시지가 세션(POST /sessions + WS)을 연다 → 연결 시 위 프레임이 흐른다.
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
        assertThat(hit.get("title").asText()).isEqualTo("골드 차감")
        // Agent가 camelCase로 파싱하는 필드명이 계약 그대로 나가야 한다.
        assertThat(hit.has("verificationStatus")).isTrue()
    }

    // ---- (b) result{scenarios:[…]} → INSERT ------------------------------------------------

    @Test
    fun `scenarios 결과를 런에 INSERT하고 position을 매긴다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val caseA = insertCase(projectId, "RULE", "A")
        val caseB = insertCase(projectId, "RULE", "B")
        val caseC = insertCase(projectId, "UI", "C")

        framesToSend.add(
            """{"type":"result","message":"두 시나리오 만들었어","scenarios":[""" +
                """{"title":"구매 여정","description":"d1","case_ids":[$caseA,$caseB]},""" +
                """{"title":"판매 여정","description":"d2","case_ids":[$caseC]}]}"""
        )

        // 세션은 런 단위 — 런 채팅 엔드포인트로 첫 메시지를 보내 세션을 이 런에 바인딩한다.
        postMessage(client, projectId, runId, token, "시나리오 만들어줘")

        // 반영은 fire-and-forget 코루틴이라 잠시 기다렸다 단정한다. 런 조합을 기준으로 삼는다 —
        // 시나리오는 createScenario가 만든 원본(빈 payload)까지 프로젝트에 있어 개수로 세면 헷갈린다.
        awaitUntil { runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().size == 2 }

        // 런 조합: 빈 런에 배치 순서대로 position 0,1로 붙는다.
        val runLinks = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        assertThat(runLinks.map { it.position }).containsExactly(0, 1)

        val purchase = scenarioRepository.findById(runLinks[0].testScenarioId)!!
        val sale = scenarioRepository.findById(runLinks[1].testScenarioId)!!
        assertThat(purchase.projectId).isEqualTo(projectId)
        assertThat(purchase.payload.asString()).contains("구매 여정")
        assertThat(sale.payload.asString()).contains("판매 여정")

        // 케이스 링크: 리스트 인덱스가 position.
        val purchaseCases = scenarioCaseRepository.findByTestScenarioIdOrderByPosition(purchase.id!!).toList()
        assertThat(purchaseCases.map { it.testCaseId to it.position })
            .containsExactly(caseA to 0, caseB to 1)
        val saleCases = scenarioCaseRepository.findByTestScenarioIdOrderByPosition(sale.id!!).toList()
        assertThat(saleCases.map { it.testCaseId to it.position }).containsExactly(caseC to 0)
    }

    // ---- (c) result{scenarios:[]} → DB 무변경 ------------------------------------------------

    @Test
    fun `빈 scenarios는 DB를 전혀 바꾸지 않는다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        // 채팅은 시나리오 없이 런만으로 시작한다 — 프로젝트/런 모두 비어 있다.
        // 스코프별로 세어 다른 테스트 클래스가 동시에 넣는 행에 흔들리지 않게 한다.
        val before = scopedCounts(projectId, runId)
        assertThat(before).isEqualTo(Counts(scenarioInProject = 0, runLinks = 0))

        framesToSend.add("""{"type":"result","message":"그건 좀 애매한데요","scenarios":[]}""")
        postMessage(client, projectId, runId, token, "애매한 요청")

        // ASSISTANT 채팅 저장까지 처리될 시간을 준 뒤, 이 스코프의 행 수가 그대로인지 확인한다.
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

        // 런에 기존 시나리오 1개를 심는다(payload=old, 케이스=A).
        val existing = scenarioRepository.save(
            TestScenarioEntity(
                projectId = projectId,
                payload = Json.of("""{"title":"old","description":"old"}""")
            )
        ).id!!
        runScenarioRepository.save(
            TestRunScenarioEntity(testRunId = runId, testScenarioId = existing, position = 0)
        )
        scenarioCaseRepository.save(
            TestScenarioCaseEntity(testScenarioId = existing, testCaseId = caseA, position = 0)
        )

        // Agent가 그 시나리오를 겨냥해 수정 결과를 돌려준다(scenario_id 포함, 케이스는 B로 교체).
        framesToSend.add(
            """{"type":"result","message":"수정했어","scenarios":[""" +
                """{"scenario_id":$existing,"title":"new","description":"nd","case_ids":[$caseB]}]}"""
        )
        postMessage(client, projectId, runId, token, "그 시나리오 케이스를 B로 바꿔줘")

        // payload가 new로 바뀔 때까지 기다린다.
        awaitUntil { scenarioRepository.findById(existing)!!.payload.asString().contains("new") }

        // 같은 시나리오 id 그대로, payload만 갱신됨(새 시나리오가 생기지 않음).
        assertThat(scenarioRepository.findById(existing)!!.payload.asString()).contains("new")
        // 런 링크는 그대로 1개(수정은 append하지 않음).
        val runLinks = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        assertThat(runLinks.map { it.testScenarioId }).containsExactly(existing)
        // 케이스 링크는 통째 교체(A 제거, B만).
        val cases = scenarioCaseRepository.findByTestScenarioIdOrderByPosition(existing).toList()
        assertThat(cases.map { it.testCaseId }).containsExactly(caseB)
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
                """{"title":"제안 시나리오","description":"d","case_ids":[$caseA]}]}"""
        )
        // autoApply=false → 서버는 결과를 저장하지 않고 제안으로만 둔다.
        postMessage(client, projectId, runId, token, "시나리오 제안해줘", autoApply = false)

        Thread.sleep(1500)
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()).isEmpty()

        // 사용자가 카드로 커밋 → 같은 엔진으로 반영된다.
        commitScenarios(
            client, projectId, runId, token,
            """[{"title":"제안 시나리오","description":"d","case_ids":[$caseA]}]"""
        )
        awaitUntil { runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().size == 1 }
        val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        assertThat(links).hasSize(1)
        val committed = scenarioRepository.findById(links[0].testScenarioId)!!
        assertThat(committed.payload.asString()).contains("제안 시나리오")
    }

    // ---- (f) 수정 시 steps 캐리포워드(자리 유지 보존 / 자리 변경 폐기) --------------------------

    @Test
    fun `수정 시 자리가 유지되는 steps는 보존하고 바뀐 자리는 폐기한다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser()
        val projectId = createMemberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val caseA = insertCase(projectId, "RULE", "A")
        val caseB = insertCase(projectId, "RULE", "B")

        // 런에 기존 시나리오: caseA(pos0)+스텝, caseB(pos1)+스텝.
        val existing = scenarioRepository.save(
            TestScenarioEntity(projectId = projectId, payload = Json.of("""{"title":"old","description":"old"}"""))
        ).id!!
        runScenarioRepository.save(
            TestRunScenarioEntity(testRunId = runId, testScenarioId = existing, position = 0)
        )
        scenarioCaseRepository.save(
            TestScenarioCaseEntity(
                testScenarioId = existing, testCaseId = caseA, position = 0,
                steps = Json.of("""[{"id":"a","kind":"guide","assert":true,"intent":"A진행"}]""")
            )
        )
        scenarioCaseRepository.save(
            TestScenarioCaseEntity(
                testScenarioId = existing, testCaseId = caseB, position = 1,
                steps = Json.of("""[{"id":"b","kind":"setup","assert":false,"intent":"B도달"}]""")
            )
        )

        // reconcile UPDATE: 조합을 [A, A]로 교체 — pos0은 caseA 그대로(자리 유지), pos1은 caseB→caseA(자리 변경).
        framesToSend.add(
            """{"type":"result","message":"수정","scenarios":[""" +
                """{"scenario_id":$existing,"title":"new","description":"nd","case_ids":[$caseA,$caseA]}]}"""
        )
        postMessage(client, projectId, runId, token, "수정해줘")

        awaitUntil { scenarioRepository.findById(existing)!!.payload.asString().contains("new") }

        val cases = scenarioCaseRepository.findByTestScenarioIdOrderByPosition(existing).toList()
        assertThat(cases.map { it.testCaseId }).containsExactly(caseA, caseA)
        // pos0: (caseA,0) 자리 유지 → 스텝 보존.
        assertThat(cases[0].steps.asString()).contains("A진행")
        // pos1: 원래 (caseB,1)였고 이제 (caseA,1) — 스냅샷에 없어 빈 배열로 초기화(B도달은 폐기).
        assertThat(cases[1].steps.asString()).isEqualTo("[]")
    }

    // ---- (g) 실행용 시나리오 조립: 조합 TC + steps를 cases[]로 실어 준다 ----------------------

    @Test
    fun `agentScenario가 조합 TC와 steps를 cases 배열로 실어 준다`(): Unit = runBlocking {
        val (appUserId, _) = issueUser()
        val projectId = createMemberProject(appUserId)

        val caseA = insertCase(projectId, "RULE", "상점 진입")
        val caseB = insertCase(projectId, "UI", "구매 확인")

        val scenarioId = scenarioRepository.save(
            TestScenarioEntity(projectId = projectId, payload = Json.of("""{"title":"구매","description":"d"}"""))
        ).id!!
        // caseA(pos0): setup 스텝 / caseB(pos1): guide 스텝
        scenarioCaseRepository.save(
            TestScenarioCaseEntity(
                testScenarioId = scenarioId, testCaseId = caseA, position = 0,
                steps = Json.of("""[{"id":"s1","kind":"setup","assert":false,"intent":"상점으로 이동"}]""")
            )
        )
        scenarioCaseRepository.save(
            TestScenarioCaseEntity(
                testScenarioId = scenarioId, testCaseId = caseB, position = 1,
                steps = Json.of("""[{"id":"s2","kind":"guide","assert":true,"intent":"구매 버튼 누름","hint":"Enter"}]""")
            )
        )

        val node = compositionService.agentScenario(scenarioId, appUserId, """{"title":"구매","description":"d"}""")

        assertThat(node.get("title").asText()).isEqualTo("구매")
        val cases = node.get("cases")
        assertThat(cases).hasSize(2)
        // pos0: caseA + setup 스텝
        assertThat(cases[0].get("position").asInt()).isEqualTo(0)
        assertThat(cases[0].get("title").asText()).isEqualTo("상점 진입")
        assertThat(cases[0].get("expected").asText()).isEqualTo("상점 진입 기대결과")
        assertThat(cases[0].get("steps")).hasSize(1)
        assertThat(cases[0].get("steps")[0].get("kind").asText()).isEqualTo("setup")
        assertThat(cases[0].get("steps")[0].get("intent").asText()).isEqualTo("상점으로 이동")
        // pos1: caseB + guide 스텝(hint 포함)
        assertThat(cases[1].get("title").asText()).isEqualTo("구매 확인")
        assertThat(cases[1].get("steps")[0].get("kind").asText()).isEqualTo("guide")
        assertThat(cases[1].get("steps")[0].get("hint").asText()).isEqualTo("Enter")
    }

    // ---- helpers ----------------------------------------------------------------------------

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
                category = category,
                title = title,
                precondition = null,
                expected = "$title 기대결과",
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
