package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.repository.QaLaunchReadRepository
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaStatsRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaRunGates
import kr.artel.orchestration.qa.service.QaTryPersistenceService
import kr.artel.orchestration.qa.service.QaTryService
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 모델 API 오류로 멈춘 QA 를 미판정으로 끝내고, 판정 없는 런을 버린다(ARTEL-1371).
 *
 * agent 는 크레딧 · 인증 · 모델 오류가 나면 그 시나리오를 `result=UNDECIDED` 와 `stop` 으로 닫고 런을 멈춘다
 * (ARTEL-1370). 남은 시나리오는 오지 않으므로 orchestration 이 닫아야 런이 끝난다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaRunUndecidedIntegrationTest {

    @Autowired private lateinit var router: QaAgentInboundRouter
    @Autowired private lateinit var service: QaTryService
    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var launchReads: QaLaunchReadRepository
    @Autowired private lateinit var stats: QaStatsRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun before(): Unit = runBlocking { wipe() }

    @AfterEach
    fun after(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testRunRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `첫 시나리오에서 크레딧 오류가 나면 모든 시도와 런이 미판정으로 끝나고 런은 버림이다`(): Unit = runBlocking {
        val run = runningRun(scenarios = 3)

        router.handle(errorFrame(run.tryIds[0]))
        router.handle(undecidedFrame(run.tryIds[0]))

        val errored = qaTryRepository.findById(run.tryIds[0])!!
        assertThat(errored.status).isEqualTo("UNDECIDED")
        assertThat(errored.stopCode).isEqualTo("llm_credit_exhausted")
        assertThat(errored.stopProvider).isEqualTo("anthropic")
        assertThat(errored.stopMessage).isEqualTo("The model API token balance is exhausted, so the run stopped.")
        // 미판정은 판정 컬럼에 오르지 않는다 — agent 가 실은 요약의 0 은 "검증하지 못했다" 이다.
        assertThat(errored.stepsTotal).isNull()

        for (waitingId in run.tryIds.drop(1)) {
            val waiting = qaTryRepository.findById(waitingId)!!
            assertThat(waiting.status).isEqualTo("UNDECIDED")
            assertThat(waiting.stopCode).isEqualTo("not_started")
            assertThat(waiting.completedAt).isNotNull()
            // 화면의 "실행 안 함" 줄이 이 STATUS 로 그려진다.
            val status = qaLogRepository.findPage(waitingId, null, 50).toList()
                .single { it.type == "STATUS" && objectMapper.readTree(it.payload.asString()).path("status").asText() == "UNDECIDED" }
            val stop = objectMapper.readTree(status.payload.asString()).path("stop")
            assertThat(stop.path("code").asText()).isEqualTo("not_started")
            assertThat(stop.path("cause").asText()).isEqualTo("llm_credit_exhausted")
        }

        val closed = qaRunRepository.findById(run.runId)!!
        assertThat(closed.status).isEqualTo("UNDECIDED")
        assertThat(closed.stopCode).isEqualTo("llm_credit_exhausted")
        assertThat(closed.discardedAt).isNotNull()
        // 활성 런이 남지 않아 다음 런을 막지 않는다.
        assertThat(qaRunRepository.findActiveByGameInstanceId(run.instanceId)).isNull()

        // 런 화면은 버린 런도 읽는다. 원인과 버림이 실린다.
        val response = service.getRun(run.runId, run.ownerId)!!
        assertThat(response.status).isEqualTo("UNDECIDED")
        assertThat(response.discarded).isTrue()
        assertThat(response.stop!!.code).isEqualTo("llm_credit_exhausted")
        assertThat(response.tries.map { it.stop!!.code })
            .containsExactly("llm_credit_exhausted", "not_started", "not_started")

        // 히스토리 · 알림함에서는 빠진다. 데이터는 지우지 않는다.
        assertThat(qaRunRepository.findByProject(run.projectId, null, 50).toList()).isEmpty()
        assertThat(launchReads.findRuns(run.projectId, null, 50)).isEmpty()
        assertThat(launchReads.findNotices(run.ownerId, 50)).isEmpty()
        assertThat(launchReads.countUnreadNotices(run.ownerId)).isZero()
        assertThat(qaTryRepository.findByQaRunId(run.runId).toList()).hasSize(3)
    }

    @Test
    fun `통과한 시나리오가 있으면 뒤에서 오류가 나도 런은 히스토리에 남는다`(): Unit = runBlocking {
        val run = runningRun(scenarios = 3)

        router.handle(passedFrame(run.tryIds[0]))
        router.handle(logFrame(run.tryIds[1], "scenario 2 turn"))
        router.handle(errorFrame(run.tryIds[1]))
        router.handle(undecidedFrame(run.tryIds[1]))

        assertThat(qaTryRepository.findById(run.tryIds[0])!!.status).isEqualTo("COMPLETED")
        assertThat(qaTryRepository.findById(run.tryIds[1])!!.stopCode).isEqualTo("llm_credit_exhausted")
        assertThat(qaTryRepository.findById(run.tryIds[2])!!.stopCode).isEqualTo("not_started")
        val closed = qaRunRepository.findById(run.runId)!!
        assertThat(closed.status).isEqualTo("UNDECIDED")
        assertThat(closed.discardedAt).isNull()
        assertThat(qaRunRepository.findByProject(run.projectId, null, 50).toList().map { it.id })
            .containsExactly(run.runId)
        assertThat(launchReads.findNotices(run.ownerId, 50)).hasSize(1)

        // 통계는 통과한 시도 하나만 센다. 미판정 둘은 runs · 분모에 들어가지 않는다.
        val now = Instant.now()
        val total = stats.aggregateByRunConfig(
            run.projectId, run.ownerId, false, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)), 50
        ).total!!
        assertThat(total.runs).isEqualTo(1)
        assertThat(total.failed).isZero()
    }

    @Test
    fun `오류 전에 스텝 판정이 하나라도 있으면 런을 버리지 않는다`(): Unit = runBlocking {
        val run = runningRun(scenarios = 2)

        router.handle(stepVerdictFrame(run.tryIds[0]))
        router.handle(errorFrame(run.tryIds[0]))
        router.handle(undecidedFrame(run.tryIds[0]))

        val closed = qaRunRepository.findById(run.runId)!!
        assertThat(closed.status).isEqualTo("UNDECIDED")
        assertThat(closed.discardedAt).isNull()
    }

    @Test
    fun `사용자가 종료한 런은 미판정이 아니라 취소로 남는다`(): Unit = runBlocking {
        val run = runningRun(scenarios = 2)
        val now = Instant.now()
        assertThat(qaTryRepository.transition(run.tryIds[0], "RUNNING", "COMPLETED", now, now)).isEqualTo(1)

        service.cancelRun(run.runId, run.ownerId)

        val cancelled = qaRunRepository.findById(run.runId)!!
        assertThat(cancelled.status).isEqualTo("CANCELLED")
        assertThat(cancelled.stopCode).isNull()
        assertThat(cancelled.discardedAt).isNull()
    }

    // ----------------------------------------------------------------- helpers

    private data class RunningRun(
        val runId: Long,
        val tryIds: List<Long>,
        val ownerId: Long,
        val projectId: Long,
        val instanceId: Long
    )

    /** qa_run RUNNING + 첫 시나리오 RUNNING + 나머지 PENDING 인 런. */
    private suspend fun runningRun(scenarios: Int): RunningRun {
        val owner = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "1371", login = "undecided",
                displayName = "undecided", avatarUrl = null, email = "undecided@example.com"
            )
        )
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(ProjectEntity(name = "undecided-p", genre = "ACTION", createdAt = now, updatedAt = now))
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = ownerId, role = ProjectRole.OWNER.name, createdAt = now)
        )
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id, name = "inst", platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now
            )
        )
        val testRun = testRunRepository.save(TestRunEntity(projectId = project.id, name = "런"))
        val scenarioIds = (1..scenarios).map { testScenarioRepository.save(TestScenarioEntity(projectId = project.id)).id!! }
        val started = persistence.createRunStarting(testRun.id!!, instance.id!!, ownerId, scenarioIds, QaRunGates())
        val tryIds = started.tries.map { it.id!! }
        persistence.attachRunAndMarkRunning(
            started.qaRun, tryIds.first(), "session-undecided",
            objectMapper.readTree("""{"model":"anthropic/claude-sonnet-5","reasoning":{"effort":"high"}}""")
        )
        return RunningRun(started.qaRun.id!!, tryIds, ownerId, project.id, instance.id)
    }

    private fun frame(qaTryId: Long, type: String, payload: String) =
        QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = type,
            qaTryId = qaTryId.toString(),
            timestamp = Instant.now(),
            payload = objectMapper.readTree(payload)
        )

    private fun errorFrame(qaTryId: Long) = frame(
        qaTryId, "ERROR",
        """{"message":"The model API token balance is exhausted, so the run stopped.","code":"llm_credit_exhausted","retryable":false,
           "detail":{"provider":"anthropic","provider_message":"Your credit balance is too low to access the Anthropic API."}}"""
    )

    private fun undecidedFrame(qaTryId: Long) = frame(
        qaTryId, "STATUS",
        """{"status":"FAILED","result":"UNDECIDED","message":"The model API token balance is exhausted, so the run stopped.",
           "summary":{"steps":{"total":3,"passed":0},"cases":{"total":2,"passed":0}},
           "stop":{"code":"llm_credit_exhausted","provider":"anthropic","message":"The model API token balance is exhausted, so the run stopped."}}"""
    )

    private fun passedFrame(qaTryId: Long) = frame(
        qaTryId, "STATUS",
        """{"status":"COMPLETED","result":"PASSED","message":"scenario done","summary":{"steps":{"total":1,"passed":1}}}"""
    )

    private fun stepVerdictFrame(qaTryId: Long) = frame(
        qaTryId, "STATUS", """{"status":"COMPLETED","step":1,"message":"step 1 passed"}"""
    )

    private fun logFrame(qaTryId: Long, message: String) = frame(qaTryId, "LOG", """{"message":"$message"}""")
}
