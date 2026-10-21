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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
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
