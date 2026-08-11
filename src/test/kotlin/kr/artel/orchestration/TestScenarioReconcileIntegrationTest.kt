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
 * 저작 세션의 케이스 검색 루프와 결과 반영(재설계 2026-08-07 — 시나리오 = steps 리스트).
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
        assertThat(hit.get("title").asText()).isEqualTo("골드 차감")
        assertThat(hit.has("verificationStatus")).isTrue()
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
