package kr.artel.orchestration.testrun

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testrun.dto.RunChatCancellation
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunMessageRepository
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
import java.util.concurrent.atomic.AtomicLong

/**
 * 도는 저작 요청을 **턴만** 끊는다(ARTEL-955). 화면에서 ESC 를 두 번 누른 길이다.
 *
 * 목 Agent 를 [kr.artel.orchestration.TestScenarioPipelineIntegrationTest] 의 것과 다르게 짠 것이
 * 이 스위트의 전부다 — 저쪽은 턴을 받자마자 결과를 돌려주므로 **끊을 틈이 없다.** 여기 목은 턴을
 * 받고 가만히 있다가, `cancel` 을 받고 나서야 `cancelled` 와 **늦은 결과**를 함께 보낸다. 그
 * 늦은 결과가 버려지는 것이 이 기능의 절반이다.
 *
 * 세션은 끊지 않는다. 사용자가 멈추려는 것은 기다림이지 대화가 아니고, `close` 로 대신하면 다음
 * 말이 앞의 대화를 모르는 새 세션을 연다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunChatCancelIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var oauthUserService: OAuthUserService

    @Autowired
    private lateinit var runMessageRepository: TestRunMessageRepository

    @Autowired
    private lateinit var runRepository: TestRunRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var projectMemberRepository: ProjectMemberRepository

    companion object {
        private lateinit var mockAgent: DisposableServer

        /** 늦게 오는 결과. 취소한 뒤에 도착하므로 **대화에도 런에도 남지 않아야** 한다. */
        private const val LATE_RESULT_JSON =
            """{"type":"result","message":"늦게 온 답","scenarios":[{"title":"늦게 온 시나리오","description":"d","steps":[]}]}"""
        private const val CANCELLED_JSON = """{"type":"cancelled","was_running":true}"""

        /** 목 Agent 가 WS 로 받은 프레임 전부. `cancel` 이 실제로 건너갔는지 여기서 본다. */
        private val inboundFrames = CopyOnWriteArrayList<String>()

        private val seq = AtomicLong(7000)

        @JvmStatic
        @DynamicPropertySource
        fun registerAgentUrls(registry: DynamicPropertyRegistry) {
            mockAgent = HttpServer.create().port(0).route { routes ->
                routes.post("/sessions") { _, response ->
                    response.header("Content-Type", "application/json")
                        .sendString(Mono.just("""{"session_id":"cancel-sid-1"}"""))
                        .then()
                }
                // **연결해도 아무것도 보내지 않는다.** 첫 턴이 도는 중인 상태를 그대로 둔다.
                routes.ws("/sessions/{id}") { inbound, outbound ->
                    outbound.sendString(
                        inbound.receive().asString().flatMapIterable { frame ->
                            inboundFrames.add(frame)
                            if (frame.contains("\"type\":\"cancel\"")) listOf(CANCELLED_JSON, LATE_RESULT_JSON)
                            else emptyList()
                        }
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

    private fun webClient() = WebClient.create("http://localhost:$port")

    private val sseType = object : ParameterizedTypeReference<ServerSentEvent<ScenarioStreamEvent>>() {}

    private suspend fun issueUser(providerUserId: String): Pair<Long, String> {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = "canceller-$providerUserId",
                displayName = "Canceller",
                avatarUrl = null,
                email = null,
            )
        )!!
        return user.userId.toLong() to jwtService.issue(user)
    }

    private suspend fun memberProject(appUserId: Long): Long {
        val now = java.time.Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "cancel-project", genre = "RPG", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = appUserId, role = "OWNER", createdAt = now)
        )
        return project.id!!
    }

    private fun cancel(client: WebClient, projectId: Long, runId: Long, token: String): RunChatCancellation? =
        client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/cancel")
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToMono(RunChatCancellation::class.java)
            .block(Duration.ofSeconds(5))

    @Test
    fun `취소는 턴을 끊고 늦게 온 결과를 버린다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("cancel-${seq.incrementAndGet()}")
        val projectId = memberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val notices = CopyOnWriteArrayList<ScenarioStreamEvent>()
        val results = CopyOnWriteArrayList<ScenarioStreamEvent>()
        val stream = client.get()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToFlux(sseType)
            .doOnNext {
                when (it.event()) {
                    "notice" -> notices.add(it.data()!!)
                    "result" -> results.add(it.data()!!)
                }
            }
            .subscribe()
        Thread.sleep(1000)

        // 요청을 보낸다. 목 Agent 는 답하지 않으므로 턴이 도는 중으로 남는다.
        client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue("""{"message":"튜토리얼 시나리오 만들어줘 run$runId"}""")
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))
        Thread.sleep(800)

        val cancelled = cancel(client, projectId, runId, token)
        assertThat(cancelled?.cancelled).isTrue()
        // 자동저장이 켜져 있어도 아직 넘어온 시나리오가 없으므로 지키는 것이 없다.
        assertThat(cancelled?.saved).isEqualTo(0)

        // `cancel` 프레임이 실제로 Agent 까지 갔다. `close` 가 아닌 것이 중요하다 —
        // `close` 였다면 저쪽 세션이 지워져 다음 말이 앞의 대화를 모르는 새 세션을 연다.
        Thread.sleep(600)
        assertThat(inboundFrames).anyMatch { it.contains("\"type\":\"cancel\"") }
        assertThat(inboundFrames).noneMatch { it.contains("\"type\":\"close\"") }

        // 사용자는 끊겼다는 것을 대화에서 본다. 저장까지 하므로 새로고침해도 남는다.
        assertThat(notices).anyMatch { it.message?.contains("요청을 취소했습니다") == true }

        // **늦게 온 결과는 버린다.** 화면으로도 안 나가고, 대화에도 저장되지 않는다.
        Thread.sleep(800)
        assertThat(results).isEmpty()
        val messages = runMessageRepository
            .findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList()
        assertThat(messages.map { it.content }).noneMatch { it.contains("늦게 온 답") }
        assertThat(messages.map { it.role }).contains("USER", "ASSISTANT")

        // 한 번 더 누르면 끊을 턴이 없다고 답한다. 404 가 아니다 — 답이 방금 왔는데 ESC 를
        // 누른 경우가 그 길이고, 그것은 오류가 아니다.
        assertThat(cancel(client, projectId, runId, token)?.cancelled).isFalse()

        stream.dispose()
    }

    @Test
    fun `연타해도 한 번만 끊는다`(): Unit = runBlocking {
        // ESC 를 두 번 누르랬다고 사람이 정확히 두 번만 누르지는 않는다. 급하면 네댓 번
        // 누르고, 그러면 창구도 그만큼 두드려진다. 실측(로컬, 2026-10-07)에서 대화에
        // "요청을 취소했습니다" 가 세 줄 남았다.
        //
        // **이 검사가 증명하는 것은 여기까지다** — 요청이 여럿 와도 끊은 것은 하나이고 대화에
        // 한 줄만 남는다. 진짜 동시 도착은 재현하지 못했다(`compareAndSet` 을 평범한
        // get/set 으로 되돌려도 이 검사는 통과한다). 저 세 줄을 만든 것은 화면 쪽의 묵은 state
        // 였고 그쪽은 ref 로 막았다. 여기 `AtomicBoolean` 은 창을 좁히는 두 번째 자물쇠다.
        val client = webClient()
        val (appUserId, token) = issueUser("burst-${seq.incrementAndGet()}")
        val projectId = memberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        client.post()
            .uri("/api/projects/$projectId/test-runs/$runId/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .cookie("artel_access_token", token)
            .bodyValue("""{"message":"전투 시나리오 만들어줘 run$runId"}""")
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))
        Thread.sleep(800)

        // 넷을 한꺼번에 던진다. 순서대로 부르면 경쟁이 일어나지 않아 이 검사가 아무것도 못 본다.
        val answers = reactor.core.publisher.Flux
            .range(0, 4)
            .flatMap {
                client.post()
                    .uri("/api/projects/$projectId/test-runs/$runId/chat/cancel")
                    .cookie("artel_access_token", token)
                    .retrieve()
                    .bodyToMono(RunChatCancellation::class.java)
            }
            .collectList()
            .block(Duration.ofSeconds(10))!!

        assertThat(answers.count { it.cancelled }).isEqualTo(1)

        // 대화에도 한 줄만 남는다. 사용자가 보는 것은 누른 횟수가 아니라 일어난 일이다.
        Thread.sleep(600)
        val notices = runMessageRepository
            .findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList()
            .filter { it.content.contains("요청을 취소했습니다") }
        assertThat(notices).hasSize(1)
    }

    @Test
    fun `세션이 없으면 끊을 것도 없다`(): Unit = runBlocking {
        val client = webClient()
        val (appUserId, token) = issueUser("idle-${seq.incrementAndGet()}")
        val projectId = memberProject(appUserId)
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!

        val answer = cancel(client, projectId, runId, token)
        assertThat(answer?.cancelled).isFalse()
        assertThat(answer?.saved).isEqualTo(0)
    }

    @Test
    fun `비참여자는 이 런이 없는 것처럼 404 를 받는다`(): Unit = runBlocking {
        val client = webClient()
        val (_, token) = issueUser("outsider-${seq.incrementAndGet()}")
        val now = java.time.Instant.now()
        val other = projectRepository.save(
            ProjectEntity(name = "not-mine", genre = "RPG", createdAt = now, updatedAt = now)
        )!!
        val runId = runRepository.save(TestRunEntity(projectId = other.id!!, name = "런")).id!!

        val status = client.post()
            .uri("/api/projects/${other.id}/test-runs/$runId/chat/cancel")
            .cookie("artel_access_token", token)
            .exchangeToMono { Mono.just(it.statusCode()) }
            .block(Duration.ofSeconds(5))
        assertThat(status?.value()).isEqualTo(404)
    }
}
