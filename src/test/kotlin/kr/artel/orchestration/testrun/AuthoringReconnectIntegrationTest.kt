package kr.artel.orchestration.testrun

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
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
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 처음 여는 프로젝트는 계약 정리로 몇 분이 걸린다(TC 73건 136초). 그동안 화면이 지금 상태를 알고, 다시 붙어도 그 상태를
 * 받고, 취소하면 멈춰야 한다. 목 Agent 는 계약 정리를 늦게 돌려주고, 세션이 열리면 센다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthoringReconnectIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository

    companion object {
        private lateinit var mockAgent: DisposableServer
        private val sessionsOpened = AtomicInteger()

        @JvmStatic
        @DynamicPropertySource
        fun registerAgentUrls(registry: DynamicPropertyRegistry) {
            mockAgent = HttpServer.create().port(0).route { routes ->
                routes.post("/case-contracts") { _, response ->
                    response.header("Content-Type", "application/json")
                        .sendString(Mono.just("""{"terms":[],"start_state":{},"contracts":[]}""").delayElement(Duration.ofSeconds(6)))
                        .then()
                }
                routes.post("/sessions") { _, response ->
                    sessionsOpened.incrementAndGet()
                    response.header("Content-Type", "application/json")
                        .sendString(Mono.just("""{"session_id":"slow-sid-1"}"""))
                        .then()
                }
                routes.ws("/sessions/{id}") { inbound, _ -> inbound.receive().then() }
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

    private val sseType = object : ParameterizedTypeReference<ServerSentEvent<ScenarioStreamEvent>>() {}

    @Test
    fun `계약 정리 중에는 그 단계가 보이고, 다시 붙으면 다시 받고, 취소하면 Agent 에 아무것도 가지 않는다`(): Unit = runBlocking {
        val client = WebClient.create("http://localhost:$port")
        val user = oauthUserService.upsert(
            OAuthIdentity(provider = "github", providerUserId = "slow-1", login = "slow-1", displayName = "Slow", avatarUrl = null, email = null)
        )
        val token = jwtService.issue(user)
        val now = java.time.Instant.now()
        val project = projectRepository.save(ProjectEntity(name = "slow", genre = "RPG", createdAt = now, updatedAt = now))
        projectMemberRepository.save(ProjectMemberEntity(projectId = project.id!!, appUserId = user.userId.toLong(), role = "OWNER", createdAt = now))
        testCaseRepository.save(TestCaseEntity(projectId = project.id!!, scene = "숲", step = "늑대한테 다가간다.", expectedValue = "늑대가 나타난다."))
        val runId = runRepository.save(TestRunEntity(projectId = project.id!!, name = "런")).id!!
        val base = "/api/projects/${project.id}/test-runs/$runId/chat"

        fun listen(into: MutableList<ScenarioStreamEvent>) = client.get()
            .uri("$base/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToFlux(sseType)
            .doOnNext { sse -> sse.data()?.let(into::add) }
            .subscribe()

        // 붙자마자 지금 턴 상태가 온다. 도는 턴이 없으면 단계가 없다.
        val first = CopyOnWriteArrayList<ScenarioStreamEvent>()
        val firstStream = listen(first)
        Thread.sleep(800)
        assertThat(first.first().type).isEqualTo("turn")
        assertThat(first.first().stage).isNull()

        client.post()
            .uri("$base/message")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue(mapOf("message" to "숲 시나리오 만들어줘", "autoApply" to true))
            .retrieve().toBodilessEntity().block(Duration.ofSeconds(5))
        Thread.sleep(800)
        assertThat(first.filter { it.type == "progress" }.map { it.stage?.wire }).contains("preparing_cases")
        firstStream.dispose()

        // 연결이 끊겼다 다시 붙었다. 끊긴 사이의 진행은 다시 오지 않으므로, 지금 어디인지를 맨 먼저 받는다.
        val again = CopyOnWriteArrayList<ScenarioStreamEvent>()
        val againStream = listen(again)
        Thread.sleep(800)
        assertThat(again.first().type).isEqualTo("turn")
        assertThat(again.first().stage?.wire).isEqualTo("preparing_cases")

        val cancelled = client.post()
            .uri("$base/cancel")
            .cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block(Duration.ofSeconds(5))!!
        assertThat(cancelled).contains("\"cancelled\":true")
        Thread.sleep(800)
        assertThat(again.filter { it.type == "notice" }.map { it.message }).anyMatch { it!!.contains("요청을 취소했습니다") }
        againStream.dispose()

        // 정리가 끝나도 취소한 턴은 세션을 열지 않는다.
        Thread.sleep(6_500)
        assertThat(sessionsOpened.get()).isZero()
        val after = CopyOnWriteArrayList<ScenarioStreamEvent>()
        val afterStream = listen(after)
        Thread.sleep(800)
        afterStream.dispose()
        assertThat(after.first().stage).isNull()
    }
}
