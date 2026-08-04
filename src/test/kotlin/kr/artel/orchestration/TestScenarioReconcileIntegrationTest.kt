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
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioCaseRepository
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

/** V20의 vector(1024)와 같아야 한다. */
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

    private fun postMessage(client: WebClient, projectId: Long, runId: Long, token: String, msg: String) =
        client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue("""{"message":"$msg"}""")
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
