package kr.artel.orchestration.testrun

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.TestScenarioService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * 같은 턴 안에서 앞 시나리오를 저장해 잠금이 좁혀진 틈에 사람이 다른 시나리오의 **제목만** 고치면, 뒤에 오는 AI 결과가 그것을
 * 덮지 않는다(ARTEL-1264, Codex P2). 스텝만 견주던 동안은 제목 · 설명 · 실행 방식만 바뀐 경우가 비교를 지나 덮였다.
 *
 * 목 Agent 는 첫 시나리오를 내고 멈췄다가, 테스트가 [gate] 를 열면 둘째 시나리오와 결과를 낸다. 그 사이에 사람이 고친다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunAuthoringOverwriteIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var scenarioService: TestScenarioService
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository

    companion object {
        private lateinit var mockAgent: DisposableServer

        /** 둘째 시나리오를 내보내도 되는가. 테스트가 사람의 편집을 마친 뒤 연다. */
        private val gate = CompletableFuture<Unit>()

        /** 이번 턴이 고칠 두 시나리오의 번호. 목이 프레임을 만들 때 읽는다. */
        @Volatile private var firstId = 0L
        @Volatile private var secondId = 0L

        private fun submit(messageId: String, scenarioId: Long, title: String) =
            """{"type":"submit_scenario","messageId":"$messageId","scenario":{"scenario_id":$scenarioId,"title":"$title",""" +
                """"description":"AI 가 쓴 설명","steps":[{"action":"AI 가 쓴 스텝"}]}}"""

        @JvmStatic
        @DynamicPropertySource
        fun registerAgentUrls(registry: DynamicPropertyRegistry) {
            mockAgent = HttpServer.create().port(0).route { routes ->
                routes.post("/sessions") { _, response ->
                    response.header("Content-Type", "application/json")
                        .sendString(Mono.just("""{"session_id":"overwrite-sid-1"}""")).then()
                }
                // 연결하면 바로 첫째를 내고, [gate] 가 열리면 둘째와 결과를 낸다.
                routes.ws("/sessions/{id}") { inbound, outbound ->
                    inbound.receive().subscribe()
                    outbound.sendString(
                        Flux.concat(
                            Mono.fromCallable { submit("s1", firstId, "AI 가 고친 첫째") },
                            Mono.fromFuture(gate).map { submit("s2", secondId, "AI 가 고친 둘째") },
                            Mono.just("""{"type":"result","message":"ok","scenarios":[]}"""),
                        ),
                    ).then(Mono.never())
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

    @Test
    fun `앞 시나리오를 저장한 틈에 사람이 고친 제목은 AI 결과가 덮지 않고 draft 로 남긴다`(): Unit = runBlocking {
        val client = WebClient.create("http://localhost:$port")
        val user = oauthUserService.upsert(OAuthIdentity("github", "overwrite-1", "overwrite-1", "Overwriter", null, null))
        val userId = user.userId.toLong()
        val token = jwtService.issue(user)
        val now = Instant.now()
        val projectId = projectRepository.save(ProjectEntity(name = "overwrite", genre = "RPG", createdAt = now, updatedAt = now)).id!!
        projectMemberRepository.save(ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "OWNER", createdAt = now))
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        fun scenario(title: String) = runBlocking {
            scenarioRepository.save(
                TestScenarioEntity(projectId = projectId, title = title, steps = Json.of("""[{"action":"처음 스텝"}]""")),
            ).id!!
        }
        firstId = scenario("첫째")
        secondId = scenario("둘째")
        runScenarioRepository.save(TestRunScenarioEntity(testRunId = runId, testScenarioId = firstId, position = 0))
        runScenarioRepository.save(TestRunScenarioEntity(testRunId = runId, testScenarioId = secondId, position = 1))

        client.post().uri("/api/projects/$projectId/test-runs/$runId/chat/message")
            .contentType(MediaType.APPLICATION_JSON).cookie("artel_access_token", token)
            .bodyValue("""{"model":"openai/gpt-6-luna","keySource":"platform","message":"두 시나리오를 고쳐 줘"}""")
            .retrieve().toEntity(String::class.java).block(Duration.ofSeconds(5))

        // 첫째가 저장되면 잠금이 첫째로 좁혀진다. 그 틈에 사람이 둘째의 제목만 고친다.
        val deadline = System.currentTimeMillis() + 10_000
        while (scenarioRepository.findById(firstId)!!.title != "AI 가 고친 첫째" && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertThat(scenarioRepository.findById(firstId)!!.title).isEqualTo("AI 가 고친 첫째")
        // 스텝은 그대로 두고 제목만 바꾼다 — 스텝 비교로는 잡히지 않는 경우다.
        val before = scenarioRepository.findById(secondId)!!.toDraft(objectMapper)
        scenarioService.testScenarioUpdate(userId, secondId, before.copy(title = "사람이 고친 둘째"))
        gate.complete(Unit)

        // 둘째는 덮지 않고 draft 로 남는다.
        val drafted = (1..50).firstNotNullOfOrNull {
            val body = client.get().uri("/api/projects/$projectId/test-runs/$runId/chat/draft").cookie("artel_access_token", token)
                .retrieve().bodyToMono(String::class.java).block(Duration.ofSeconds(5))
            objectMapper.readTree(body).firstOrNull { it.path("scenario_id").asLong() == secondId } ?: run { Thread.sleep(200); null }
        }
        assertThat(drafted).describedAs("덮지 않은 AI 결과는 draft 로 남는다").isNotNull
        assertThat(scenarioRepository.findById(secondId)!!.title).isEqualTo("사람이 고친 둘째")
    }
}
