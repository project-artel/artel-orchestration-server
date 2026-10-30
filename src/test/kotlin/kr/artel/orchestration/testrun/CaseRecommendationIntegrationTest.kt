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

        post(client, "$base/chat/message", token, mapOf("message" to "밤에 늑대", "autoApply" to false))
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
        post(client, "$base/chat/message", token, mapOf("message" to "밤에 늑대", "autoApply" to false))
        Thread.sleep(2000)

        post(client, "$base/case-recommendations/discard", token, mapOf("ids" to emptyList<String>()))

        val drafted = objectMapper.readTree(get(client, "$base/chat/draft", token))
        assertThat(drafted[0]["steps"][1].path("recommendation_id").isMissingNode || drafted[0]["steps"][1]["recommendation_id"].isNull).isTrue()
        assertThat(drafted[0]["steps"][1]["case_id"].isNull).isTrue()
        assertThat(objectMapper.readTree(get(client, "$base/case-recommendations", token))[0]["status"].asText()).isEqualTo("DISCARDED")
    }
}
