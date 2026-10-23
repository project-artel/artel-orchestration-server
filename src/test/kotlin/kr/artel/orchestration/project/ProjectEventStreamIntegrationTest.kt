package kr.artel.orchestration.project

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.event.ProjectChangeEvent
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testscenario.dto.ScenarioExecutionMode
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** `GET /api/projects/{projectId}/events` 통합 테스트(ARTEL-1123). */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectEventStreamIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var streamManager: ProjectChangeStreamManager
    @Autowired private lateinit var scenarios: TestScenarioRepository
    @Autowired private lateinit var database: DatabaseClient

    private val sseType = object : ParameterizedTypeReference<ServerSentEvent<ProjectChangeEvent>>() {}

    @BeforeEach
    fun clean(): Unit = runBlocking {
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `sends ready right after subscribing`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)

        val events = CopyOnWriteArrayList<ServerSentEvent<ProjectChangeEvent>>()
        val subscription = subscribeSse(projectId, token) { events.add(it) }

        awaitUntil { events.isNotEmpty() }
        assertThat(events.first().event()).isEqualTo("ready")

        subscription.dispose()
    }

    @Test
    fun `pushes a changed frame carrying only the topic`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        val events = CopyOnWriteArrayList<ServerSentEvent<ProjectChangeEvent>>()
        val subscription = subscribeSse(projectId, token) { events.add(it) }
        awaitUntil { events.any { it.event() == "ready" } }

        streamManager.notify(projectId.toLong(), ProjectChangeTopic.GAME_INSTANCES)

        awaitUntil { events.any { it.event() == "changed" } }
        val changed = events.single { it.event() == "changed" }
        assertThat(changed.data()?.topic).isEqualTo(ProjectChangeTopic.GAME_INSTANCES)

        subscription.dispose()
    }

    @Test
    fun `creating a test case folder notifies TEST_CASES`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        val events = CopyOnWriteArrayList<ServerSentEvent<ProjectChangeEvent>>()
        val subscription = subscribeSse(projectId, token) { events.add(it) }
        awaitUntil { events.any { it.event() == "ready" } }

        post(token, "/api/projects/$projectId/test-case-folders", """{"name":"내 folder"}""")

        awaitUntil { events.any { it.data()?.topic == ProjectChangeTopic.TEST_CASES } }

        subscription.dispose()
    }

    @Test
    fun `does not deliver another project's changes`(): Unit = runBlocking {
        val token = signIn()
        val watched = createProject(token)
        val other = createProject(token)
        val events = CopyOnWriteArrayList<ServerSentEvent<ProjectChangeEvent>>()
        val subscription = subscribeSse(watched, token) { events.add(it) }
        awaitUntil { events.any { it.event() == "ready" } }

        streamManager.notify(other.toLong(), ProjectChangeTopic.ISSUES)
        streamManager.notify(watched.toLong(), ProjectChangeTopic.MACROS)

        awaitUntil { events.any { it.event() == "changed" } }
        assertThat(events.filter { it.event() == "changed" }.map { it.data()?.topic })
            .containsExactly(ProjectChangeTopic.MACROS)

        subscription.dispose()
    }

    @Test
    fun `refuses to open a stream for a project the caller cannot access`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val strangerToken = signIn("99", "hubot")
        val projectId = createProject(ownerToken)

        val status = client().get()
            .uri("/api/projects/$projectId/events")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .cookie("artel_access_token", strangerToken)
            .exchangeToMono { Mono.just(it.statusCode().value()) }
            .block(Duration.ofSeconds(5))

        assertThat(status).isEqualTo(404)
    }

    @Test
    fun `런 구성 변경과 시나리오 삭제 알림 뒤 최신 개수를 조회한다`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        val runsUri = "/api/projects/$projectId/test-runs"
        val runId = post(token, runsUri, """{"name":"SSE 런"}""")["id"].asText()
        val ordered = scenarios.save(TestScenarioEntity(projectId = projectId.toLong(), title = "ORDERED"))
        val free = scenarios.save(TestScenarioEntity(
            projectId = projectId.toLong(), title = "FREE", executionMode = ScenarioExecutionMode.FREE,
        ))
        val events = CopyOnWriteArrayList<ServerSentEvent<ProjectChangeEvent>>()
        val subscription = subscribeSse(projectId, token) { events.add(it) }
        try {
            awaitUntil { events.any { it.event() == "ready" } }
            fun count() = objectMapper.readTree(client().get().uri(runsUri)
                .cookie("artel_access_token", token).retrieve().bodyToMono(String::class.java).block())
                .get("items").single { it["id"].asText() == runId }["scenarioCount"].asInt()
            fun changes() = events.count { it.data()?.topic == ProjectChangeTopic.TEST_RUNS }
            for ((index, scenario) in listOf(ordered, free).withIndex()) {
                client().put().uri("$runsUri/$runId/scenarios").cookie("artel_access_token", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"scenarioIds":["${scenario.id}"]}""")
                    .retrieve().toBodilessEntity().block()
                awaitUntil { changes() == index + 1 }
                assertThat(count()).isEqualTo(1)
            }

            // 테스트 전용 제약으로 삽입을 실패시켜 앞선 링크 삭제도 rollback되는지 확인한다.
            database.sql("ALTER TABLE test_run_scenario ADD CONSTRAINT sse_test_reject_link CHECK (test_scenario_id <> ${ordered.id})")
                .then().block()
            val failedStatus = try {
                client().put().uri("$runsUri/$runId/scenarios").cookie("artel_access_token", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"scenarioIds":["${ordered.id}"]}""")
                    .exchangeToMono { Mono.just(it.statusCode().value()) }.block()
            } finally {
                database.sql("ALTER TABLE test_run_scenario DROP CONSTRAINT sse_test_reject_link").then().block()
            }
            assertThat(failedStatus).isGreaterThanOrEqualTo(400)
            streamManager.notify(projectId.toLong(), ProjectChangeTopic.MACROS)
            awaitUntil { events.any { it.data()?.topic == ProjectChangeTopic.MACROS } }
            assertThat(changes()).isEqualTo(2)
            assertThat(count()).isEqualTo(1)

            client().delete().uri("/api/test-scenario/${free.id}").cookie("artel_access_token", token)
                .retrieve().toBodilessEntity().block()
            awaitUntil { changes() == 3 }
            assertThat(count()).isZero()

            post(token, "$runsUri/$runId/scenarios/imports", """{"testScenarioIds":["${ordered.id}"]}""")
            awaitUntil { changes() == 4 }
            assertThat(count()).isEqualTo(1)
        } finally {
            subscription.dispose()
        }
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, intervalMs: Long = 50, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "조건이 ${timeoutMs}ms 안에 충족되지 않았다." }
            Thread.sleep(intervalMs)
        }
    }

    private fun subscribeSse(
        projectId: String,
        token: String,
        onEvent: (ServerSentEvent<ProjectChangeEvent>) -> Unit
    ): Disposable = client().get()
        .uri("/api/projects/$projectId/events")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .cookie("artel_access_token", token)
        .retrieve()
        .bodyToFlux(sseType)
        .doOnNext(onEvent)
        .subscribe()

    private fun createProject(token: String): String =
        post(token, "/api/projects", """{"name":"Demo Day","genre":"ACTION"}""")["id"].asText()

    private suspend fun signIn(providerUserId: String = "42", login: String = "octocat"): String {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        )
        return jwtService.issue(user)
    }

    private fun post(token: String, uri: String, body: String) = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun client() = WebClient.create("http://localhost:$port")
}
