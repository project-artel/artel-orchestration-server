package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
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
import kr.artel.orchestration.qa.service.QaTryPersistenceService
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
import java.time.Instant
import java.util.UUID

/**
 * 종단 STATUS가 실어 온 2단 요약이 qa_try 컬럼으로 승격되는지(ARTEL-299).
 *
 * 승격은 **집계용 사본**이라 여기서 검증하는 것은 값이 맞는지보다 **어떤 상태를 어떤 값으로
 * 표현하는지**다. NULL은 "판정을 모른다"이고 0은 "측정된 0"이며, 둘을 뭉개면 소켓 사망으로 끝난
 * 런이 0점짜리 런과 같아 보인다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaVerdictPromotionIntegrationTest {

    @Autowired private lateinit var router: QaAgentInboundRouter
    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
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
    fun `종단 STATUS의 2단 요약이 네 컬럼으로 승격된다`(): Unit = runBlocking {
        val run = runningRun(scenarioCount = 1)

        router.handle(
            terminalFrame(
                run.tryIds[0],
                summary = summaryJson(
                    stepsTotal = 5L, stepsPassed = 4L,
                    casesTotal = 2L, casesPassed = 1L
                )
            )
        )

        val promoted = qaTryRepository.findById(run.tryIds[0])!!
        assertThat(promoted.status).isEqualTo("COMPLETED")
        assertThat(promoted.stepsTotal).isEqualTo(5)
        assertThat(promoted.stepsPassed).isEqualTo(4)
        assertThat(promoted.casesTotal).isEqualTo(2)
        assertThat(promoted.casesPassed).isEqualTo(1)
    }

    @Test
    fun `요약 없이 끝난 런은 NULL로 남는다`(): Unit = runBlocking {
        val run = runningRun(scenarioCount = 1)

        // 소켓 사망·취소·state 없이 끝나는 경로가 여기다(_send_terminal이 state=None으로 부른다).
        router.handle(terminalFrame(run.tryIds[0], summary = null))

        val ended = qaTryRepository.findById(run.tryIds[0])!!
        assertThat(ended.status).isEqualTo("COMPLETED")
        // 0으로 채우면 잘 죽는 모델이 전부 0점으로 보이고 그 오류는 조용히 지나간다.
        assertThat(ended.stepsTotal).isNull()
        assertThat(ended.stepsPassed).isNull()
        assertThat(ended.casesTotal).isNull()
        assertThat(ended.casesPassed).isNull()
    }

    @Test
    fun `스텝 판정 STATUS는 런을 끝내지도 승격하지도 않는다`(): Unit = runBlocking {
        val run = runningRun(scenarioCount = 1)

        // 스텝 판정 프레임은 COMPLETED/FAILED를 **그 스텝의 판정으로** 재사용하고 result=null이다.
        // status 낱말만 보고 판단하면 첫 스텝에서 런이 끝난다.
        router.handle(
            statusFrame(
                run.tryIds[0],
                """{"status":"COMPLETED","result":null,"step":1,"case_id":7,"message":"step 1 passed",
                    "summary":${summaryJson(9L, 9L, 9L, 9L)}}"""
            )
        )

        val stillRunning = qaTryRepository.findById(run.tryIds[0])!!
        assertThat(stillRunning.status).isEqualTo("RUNNING")
        // summary가 실려 있어도 종단 프레임이 아니면 승격하지 않는다.
        assertThat(stillRunning.stepsTotal).isNull()
        assertThat(stillRunning.casesTotal).isNull()
        // 프레임 자체는 타임라인에 그대로 남는다.
        val logs = qaLogRepository.findPage(run.tryIds[0], null, 50).toList()
        assertThat(logs.any { it.type == "STATUS" && it.message == "step 1 passed" }).isTrue()
    }

    @Test
    fun `case_id 없는 시나리오의 cases_total은 NULL이 아니라 0이다`(): Unit = runBlocking {
        val run = runningRun(scenarioCount = 1)

        // 저작 Step 모델에서 case_id는 nullable이라 케이스가 하나도 없는 시나리오가 있다.
        // Agent의 case_units()는 빈 목록을 내고 cases.total은 0으로 실려 온다 — **측정된 0**이다.
        router.handle(
            terminalFrame(
                run.tryIds[0],
                summary = summaryJson(stepsTotal = 3L, stepsPassed = 3L, casesTotal = 0L, casesPassed = 0L)
            )
        )

        val promoted = qaTryRepository.findById(run.tryIds[0])!!
        assertThat(promoted.stepsTotal).isEqualTo(3)
        // NULL로 뭉개면 "요약을 못 받았다"와 "케이스 없이 저작된 시나리오"가 같은 값이 되고,
        // 커버리지 집계가 후자를 미측정으로 세어 커버리지를 실제보다 낮게 보고한다.
        assertThat(promoted.casesTotal).isEqualTo(0)
        assertThat(promoted.casesPassed).isEqualTo(0)
    }

    @Test
    fun `승격이 실패해도 런은 정상 종료한다`(): Unit = runBlocking {
        val run = runningRun(scenarioCount = 1)

        // INT에 안 들어가는 수. 코틀린에서 좁히지 않고 그대로 넘기므로 DB가 거절한다 —
        // asInt()로 접었다면 조용히 **다른 수**가 저장됐을 것이고, 판정 지표에서 그것은
        // 못 읽은 것보다 나쁘다.
        router.handle(
            terminalFrame(
                run.tryIds[0],
                summary = summaryJson(stepsTotal = 99_999_999_999L, stepsPassed = 1L, casesTotal = 1L, casesPassed = 1L)
            )
        )

        val ended = qaTryRepository.findById(run.tryIds[0])!!
        // 승격 실패가 예외로 새어 나가면 WS가 닫히고 onDisconnect가 이 런을 FAILED로 뒤집는다.
        assertThat(ended.status).isEqualTo("COMPLETED")
        assertThat(ended.stepsTotal).isNull()
        assertThat(ended.stepsPassed).isNull()
        // 조용히 넘어가지도 않는다 — 타임라인에 흔적이 남는다.
        val logs = qaLogRepository.findPage(run.tryIds[0], null, 50).toList()
        assertThat(
            logs.any { it.type == "ERROR" && it.message?.startsWith("STATUS verdict promotion failed") == true }
        ).isTrue()
    }

    @Test
    fun `한 세션의 시나리오마다 자기 판정을 갖는다`(): Unit = runBlocking {
        val run = runningRun(scenarioCount = 2)

        router.handle(
            terminalFrame(run.tryIds[0], summary = summaryJson(4L, 4L, 2L, 2L))
        )
        // 두 번째 시나리오의 첫 프레임이 PENDING try를 활성한다(ARTEL-259 시나리오 전환).
        router.handle(logFrame(run.tryIds[1], "scenario 2 turn"))
        router.handle(
            terminalFrame(run.tryIds[1], result = "FAILED", summary = summaryJson(6L, 2L, 3L, 1L))
        )

        val first = qaTryRepository.findById(run.tryIds[0])!!
        val second = qaTryRepository.findById(run.tryIds[1])!!
        // 승격 경계는 qa_try이지 WS 세션이 아니다 — 세션 하나가 두 시나리오를 순차 실행했다.
        assertThat(first.stepsTotal to first.stepsPassed).isEqualTo(4 to 4)
        assertThat(second.stepsTotal to second.stepsPassed).isEqualTo(6 to 2)
        assertThat(first.casesPassed).isEqualTo(2)
        assertThat(second.casesPassed).isEqualTo(1)
        assertThat(qaRunRepository.findById(run.runId)!!.status).isEqualTo("FAILED")
    }

    // ----------------------------------------------------------------- helpers

    private fun summaryJson(
        stepsTotal: Long,
        stepsPassed: Long,
        casesTotal: Long,
        casesPassed: Long
    ) = """
        {"steps":{"total":$stepsTotal,"passed":$stepsPassed,"failed":${stepsTotal - stepsPassed},"items":[]},
         "cases":{"total":$casesTotal,"passed":$casesPassed,"failed":${casesTotal - casesPassed},"items":[]}}
    """.trimIndent()

    private fun terminalFrame(qaTryId: Long, summary: String?, result: String = "PASSED") =
        statusFrame(
            qaTryId,
            """
            {"status":"${if (result == "PASSED") "COMPLETED" else "FAILED"}","result":"$result",
             "message":"scenario done"${if (summary == null) "" else ",\"summary\":$summary"}}
            """.trimIndent()
        )

    private fun statusFrame(qaTryId: Long, payload: String) =
        QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = "STATUS",
            qaTryId = qaTryId.toString(),
            timestamp = Instant.now(),
            payload = objectMapper.readTree(payload)
        )

    private fun logFrame(qaTryId: Long, message: String) =
        QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = "LOG",
            qaTryId = qaTryId.toString(),
            timestamp = Instant.now(),
            payload = objectMapper.readTree("""{"message":"$message"}""")
        )

    private data class RunningRun(val runId: Long, val tryIds: List<Long>)

    /** qa_run RUNNING + 첫 시나리오 활성 + 나머지 PENDING인 런(ARTEL-259의 순차 실행 형태). */
    private suspend fun runningRun(scenarioCount: Int): RunningRun {
        val owner = signIn()
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "verdict-p", genre = "ACTION", createdAt = now, updatedAt = now)
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
        val testRun = testRunRepository.save(TestRunEntity(projectId = project.id!!, name = "런"))
        val scenarioIds = (1..scenarioCount).map {
            testScenarioRepository.save(
                TestScenarioEntity(projectId = project.id!!)
            ).id!!
        }

        val started = persistence.createRunStarting(requireNotNull(testRun.id), instance.id!!, ownerId, scenarioIds)
        val tryIds = started.tries.map { requireNotNull(it.id) }
        persistence.attachRunAndMarkRunning(
            started.qaRun,
            tryIds.first(),
            "session-verdict-1",
            objectMapper.readTree("""{"model":"anthropic/claude-sonnet-5","prompt_version":"v3"}""")
        )
        return RunningRun(requireNotNull(started.qaRun.id), tryIds)
    }

    private suspend fun signIn(): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "299",
                login = "verdict",
                displayName = "verdict",
                avatarUrl = null,
                email = "verdict@example.com"
            )
        )
}
