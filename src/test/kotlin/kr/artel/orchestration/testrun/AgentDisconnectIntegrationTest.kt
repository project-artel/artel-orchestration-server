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
import kr.artel.orchestration.testscenario.service.TestScenarioAgentService

/**
 * 답을 기다리던 턴의 Agent 연결이 끊기면 바로 알린다. 목 Agent 는 턴을 받자마자 결과 없이 연결을 닫는다 —
 * Agent 가 다시 뜨며 끊긴 자리다(2026-10-29, 사용자가 194초를 기다렸다).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentDisconnectIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository

    companion object {
        private lateinit var mockAgent: DisposableServer

        @JvmStatic
        @DynamicPropertySource
        fun registerAgentUrls(registry: DynamicPropertyRegistry) {
            mockAgent = HttpServer.create().port(0).route { routes ->
                routes.post("/sessions") { _, response ->
                    response.header("Content-Type", "application/json")
                        .sendString(Mono.just("""{"session_id":"gone-sid-1"}"""))
                        .then()
                }
                // 턴을 받고 답 없이 닫는다. 받는 쪽은 비워 두고, 잠시 뒤 연결을 끊는다.
                routes.ws("/sessions/{id}") { inbound, outbound ->
                    inbound.receive().subscribe()
                    Mono.delay(java.time.Duration.ofMillis(800)).then(outbound.sendClose())
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

    private val sseType = object : ParameterizedTypeReference<ServerSentEvent<ScenarioStreamEvent>>() {}

    @Test
    fun `답을 기다리던 턴의 연결이 끊기면 바로 오류를 알리고 대화에 남긴다`(): Unit = runBlocking {
        val client = WebClient.create("http://localhost:$port")
        val user = oauthUserService.upsert(
            OAuthIdentity(provider = "github", providerUserId = "gone-1", login = "gone-1", displayName = "Gone", avatarUrl = null, email = null)
        )
        val token = jwtService.issue(user)
        val now = java.time.Instant.now()
        val project = projectRepository.save(ProjectEntity(name = "gone", genre = "RPG", createdAt = now, updatedAt = now))
        projectMemberRepository.save(ProjectMemberEntity(projectId = project.id!!, appUserId = user.userId.toLong(), role = "OWNER", createdAt = now))
        val runId = runRepository.save(TestRunEntity(projectId = project.id!!, name = "런")).id!!

        val errors = CopyOnWriteArrayList<ScenarioStreamEvent>()
        val stream = client.get()
            .uri("/api/projects/${project.id}/test-runs/$runId/chat/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToFlux(sseType)
            .doOnNext { if (it.data()?.type == "error") errors.add(it.data()!!) }
            .subscribe()
        Thread.sleep(1000)

        client.post()
            .uri("/api/projects/${project.id}/test-runs/$runId/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue(mapOf("message" to "메뉴 시나리오 만들어줘", "autoApply" to true))
            .retrieve().toBodilessEntity().block(Duration.ofSeconds(10))
        Thread.sleep(2000)
        stream.dispose()

        assertThat(errors.map { it.code }).contains(TestScenarioAgentService.AGENT_DISCONNECTED)
        val history = client.get()
            .uri("/api/projects/${project.id}/test-runs/$runId/chat/messages")
            .cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block(Duration.ofSeconds(5))!!
        assertThat(history).contains("연결이 끊겨 이번 요청을 처리하지 못했어요")
    }
}
