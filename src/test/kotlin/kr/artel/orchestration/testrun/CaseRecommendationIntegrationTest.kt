package kr.artel.orchestration.testrun

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.DraftApplyResult
import kr.artel.orchestration.testscenario.dto.DraftScenario
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
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

/**
 * 저작이 추천한 TC 는 저장 전까지 추천으로 남고, 시나리오 스텝은 그 추천을 가리킨다. 저장하면 사용자 TC 가 생기고 스텝이 그
 * TC 로 바뀐다. 시나리오 draft 를 승인하면 그 draft 가 쓰는 추천도 한 번에 저장된다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CaseRecommendationIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var recommendations: kr.artel.orchestration.testscenario.service.CaseRecommendationService
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository

    companion object {
        private lateinit var mockAgent: DisposableServer

        /** 3번 스텝이 추천 TC(-1)를 확인한다. 추천은 시나리오와 함께 온다. */
        private const val SUBMIT =
            """{"type":"submit_scenario","messageId":"s1","scenario":{"title":"밤 늑대","description":"d","steps":[{"action":"여관에 들어간다"},{"action":"저녁까지 쉬고 밤이 되는지 본다","proposed_case":-1},{"action":"늑대한테 다가간다"}]},"proposedCases":[{"ref":-1,"scene":"여관","precondition":"여관 안에 있다.","action":"저녁까지 쉰다.","expected":"밤이 된다.","why":"밤을 만드는 TC 가 없다"}]}"""
        private const val RESULT =
            """{"type":"result","message":"ok","scenarios":[],"reply":{"result":"총 1건의 시나리오를 만들었습니다.","detail":"추천해요","changes":[],"asks":[]}}"""

        @JvmStatic
        @DynamicPropertySource
        fun registerAgentUrls(registry: DynamicPropertyRegistry) {
            mockAgent = HttpServer.create().port(0).route { routes ->
                routes.post("/sessions") { _, response ->
                    response.header("Content-Type", "application/json").sendString(Mono.just("""{"session_id":"rec-sid-1"}""")).then()
                }
                routes.ws("/sessions/{id}") { inbound, outbound ->
                    inbound.receive().subscribe()
                    outbound.sendString(Flux.just(SUBMIT, RESULT)).then(Mono.never())
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

    private suspend fun world(seed: String): Triple<Long, Long, String> {
        val user = oauthUserService.upsert(OAuthIdentity("github", "rec-$seed", "rec-$seed", "Rec", null, null))
        val now = java.time.Instant.now()
        val project = projectRepository.save(ProjectEntity(name = "rec-$seed", genre = "RPG", createdAt = now, updatedAt = now))
        projectMemberRepository.save(ProjectMemberEntity(projectId = project.id!!, appUserId = user.userId.toLong(), role = "OWNER", createdAt = now))
        val runId = runRepository.save(TestRunEntity(projectId = project.id!!, name = "런")).id!!
        return Triple(project.id!!, runId, jwtService.issue(user))
    }

    private fun post(client: WebClient, uri: String, token: String, body: Any): String =
        client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).cookie("artel_access_token", token)
            .bodyValue(body).retrieve().bodyToMono(String::class.java).defaultIfEmpty("").block(Duration.ofSeconds(15))!!

    private fun get(client: WebClient, uri: String, token: String): String =
        client.get().uri(uri).cookie("artel_access_token", token).retrieve().bodyToMono(String::class.java).block(Duration.ofSeconds(10))!!

    @Test
    fun `추천 TC 는 저장하면 TC 가 되고 스텝이 그 TC 로 바뀌며, draft 승인은 추천까지 한 번에 저장한다`(): Unit = runBlocking {
        val client = WebClient.create("http://localhost:$port")
        val (projectId, runId, token) = world("1")
        val base = "/api/projects/$projectId/test-runs/$runId"

        post(client, "$base/chat/message", token, mapOf("message" to "밤에 늑대", "autoApply" to false, "model" to "openai/gpt-6-luna", "keySource" to "platform"))
        Thread.sleep(2000)

        // 추천이 남고, draft 의 그 스텝은 추천을 가리킨다. 답에도 추천이 실린다.
        val recs = objectMapper.readTree(get(client, "$base/case-recommendations", token))
        assertThat(recs).hasSize(1)
        assertThat(recs[0]["status"].asText()).isEqualTo("PENDING")
        val recId = recs[0]["id"].asText()
        val drafted = objectMapper.readTree(get(client, "$base/chat/draft", token))
        assertThat(drafted[0]["steps"][1]["recommendation_id"].asText()).isEqualTo(recId)
        assertThat(get(client, "$base/chat/messages", token)).contains("recommendations").contains("저녁까지 쉰다.")

        // draft 를 승인하면 추천도 저장되고, 저장된 시나리오의 스텝은 새 TC 를 가리킨다.
        post(client, "$base/chat/draft/apply", token, mapOf("draft_ids" to listOf(drafted[0]["draft_id"].asText())))
        val after = objectMapper.readTree(get(client, "$base/case-recommendations", token))
        assertThat(after[0]["status"].asText()).isEqualTo("SAVED")
        val caseId = after[0]["testCaseId"].asLong()
        val link = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().single()
        val steps = objectMapper.readTree(scenarioRepository.findById(link.testScenarioId)!!.steps.asString())
        assertThat(steps[1]["case_id"].asLong()).isEqualTo(caseId)
        assertThat(steps[1].path("recommendation_id").let { it.isMissingNode || it.isNull }).isTrue()
    }

    @Test
    fun `추천 TC 를 버리면 스텝은 판정 없는 스텝으로 남는다`(): Unit = runBlocking {
        val client = WebClient.create("http://localhost:$port")
        val (projectId, runId, token) = world("2")
        val base = "/api/projects/$projectId/test-runs/$runId"
        post(client, "$base/chat/message", token, mapOf("message" to "밤에 늑대", "autoApply" to false, "model" to "openai/gpt-6-luna", "keySource" to "platform"))
        Thread.sleep(2000)

        post(client, "$base/case-recommendations/discard", token, mapOf("ids" to emptyList<String>()))

        val drafted = objectMapper.readTree(get(client, "$base/chat/draft", token))
        assertThat(drafted[0]["steps"][1].path("recommendation_id").isMissingNode || drafted[0]["steps"][1]["recommendation_id"].isNull).isTrue()
        assertThat(drafted[0]["steps"][1]["case_id"].isNull).isTrue()
        assertThat(objectMapper.readTree(get(client, "$base/case-recommendations", token))[0]["status"].asText()).isEqualTo("DISCARDED")
    }

    /** 길 추천을 저장하면, 그 길을 사람 말 그대로 채운 사람 스텝이 새 TC 를 확인하는 스텝이 된다. 그래야 QA 가 그 TC 를 판정한다. */
    @Test
    fun `길 추천을 저장하면 같은 말의 사람 스텝이 그 TC 를 확인한다`(): Unit = runBlocking {
        val client = WebClient.create("http://localhost:$port")
        val (projectId, runId, token) = world("3")
        // 메우기는 사람 말 끝에 마침표를 붙여 넣는다. 추천은 말 그대로라 끝 문장부호가 달라도 같은 말로 본다.
        val steps = """[{"action":"광장 오른쪽 길로 간다.","step_source":"HUMAN","step_kind":"ACTION"},{"action":"숲에서 늑대한테 다가간다","step_source":"HUMAN","step_kind":"ACTION"}]"""
        val scenario = scenarioRepository.save(
            kr.artel.orchestration.testscenario.entity.TestScenarioEntity(projectId = projectId, title = "숲", steps = io.r2dbc.postgresql.codec.Json.of(steps)),
        )
        runScenarioRepository.save(kr.artel.orchestration.testrun.entity.TestRunScenarioEntity(testRunId = runId, testScenarioId = scenario.id!!, position = 0))
        val rec = recommendations.recommendSceneMove(projectId, runId, "광장", "숲", "광장 오른쪽 길로 간다")!!

        post(client, "/api/projects/$projectId/test-runs/$runId/case-recommendations/save", token, mapOf("ids" to listOf(rec.id.toString())))

        val caseId = objectMapper.readTree(get(client, "/api/projects/$projectId/test-runs/$runId/case-recommendations", token))[0]["testCaseId"].asLong()
        val after = objectMapper.readTree(scenarioRepository.findById(scenario.id!!)!!.steps.asString())
        assertThat(after[0]["case_id"].asLong()).isEqualTo(caseId)
        assertThat(after[0]["step_source"].asText()).isEqualTo("CASE")
        // 다른 말의 사람 스텝은 그대로다.
        assertThat(after[1]["step_source"].asText()).isEqualTo("HUMAN")
        assertThat(after[1].path("case_id").isNumber).isFalse()
        // 길 추천은 도착 화면을 안다 — 저장한 TC 가 씬 전이 TC 가 된다(ARTEL-1312).
        assertThat(rec.arrivesAt).isEqualTo("숲")
        assertThat(objectMapper.readTree(get(client, "/api/projects/$projectId/test-cases/$caseId", token))["arrivesAt"].asText()).isEqualTo("숲")
    }

    @Test
    fun `승인 전 추천 TC 는 승인된 시나리오에 잇지 않고 승인할 때 잇는다`(): Unit = runBlocking {
        val client = WebClient.create("http://localhost:$port")
        val (projectId, runId, token) = world("review-link")
        fun stepsOf(id: Long) = runBlocking { objectMapper.readTree(scenarioRepository.findById(id)!!.steps.asString()) }
        fun send(uri: String, body: String): Pair<Int, String> =
            client.post().uri(uri).contentType(org.springframework.http.MediaType.APPLICATION_JSON).cookie("artel_access_token", token).bodyValue(body)
                .exchangeToMono { r -> r.bodyToMono(String::class.java).defaultIfEmpty("").map { r.statusCode().value() to it } }.block()!!
        val steps = io.r2dbc.postgresql.codec.Json.of("""[{"action":"광장 오른쪽 길로 간다","step_source":"HUMAN","step_kind":"ACTION"}]""")
        val approved = scenarioRepository.save(
            kr.artel.orchestration.testscenario.entity.TestScenarioEntity(projectId = projectId, title = "승인된 숲", steps = steps),
        )
        val pending = scenarioRepository.save(
            kr.artel.orchestration.testscenario.entity.TestScenarioEntity(projectId = projectId, title = "대기 숲", steps = steps, reviewStatus = "DRAFT"),
        )
        runScenarioRepository.save(kr.artel.orchestration.testrun.entity.TestRunScenarioEntity(testRunId = runId, testScenarioId = approved.id!!, position = 0))
        runScenarioRepository.save(kr.artel.orchestration.testrun.entity.TestRunScenarioEntity(testRunId = runId, testScenarioId = pending.id!!, position = 1))
        val rec = recommendations.recommendSceneMove(projectId, runId, "광장", "숲", "광장 오른쪽 길로 간다")!!

        send("/api/projects/$projectId/test-runs/$runId/case-recommendations/save", """{"ids":["${rec.id}"],"draft":true}""")

        val caseId = objectMapper.readTree(get(client, "/api/projects/$projectId/test-runs/$runId/case-recommendations", token))[0]["testCaseId"].asLong()
        assertThat(stepsOf(approved.id!!)[0].path("case_id").isNumber).isFalse()
        assertThat(stepsOf(pending.id!!)[0]["case_id"].asLong()).isEqualTo(caseId)

        val item = objectMapper.readTree(get(client, "/api/projects/$projectId/test-cases/review-queue", token)).single()
        fun review(decision: String) = send(
            "/api/projects/$projectId/test-cases/$caseId/review",
            """{"decision":"$decision","expectedUpdatedAt":"${item["updatedAt"].asText()}"}""",
        )
        // 승인 대기 시나리오가 쓰는 Test Case 를 지우면 참조가 끊어진다.
        val refused = review("REJECT")
        assertThat(refused.first).isEqualTo(409)
        assertThat(refused.second).contains("review_not_rejectable")

        assertThat(review("APPROVE").first).isEqualTo(204)
        assertThat(stepsOf(approved.id!!)[0]["case_id"].asLong()).isEqualTo(caseId)
        assertThat(stepsOf(approved.id!!)[0]["step_source"].asText()).isEqualTo("CASE")
    }

    /** draft 를 버리면 그 draft 의 스텝에서 나온 추천도 버린다. 남으면 쓰는 곳 없는 TC 를 저장할지 또 정해야 한다. */
    @Test
    fun `draft 를 버리면 그 draft 만 쓰던 추천도 버린다`(): Unit = runBlocking {
        val client = WebClient.create("http://localhost:$port")
        val (projectId, runId, token) = world("4")
        val base = "/api/projects/$projectId/test-runs/$runId"
        post(client, "$base/chat/message", token, mapOf("message" to "밤에 늑대", "autoApply" to false, "model" to "openai/gpt-6-luna", "keySource" to "platform"))
        Thread.sleep(2000)
        val drafted = objectMapper.readTree(get(client, "$base/chat/draft", token))

        post(client, "$base/chat/draft/discard", token, mapOf("draft_ids" to listOf(drafted[0]["draft_id"].asText())))

        assertThat(objectMapper.readTree(get(client, "$base/case-recommendations", token))[0]["status"].asText()).isEqualTo("DISCARDED")
    }

    /** 화면 이름이 이미 `화면` 으로 끝나면 한 번 더 붙이지 않는다. */
    @Test
    fun `추천 저장도 초안 여부를 받아 검토 상태와 검증 상태를 구분한다`(): Unit = runBlocking {
        val client = WebClient.create("http://localhost:$port")
        val (projectId, runId, token) = world("review-draft")
        val rec = recommendations.recommendSceneMove(projectId, runId, "광장", "숲", "길을 간다")!!
        post(client, "/api/projects/$projectId/test-runs/$runId/case-recommendations/save", token,
            mapOf("ids" to listOf(rec.id.toString()), "draft" to true))
        val saved = objectMapper.readTree(get(client, "/api/projects/$projectId/test-runs/$runId/case-recommendations", token))
        val caseId = saved[0]["testCaseId"].asText()
        val tc = objectMapper.readTree(get(client, "/api/projects/$projectId/test-cases/$caseId", token))
        assertThat(tc["reviewStatus"].asText()).isEqualTo("DRAFT")
        assertThat(tc["verificationStatus"].asText()).isEqualTo("DRAFT")
    }

    @Test
    fun `길 추천은 화면 이름에 화면을 겹쳐 붙이지 않는다`(): Unit = runBlocking {
        val (projectId, runId, _) = world("5")
        val named = recommendations.recommendSceneMove(projectId, runId, "전투", "첫 단어 퍼즐 화면", "조작 없이 기다린다")!!
        val plain = recommendations.recommendSceneMove(projectId, runId, "광장", "숲", "광장 오른쪽 길로 간다")!!

        assertThat(named.expectedValue).isEqualTo("첫 단어 퍼즐 화면으로 바뀐다.")
        assertThat(named.why).startsWith("전투에서 첫 단어 퍼즐 화면으로 가는 길을 알려 주셨어요.")
        assertThat(plain.expectedValue).isEqualTo("숲 화면으로 바뀐다.")
    }
}
