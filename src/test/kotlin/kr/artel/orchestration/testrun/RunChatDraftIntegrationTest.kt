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
 * 자동저장을 끈 턴의 결과는 draft 로 남는다(ARTEL-1074).
 *
 * 이 스위트가 생긴 이유는 그전의 실패다 — 시나리오를 하나씩 받게 된 뒤 자동저장을 끄면 `result` 의
 * `scenarios` 가 빈 채로 와서 화면이 받을 것이 없었고, **아무것도 저장되지도 보이지도 않았다.**
 *
 * 목 Agent 는 연결하자마자 시나리오 하나를 내고 턴을 끝낸다. 다음 턴에는 앞서 받은 음수 번호로 같은
 * draft 를 고쳐 낸다 — "draft 위에서 이어 고친다" 가 그 번호 하나로 성립하는지가 이 검사의 절반이다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunChatDraftIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var oauthUserService: OAuthUserService

    @Autowired
    private lateinit var runRepository: TestRunRepository

    @Autowired
    private lateinit var runScenarioRepository: TestRunScenarioRepository

    @Autowired
    private lateinit var scenarioRepository: TestScenarioRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var projectMemberRepository: ProjectMemberRepository

    companion object {
        private lateinit var mockAgent: DisposableServer

        private const val FIRST_SUBMIT =
            """{"type":"submit_scenario","messageId":"s1","scenario":{"title":"첫 draft","description":"d","steps":[{"action":"메뉴를 연다"}]}}"""

        /** 둘째 턴. 앞 턴의 새 draft 를 **음수 번호로** 다시 고친다. */
        private const val SECOND_SUBMIT =
            """{"type":"submit_scenario","messageId":"s2","scenario":{"scenario_id":-1,"title":"다시 쓴 draft","description":"d","steps":[{"action":"메뉴를 연다"},{"action":"닫는다"}]}}"""

        private const val RESULT = """{"type":"result","message":"ok","scenarios":[]}"""

        /** 목 Agent 가 받은 프레임. 둘째 턴의 `current_scenarios` 를 여기서 본다. */
        private val inboundFrames = CopyOnWriteArrayList<String>()

        private val seq = AtomicLong(8000)

        @JvmStatic
        @DynamicPropertySource
        fun registerAgentUrls(registry: DynamicPropertyRegistry) {
            mockAgent = HttpServer.create().port(0).route { routes ->
                routes.post("/sessions") { _, response ->
                    response.header("Content-Type", "application/json")
                        .sendString(Mono.just("""{"session_id":"draft-sid-1"}"""))
                        .then()
                }
                routes.ws("/sessions/{id}") { inbound, outbound ->
                    val replies = inbound.receive().asString().flatMapIterable { frame ->
                        inboundFrames.add(frame)
                        if (frame.contains("\"type\":\"turn\"")) listOf(SECOND_SUBMIT, RESULT) else emptyList()
                    }
                    outbound.sendString(Flux.concat(Flux.just(FIRST_SUBMIT, RESULT), replies)).then()
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

    private fun webClient() = WebClient.create("http://localhost:$port")

    private val sseType = object : ParameterizedTypeReference<ServerSentEvent<ScenarioStreamEvent>>() {}

    private suspend fun issueUser(providerUserId: String): Pair<Long, String> {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = "drafter-$providerUserId",
                displayName = "Drafter",
                avatarUrl = null,
                email = null,
            )
        )!!
        return user.userId.toLong() to jwtService.issue(user)
    }

    private suspend fun memberProject(appUserId: Long): Long {
        val now = java.time.Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "draft-project", genre = "RPG", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = appUserId, role = "OWNER", createdAt = now)
        )
        return project.id!!
    }

    /** 말을 보내고 응답 상태와 본문을 돌려준다. draft 가 있는 동안의 거절(409)도 그대로 본다. */
    private fun say(
        client: WebClient, projectId: Long, runId: Long, token: String, text: String, draftOnly: Boolean = false,
    ): Pair<Int, String> =
        client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue(mapOf("message" to text, "autoApply" to false, "draftOnly" to draftOnly))
            .exchangeToMono { response ->
                response.bodyToMono(String::class.java).defaultIfEmpty("").map { response.statusCode().value() to it }
            }
            .block(Duration.ofSeconds(5))!!

    private fun draft(client: WebClient, projectId: Long, runId: Long, token: String): List<DraftScenario> =
        client.get()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/draft")
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToFlux(DraftScenario::class.java)
            .collectList()
            .block(Duration.ofSeconds(5))!!

    @Test
    fun `자동저장을 끄면 draft 로 남고, 이어 말하면 draft 를 고치고, 승인해야 저장된다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("draft-${seq.incrementAndGet()}")
        val projectId = memberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val draftEvents = CopyOnWriteArrayList<ScenarioStreamEvent>()
        val stream = client.get()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToFlux(sseType)
            .doOnNext { if (it.event() == "draft") draftEvents.add(it.data()!!) }
            .subscribe()
        Thread.sleep(1000)

        // 첫 턴 — 시나리오 하나가 draft 로 남는다. 런에는 아무것도 없다.
        say(client, projectId, runId, token, "메뉴 시나리오 만들어줘")
        Thread.sleep(1500)
        val first = draft(client, projectId, runId, token)
        assertThat(first.map { it.draftId to it.title }).containsExactly("new-1" to "첫 draft")
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()).isEmpty()
        assertThat(draftEvents).anyMatch { it.drafts?.map { d -> d.title } == listOf("첫 draft") }

        // draft 가 있는 동안 새 요청은 받지 않는다 — draft 만 고치는 말로 보내야 한다.
        val refused = say(client, projectId, runId, token, "다른 시나리오도 만들어줘")
        assertThat(refused.first).isEqualTo(409)
        assertThat(refused.second).contains("draft_pending")
        assertThat(inboundFrames).noneMatch { it.contains("다른 시나리오도") }

        // 둘째 턴 — draft 만 고치는 말. Agent 는 draft 만 본다. 새 draft 는 음수 번호다.
        assertThat(say(client, projectId, runId, token, "닫는 것까지 넣어줘", draftOnly = true).first).isEqualTo(200)
        Thread.sleep(1500)
        val turn = objectMapper.readTree(inboundFrames.last { it.contains("닫는 것까지") })
        assertThat(turn.get("current_scenarios").map { it.get("scenario_id").asLong() to it.get("title").asText() })
            .containsExactly(-1L to "첫 draft")
        assertThat(turn.toString()).contains("승인 전 draft 만 고친다")

        // 음수 번호로 돌아온 것은 **같은 draft** 를 고친 것이다. 새것이 하나 더 생기지 않는다.
        val second = draft(client, projectId, runId, token)
        assertThat(second.map { it.draftId to it.title }).containsExactly("new-1" to "다시 쓴 draft")
        assertThat(second.single().steps).hasSize(2)
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()).isEmpty()

        // 승인하면 그제야 런에 들어간다. draft 는 비고, 화면에도 빈 draft 가 간다.
        val applied = client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/draft/apply")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue(mapOf("draft_ids" to listOf("new-1")))
            .retrieve()
            .bodyToMono(DraftApplyResult::class.java)
            .block(Duration.ofSeconds(10))!!
        assertThat(applied.applied).isEqualTo(1)
        assertThat(applied.drafts).isEmpty()
        val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        assertThat(links).hasSize(1)
        assertThat(scenarioRepository.findById(links.single().testScenarioId)!!.title).isEqualTo("다시 쓴 draft")
        Thread.sleep(300)
        assertThat(draftEvents.last().drafts).isEmpty()

        // draft 가 없으면 draft 만 고치는 말은 받지 않고, 보통 말은 다시 받는다.
        val noDraft = say(client, projectId, runId, token, "고쳐줘", draftOnly = true)
        assertThat(noDraft.first).isEqualTo(409)
        assertThat(noDraft.second).contains("no_draft")

        stream.dispose()
    }
}
