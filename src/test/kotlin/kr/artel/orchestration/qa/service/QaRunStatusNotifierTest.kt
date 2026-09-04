package kr.artel.orchestration.qa.service

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.test.StepVerifier
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `RUN_STATUS` 메시지 조립(ARTEL-836): 상태별 필드, 재연결, socket 없음.
 *
 * 세 lifecycle 지점(`QaTryService`, `QaAgentInboundRouter`, `QaExecutionFailureService`)의 배선은
 * [QaRunStatusLifecycleIntegrationTest]가 다룬다. 여기는 [QaRunStatusNotifier] 자체가 만드는
 * message 의 모양과, socket 이 없거나 활성 try 가 없을 때 조용히 넘어가는지만 본다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaRunStatusNotifierTest {

    @Autowired private lateinit var notifier: QaRunStatusNotifier
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testRunRepository.deleteAll()
        // qa_try 를 지운 뒤에 지운다. 순서를 뒤집으면 qa_try_test_scenario_id_fkey 에 걸린다.
        testScenarioRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `waitingAgent는 outcome 없이 WAITING_AGENT를 보낸다`(): Unit = runBlocking {
        val instance = seedInstance("WordVenture")
        val outbound = requireNotNull(
            sessionManager.register(instance.id.toString(), Mockito.mock(WebSocketSession::class.java))
        )

        StepVerifier.create(outbound.take(1))
            .then { runBlocking { notifier.waitingAgent(qaTryId = 77, gameInstanceId = instance.id, qaRunId = null) } }
            .assertNext { json ->
                assertThat(json).contains("\"type\":\"RUN_STATUS\"")
                assertThat(json).contains("\"state\":\"WAITING_AGENT\"")
                assertThat(json).contains("\"projectName\":\"WordVenture\"")
                assertThat(json).contains("\"qaTryId\":77")
                // 단일 시나리오 경로: qaRunId/testRunName/label/outcome이 모두 비어 있다.
                assertThat(json).contains("\"qaRunId\":null")
                assertThat(json).contains("\"testRunName\":null")
                assertThat(json).contains("\"outcome\":null")
            }
            .expectComplete()
            .verify(Duration.ofSeconds(2))
    }

    @Test
    fun `running은 qa_run에서 label과 test run 이름을 읽는다`(): Unit = runBlocking {
        val instance = seedInstance("WordVenture")
        val run = seedRun(instance.id, testRunName = "타이틀에서 전투까지", label = "nightly-2x2")
        val outbound = requireNotNull(
            sessionManager.register(instance.id.toString(), Mockito.mock(WebSocketSession::class.java))
        )

        StepVerifier.create(outbound.take(1))
            .then {
                runBlocking { notifier.running(qaTryId = 77, gameInstanceId = instance.id, qaRunId = run.id) }
            }
            .assertNext { json ->
                assertThat(json).contains("\"state\":\"RUNNING\"")
                assertThat(json).contains("\"testRunName\":\"타이틀에서 전투까지\"")
                assertThat(json).contains("\"label\":\"nightly-2x2\"")
                assertThat(json).contains("\"qaRunId\":${run.id}")
            }
            .expectComplete()
            .verify(Duration.ofSeconds(2))
    }

    @Test
    fun `finished는 outcome을 싣는다`(): Unit = runBlocking {
        val instance = seedInstance("WordVenture")
        val outbound = requireNotNull(
            sessionManager.register(instance.id.toString(), Mockito.mock(WebSocketSession::class.java))
        )

        StepVerifier.create(outbound.take(1))
            .then {
                runBlocking {
                    notifier.finished(qaTryId = 77, gameInstanceId = instance.id, qaRunId = null, outcome = "PASSED")
                }
            }
            .assertNext { json ->
                assertThat(json).contains("\"state\":\"FINISHED\"")
                assertThat(json).contains("\"outcome\":\"PASSED\"")
            }
            .expectComplete()
            .verify(Duration.ofSeconds(2))
    }

    @Test
    fun `socket이 없으면 예외 없이 조용히 넘어간다`(): Unit = runBlocking {
        // 인스턴스·프로젝트는 실재해 payload 조립까지는 가지만, socket을 register하지 않았다 —
        // SessionManager.send가 IllegalArgumentException을 던지고, notifier가 그것을 삼켜야 한다.
        val instance = seedInstance("WordVenture")

        notifier.waitingAgent(qaTryId = 1, gameInstanceId = instance.id, qaRunId = null)
        notifier.running(qaTryId = 1, gameInstanceId = instance.id, qaRunId = null)
        notifier.finished(qaTryId = 1, gameInstanceId = instance.id, qaRunId = null, outcome = "ERROR")
        // 여기까지 오면(예외가 안 났으면) 성공이다.
    }

    @Test
    fun `없는 game instance로 보내도 조용히 넘어간다`(): Unit = runBlocking {
        notifier.waitingAgent(qaTryId = 1, gameInstanceId = 999_999L, qaRunId = null)
        notifier.onReconnect(999_999L)
        // instance/project를 못 찾으면 send를 시도하지도 않는다 — 그래도 예외는 없다.
    }

    @Test
    fun `onReconnect는 활성 try가 없으면 아무것도 보내지 않는다`(): Unit = runBlocking {
        val instance = seedInstance("WordVenture")
        val outbound = requireNotNull(
            sessionManager.register(instance.id.toString(), Mockito.mock(WebSocketSession::class.java))
        )

        StepVerifier.create(outbound.take(1))
            .then { runBlocking { notifier.onReconnect(instance.id) } }
            .expectTimeout(Duration.ofMillis(500))
    }

    @Test
    fun `onReconnect는 agentSessionId가 없는 활성 try를 WAITING_AGENT로 보낸다`(): Unit = runBlocking {
        val instance = seedInstance("WordVenture")
        seedTry(instance.id, status = "STARTING", agentSessionId = null)
        val outbound = requireNotNull(
            sessionManager.register(instance.id.toString(), Mockito.mock(WebSocketSession::class.java))
        )

        StepVerifier.create(outbound.take(1))
            .then { runBlocking { notifier.onReconnect(instance.id) } }
            .assertNext { json -> assertThat(json).contains("\"state\":\"WAITING_AGENT\"") }
            .expectComplete()
            .verify(Duration.ofSeconds(2))
    }

    @Test
    fun `onReconnect는 agentSessionId가 있는 활성 try를 RUNNING으로 보낸다`(): Unit = runBlocking {
        val instance = seedInstance("WordVenture")
        seedTry(instance.id, status = "RUNNING", agentSessionId = "session-1")
        val outbound = requireNotNull(
            sessionManager.register(instance.id.toString(), Mockito.mock(WebSocketSession::class.java))
        )

        StepVerifier.create(outbound.take(1))
            .then { runBlocking { notifier.onReconnect(instance.id) } }
            .assertNext { json -> assertThat(json).contains("\"state\":\"RUNNING\"") }
            .expectComplete()
            .verify(Duration.ofSeconds(2))
    }

    // ----------------------------------------------------------------- helpers

    private data class SeededInstance(val id: Long, val projectId: Long)

    /**
     * `qa_run.started_by` 와 `qa_try.started_by` 는 `app_user` 를 실제로 가리켜야 한다. 아무 숫자나
     * 넣으면 `qa_run_started_by_fkey` · `qa_try_started_by_fkey` 에 걸려 INSERT 가 실패한다.
     */
    private suspend fun seedUser(): Long =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = UUID.randomUUID().toString(),
                login = "run-status",
                displayName = "run-status",
                avatarUrl = null,
                email = "run-status@example.com"
            )
        )!!.userId.toLong()

    private suspend fun seedInstance(projectName: String): SeededInstance {
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = projectName, genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!, name = "inst", platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now
            )
        )!!
        return SeededInstance(instance.id!!, project.id!!)
    }

    private suspend fun seedRun(gameInstanceId: Long, testRunName: String, label: String?): QaRunEntity {
        val project = requireNotNull(gameInstanceRepository.findById(gameInstanceId)).projectId
        val testRun = testRunRepository.save(TestRunEntity(projectId = project, name = testRunName))!!
        return qaRunRepository.save(
            QaRunEntity(
                testRunId = testRun.id!!, gameInstanceId = gameInstanceId, startedBy = seedUser(),
                status = "RUNNING", label = label, startedAt = Instant.now()
            )
        )!!
    }

    /**
     * `qa_try.test_scenario_id` 는 `test_scenario` 를 실제로 가리켜야 한다. 아무 숫자나 넣으면
     * `qa_try_test_scenario_id_fkey` 에 걸려 INSERT 가 통째로 실패한다.
     */
    private suspend fun seedTry(gameInstanceId: Long, status: String, agentSessionId: String?): QaTryEntity {
        val projectId = requireNotNull(gameInstanceRepository.findById(gameInstanceId)).projectId
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = projectId, title = "run status seed")
        )!!
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!, gameInstanceId = gameInstanceId, startedBy = seedUser(),
                status = status, agentSessionId = agentSessionId, startedAt = Instant.now()
            )
        )!!
    }
}
