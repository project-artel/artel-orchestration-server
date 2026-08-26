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
import kr.artel.orchestration.qa.service.ActiveQaRunException
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentPort
import kr.artel.orchestration.qa.service.QaAgentSession
import kr.artel.orchestration.qa.service.QaAgentSessionContext
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
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * 스테일 런이 게임 인스턴스를 영구 점유하지 않게 하는 두 가지.
 *
 * 1. **취소는 활성 try가 없어도 Agent 세션을 끊는다.** 런에는 활성 try가 없는 창이 있다 —
 *    시나리오 N이 끝나고 N+1의 첫 프레임이 오기 전(그 사이 Agent는 게임을 리셋한다), 그리고
 *    세션이 붙기 전. 그 창에서 취소하면 DB만 닫히고 Agent는 다음 시나리오를 계속 돌렸다.
 * 2. **`force`로 진행 중인 런을 이어받는다.** Orchestration이 배포로 재시작하면 소켓만 죽고
 *    DB의 런은 RUNNING으로 남는다. 그 상태를 운영자에게 "먼저 종료하고 오라"고 돌려보내는 대신,
 *    같은 요청에 끊고 시작하는 길을 준다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaRunTakeoverIntegrationTest {

    /** 세션마다 다른 id를 주어, 이어받기 뒤의 런이 **새** 세션을 잡았는지 구분되게 한다. */
    class RecordingAgentPort : QaAgentPort {
        private val counter = AtomicInteger()
        val closed: MutableList<String> = CopyOnWriteArrayList()

        override suspend fun createSession(
            context: QaAgentSessionContext,
            onMessage: suspend (QaAgentEnvelope) -> Unit,
            onDisconnect: suspend () -> Unit
        ): QaAgentSession = QaAgentSession("takeover-session-${counter.incrementAndGet()}")

        override suspend fun send(sessionId: String, envelope: QaAgentEnvelope) = Unit

        override suspend fun close(sessionId: String) {
            closed += sessionId
        }
    }

    @TestConfiguration
    class Fakes {
        @Bean @Primary fun recordingAgentPort() = RecordingAgentPort()
    }

    @Autowired private lateinit var service: QaTryService
    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var testRunService: TestRunService
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var agentPort: RecordingAgentPort
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
        agentPort.closed.clear()
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
    fun `취소는 활성 try가 없는 시나리오 사이 창에서도 런의 Agent 세션을 끊는다`(): Unit = runBlocking {
        val seed = runningRun()
        // 시나리오 1 종단 + 시나리오 2는 아직 PENDING = 활성 try가 하나도 없는 그 창.
        completeFirstScenario(seed.firstTryId)
        assertThat(qaTryRepository.findActiveByGameInstanceId(seed.instanceId)).isNull()

        service.cancelRun(seed.runId, seed.ownerId)

        // 활성 try가 없어 CANCEL 프레임은 보낼 데가 없다. 그래도 세션은 끊겨야 Agent가 멈춘다.
        assertThat(agentPort.closed).contains(seed.sessionId)
        assertThat(qaRunRepository.findById(seed.runId)!!.status).isEqualTo("CANCELLED")
    }

    @Test
    fun `force 없이 시작하면 진행 중인 런 때문에 qa_run_active로 거절된다`(): Unit = runBlocking {
        val seed = runningRun()

        val thrown = runCatching { service.createRun(seed.testRunId, seed.instanceId, seed.ownerId) }
            .exceptionOrNull()

        assertThat(thrown).isInstanceOf(ActiveQaRunException::class.java)
        // FE는 이 code로 "종료하고 실행할까요?"를 띄운다. 산문 message가 아니라 code가 계약이다.
        assertThat((thrown as ActiveQaRunException).code).isEqualTo("qa_run_active")
        assertThat(qaRunRepository.findById(seed.runId)!!.status).isEqualTo("RUNNING")
    }

    @Test
    fun `force로 시작하면 진행 중인 런을 끊고 새 런이 그 인스턴스를 잡는다`(): Unit = runBlocking {
        val seed = runningRun()
        completeFirstScenario(seed.firstTryId)

        val started = service.createRun(seed.testRunId, seed.instanceId, seed.ownerId, force = true)

        // 이전 런은 취소로 닫히고, 그 세션은 끊긴다.
        assertThat(qaRunRepository.findById(seed.runId)!!.status).isEqualTo("CANCELLED")
        assertThat(qaTryRepository.findById(seed.pendingTryId)!!.status).isEqualTo("FAILED")
        assertThat(agentPort.closed).contains(seed.sessionId)
        // 새 런이 같은 인스턴스에서 실행 중이고, 그 인스턴스의 활성 런은 이제 새 것 하나뿐이다.
        assertThat(started.status).isEqualTo("RUNNING")
        assertThat(started.id).isNotEqualTo(seed.runId.toString())
        assertThat(qaRunRepository.findActiveByGameInstanceId(seed.instanceId)!!.id)
            .isEqualTo(started.id.toLong())
    }

    @Test
    fun `force는 진행 중인 런이 없을 때도 그냥 시작한다`(): Unit = runBlocking {
        val seed = idleInstance()

        val started = service.createRun(seed.testRunId, seed.instanceId, seed.ownerId, force = true)

        assertThat(started.status).isEqualTo("RUNNING")
        assertThat(started.tries).hasSize(2)
    }

    // ----------------------------------------------------------------- helpers

    /** 시나리오 1을 정상 종료시킨다 — 활성 유니크(uk_qa_try_active_instance)를 비운다. */
    private suspend fun completeFirstScenario(firstTryId: Long) {
        val now = Instant.now()
        assertThat(qaTryRepository.transition(firstTryId, "RUNNING", "COMPLETED", now, now)).isEqualTo(1)
    }

    private data class Seed(
        val runId: Long,
        val testRunId: Long,
        val firstTryId: Long,
        val pendingTryId: Long,
        val ownerId: Long,
        val instanceId: Long,
        val sessionId: String
    )

    /** 프로젝트·인스턴스(SDK 연결됨)·시나리오 2개짜리 테스트 런까지 세운다. 아직 QA는 없다. */
    private suspend fun idleInstance(): Seed {
        val owner = signIn()
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "takeover-p", genre = "ACTION", createdAt = now, updatedAt = now)
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
        // 시작 전제: 그 인스턴스에 SDK가 붙어 있어야 한다.
        sessionManager.register(instance.id!!.toString(), Mockito.mock(WebSocketSession::class.java))
        val testRun = testRunRepository.save(TestRunEntity(projectId = project.id!!, name = "런"))!!
        val s1 = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!
        val s2 = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!
        testRunService.setScenarios(testRun.id!!, ownerId, listOf(s1.id!!, s2.id!!))
        return Seed(0, testRun.id!!, 0, 0, ownerId, instance.id!!, "")
    }

    /** [idleInstance] 위에 qa_run RUNNING + 첫 시나리오 RUNNING + 두 번째 PENDING을 올린다. */
    private suspend fun runningRun(): Seed {
        val seed = idleInstance()
        val scenarioIds = testRunService.getScenarios(seed.testRunId, seed.ownerId)!!
            .items.map { it.testScenarioId.toLong() }
        val started = persistence.createRunStarting(
            seed.testRunId, seed.instanceId, seed.ownerId, scenarioIds
        )
        val sessionId = "takeover-stale-session"
        persistence.attachRunAndMarkRunning(
            started.qaRun, started.tries.first().id!!, sessionId, objectMapper.createObjectNode()
        )
        return seed.copy(
            runId = started.qaRun.id!!,
            firstTryId = started.tries.first().id!!,
            pendingTryId = started.tries[1].id!!,
            sessionId = sessionId
        )
    }

    private suspend fun signIn(): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "460",
                login = "takeover",
                displayName = "takeover",
                avatarUrl = null,
                email = "takeover@example.com"
            )
        )!!
}
