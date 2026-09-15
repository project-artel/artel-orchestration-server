package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaAgentPort
import kr.artel.orchestration.qa.service.QaAgentSession
import kr.artel.orchestration.qa.service.QaAgentSessionContext
import kr.artel.orchestration.qa.service.QaExecutionFailureService
import kr.artel.orchestration.qa.service.QaTryPersistenceService
import kr.artel.orchestration.qa.service.QaTryService
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.socket.WebSocketSession
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * QA try lifecycle 의 세 지점에서 `RUN_STATUS` 가 실제로 나가는지(ARTEL-836).
 *
 * message 조립 자체(필드 값)는 [kr.artel.orchestration.qa.service.QaRunStatusNotifierTest]가 본다.
 * 여기는 배선이다 — `QaTryService`·`QaAgentInboundRouter`·`QaExecutionFailureService`의 각 지점이
 * 실제로 notifier를 부르는지를, `SessionManager`에 register한 mock socket의 outbound에 뜨는
 * 프레임 순서로 확인한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaRunStatusLifecycleIntegrationTest {

    class RecordingAgentPort : QaAgentPort {
        private val counter = AtomicInteger()

        override suspend fun createSession(
            context: QaAgentSessionContext,
            onMessage: suspend (QaAgentEnvelope) -> Unit,
            onDisconnect: suspend () -> Unit
        ): QaAgentSession = QaAgentSession("run-status-session-${counter.incrementAndGet()}")

        override suspend fun send(sessionId: String, envelope: QaAgentEnvelope) = Unit

        override suspend fun close(sessionId: String) = Unit
    }

    @TestConfiguration
    class Fakes {
        @Bean @Primary fun recordingAgentPort() = RecordingAgentPort()
    }

    @Autowired private lateinit var service: QaTryService
    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var router: QaAgentInboundRouter
    @Autowired private lateinit var failureService: QaExecutionFailureService
    @Autowired private lateinit var testRunService: TestRunService
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        runScenarioRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testRunRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `단일 시나리오 try는 WAITING_AGENT 다음 RUNNING 다음 FINISHED(PASSED) 순으로 나간다`(): Unit = runBlocking {
        val seed = idleInstance()
        val messages = subscribe(seed.instanceId)

        val started = service.create(seed.scenarioId, seed.instanceId, seed.ownerId)

        assertThat(messages).hasSize(2)
        assertThat(messages[0]).contains("\"state\":\"WAITING_AGENT\"", "\"qaRunId\":null")
        assertThat(messages[1]).contains("\"state\":\"RUNNING\"")
        assertThat(messages[0]).contains("\"qaTryId\":${started.id}")
        assertThat(messages[1]).contains("\"qaTryId\":${started.id}")

        router.handle(terminalFrame(started.id.toLong(), result = "PASSED"))

        assertThat(messages).hasSize(3)
        assertThat(messages[2]).contains("\"state\":\"FINISHED\"", "\"outcome\":\"PASSED\"")
    }

    @Test
    fun `agent가 STATUS로 FAILED를 보고하면 outcome도 FAILED다`(): Unit = runBlocking {
        val seed = idleInstance()
        val messages = subscribe(seed.instanceId)
        val started = service.create(seed.scenarioId, seed.instanceId, seed.ownerId)
        messages.clear()

        router.handle(terminalFrame(started.id.toLong(), result = "FAILED"))

        assertThat(messages).hasSize(1)
        assertThat(messages[0]).contains("\"state\":\"FINISHED\"", "\"outcome\":\"FAILED\"")
    }

    @Test
    fun `run 시작은 첫 시나리오 try로 WAITING_AGENT 다음 RUNNING을 보낸다`(): Unit = runBlocking {
        val seed = idleInstance()
        val messages = subscribe(seed.instanceId)

        val started = service.createRun(seed.testRunId, seed.instanceId, seed.ownerId, label = "nightly-2x2")

        assertThat(messages).hasSize(2)
        val firstTryId = started.tries.first().id
        assertThat(messages[0]).contains("\"state\":\"WAITING_AGENT\"", "\"qaRunId\":${started.id}", "\"qaTryId\":$firstTryId")
        assertThat(messages[1]).contains(
            "\"state\":\"RUNNING\"", "\"qaRunId\":${started.id}", "\"label\":\"nightly-2x2\"", "\"qaTryId\":$firstTryId"
        )
    }

    @Test
    fun `run 의 두 번째 시나리오가 활성될 때도 RUNNING 이 나간다`(): Unit = runBlocking {
        val seed = idleInstance()
        val scenarioIds = testRunService.getScenarios(seed.testRunId, seed.ownerId)!!
            .items.map { it.testScenarioId.toLong() }
        val started = persistence.createRunStarting(seed.testRunId, seed.instanceId, seed.ownerId, scenarioIds)
        // 여기까지는 자리를 만드는 것뿐이다. 알림은 QaTryService 가 걸므로 persistence 를 직접
        // 부르는 이 준비 과정에서는 아무것도 나가지 않는다. 첫 시나리오의 WAITING_AGENT 와
        // RUNNING 은 `run 시작은 첫 시나리오 try로...` 가 service 경로로 본다. 이 테스트가 보는
        // 것은 그다음, 시나리오 2 가 활성될 때다.
        persistence.attachRunAndMarkRunning(
            started.qaRun, started.tries.first().id!!, "run-status-run-session", objectMapper.createObjectNode()
        )
        val messages = subscribe(seed.instanceId)

        // 시나리오 1 종단 — 활성 유니크를 비워야 시나리오 2가 활성된다.
        val now = Instant.now()
        assertThat(qaTryRepository.transition(started.tries.first().id!!, "RUNNING", "COMPLETED", now, now))
            .isEqualTo(1)

        router.handle(logFrame(started.tries[1].id!!, "scenario 2 turn"))

        assertThat(messages).hasSize(1)
        assertThat(messages[0]).contains("\"state\":\"RUNNING\"", "\"qaTryId\":${started.tries[1].id}")
    }

    @Test
    fun `agent 연결이 끊기면 outcome ERROR로 FINISHED가 나간다`(): Unit = runBlocking {
        val seed = idleInstance()
        val messages = subscribe(seed.instanceId)
        val started = service.create(seed.scenarioId, seed.instanceId, seed.ownerId)
        messages.clear()

        failureService.agentDisconnected(started.id.toLong())

        assertThat(messages).hasSize(1)
        assertThat(messages[0]).contains("\"state\":\"FINISHED\"", "\"outcome\":\"ERROR\"")
    }

    @Test
    fun `운영자가 취소하면 outcome CANCELLED로 FINISHED가 나간다`(): Unit = runBlocking {
        val seed = idleInstance()
        val messages = subscribe(seed.instanceId)
        val started = service.create(seed.scenarioId, seed.instanceId, seed.ownerId)
        messages.clear()

        service.cancel(started.id.toLong(), seed.ownerId)

        assertThat(messages).hasSize(1)
        assertThat(messages[0]).contains("\"state\":\"FINISHED\"", "\"outcome\":\"CANCELLED\"")
    }

    // ----------------------------------------------------------------- helpers

    /** 등록된 mock socket의 outbound를 실시간으로 받아 적재하는 리스트를 돌려준다. */
    /**
     * 이 instance 의 socket 으로 나가는 `RUN_STATUS` 만 모은다.
     *
     * 같은 socket 으로 `QaReadingsService` 의 `start_readings` · `stop_readings` ACTION 도 나간다.
     * 그것까지 세면 이 테스트가 보는 대상이 아닌 message 가 개수를 흔들어, RUN_STATUS 가 제대로
     * 나갔는데도 단정이 깨진다.
     */
    private fun subscribe(instanceId: Long): CopyOnWriteArrayList<String> {
        val messages = CopyOnWriteArrayList<String>()
        val outbound = requireNotNull(
            sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))
        )
        outbound.subscribe { if (it.contains("\"type\":\"RUN_STATUS\"")) messages.add(it) }
        return messages
    }

    private fun terminalFrame(qaTryId: Long, result: String) =
        QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = "STATUS",
            qaTryId = qaTryId.toString(),
            timestamp = Instant.now(),
            payload = objectMapper.readTree("""{"status":"COMPLETED","result":"$result","message":"scenario done"}""")
        )

    private fun logFrame(qaTryId: Long, message: String) =
        QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = "LOG",
            qaTryId = qaTryId.toString(),
            timestamp = Instant.now(),
            payload = objectMapper.readTree("""{"message":"$message"}""")
        )

    private data class Seed(
        val ownerId: Long,
        val instanceId: Long,
        val scenarioId: Long,
        val testRunId: Long
    )

    /** 프로젝트·인스턴스·시나리오 2개짜리 테스트 런까지 세운다. SDK는 아직 붙지 않았다. */
    private suspend fun idleInstance(): Seed {
        val owner = signIn()
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "run-status-p", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!, appUserId = ownerId, role = ProjectRole.OWNER.name, createdAt = now
            )
        )
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!, name = "inst", platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now
            )
        )!!
        val testRun = testRunRepository.save(TestRunEntity(projectId = project.id!!, name = "런"))!!
        val s1 = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!
        val s2 = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!
        testRunService.setScenarios(testRun.id!!, ownerId, listOf(s1.id!!, s2.id!!))
        return Seed(ownerId, instance.id!!, s1.id!!, testRun.id!!)
    }

    private suspend fun signIn(): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = UUID.randomUUID().toString(),
                login = "run-status",
                displayName = "run-status",
                avatarUrl = null,
                email = "run-status@example.com"
            )
        )!!
}
