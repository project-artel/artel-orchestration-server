package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.repository.QaTryScoreRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaTryPersistenceService
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.withDraft
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
 * QA 검증 스텝의 판정이 TC 상태로 옮겨지는지(ARTEL-994).
 *
 * agent 는 스텝마다 `case_id` 와 `is_verification` 을 싣는다. 같은 `case_id` 구간의 마지막 스텝만 검증
 * 스텝이고, 그 스텝의 COMPLETED/FAILED 가 그 TC 의 판정이다. 여기서 지키는 것은 넷이다: 검증 스텝만 옮긴다,
 * 판정이 어느 build 것인지 남긴다, `BROKEN` 은 건드리지 않는다, 재전송은 두 번 세지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CaseVerdictFromQaIntegrationTest {

    @Autowired private lateinit var router: QaAgentInboundRouter
    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var scoreRepository: QaTryScoreRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var gameBuildRepository: GameBuildRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun cleanBefore(): Unit = runBlocking { wipe() }

    @AfterEach
    fun cleanAfter(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        scoreRepository.deleteAll()
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testRunRepository.deleteAll()
        scenarioRepository.deleteAll()
        testCaseRepository.deleteAll()
        gameBuildRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `검증 스텝의 통과와 실패가 TC 상태와 판정 build 로 남는다`(): Unit = runBlocking {
        val run = runningRun()

        reportStep(run.tryId, step = 1, caseId = run.passedCaseId, verification = false, passed = false)
        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)
        reportStep(run.tryId, step = 3, caseId = run.failedCaseId, verification = true, passed = false)

        val passed = testCaseRepository.findById(run.passedCaseId)!!
        assertThat(passed.verificationStatus).isEqualTo("VERIFIED")
        assertThat(passed.lastVerifiedBuildId).isEqualTo(run.gameBuildId)
        val failed = testCaseRepository.findById(run.failedCaseId)!!
        assertThat(failed.verificationStatus).isEqualTo("FAILED")
        assertThat(failed.lastVerifiedBuildId).isEqualTo(run.gameBuildId)

        val verdicts = caseVerdictLogs(run.tryId)
        assertThat(verdicts.map { it.path("case_id").asLong() to it.path("verification_status").asText() })
            .containsExactly(run.passedCaseId to "VERIFIED", run.failedCaseId to "FAILED")
        assertThat(verdicts).allSatisfy { assertThat(it.path("game_build_id").asLong()).isEqualTo(run.gameBuildId) }
    }

    @Test
    fun `검증 스텝이 아니거나 case_id 가 없는 프레임은 상태를 바꾸지 않는다`(): Unit = runBlocking {
        val run = runningRun()

        reportStep(run.tryId, step = 1, caseId = run.passedCaseId, verification = false, passed = true)
        reportStep(run.tryId, step = 2, caseId = null, verification = true, passed = true)

        assertThat(testCaseRepository.findById(run.passedCaseId)!!.verificationStatus).isEqualTo("DRAFT")
        assertThat(caseVerdictLogs(run.tryId)).isEmpty()
    }

    @Test
    fun `BROKEN 인 TC 는 판정이 와도 그대로다`(): Unit = runBlocking {
        val run = runningRun()
        testCaseRepository.save(testCaseRepository.findById(run.passedCaseId)!!.copy(verificationStatus = "BROKEN"))

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        assertThat(testCaseRepository.findById(run.passedCaseId)!!.verificationStatus).isEqualTo("BROKEN")
        assertThat(caseVerdictLogs(run.tryId)).isEmpty()
    }

    @Test
    fun `같은 프레임의 재전송은 판정 기록을 하나만 남긴다`(): Unit = runBlocking {
        val run = runningRun()
        val messageId = UUID.randomUUID().toString()

        repeat(2) {
            reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true, messageId)
        }

        assertThat(caseVerdictLogs(run.tryId)).hasSize(1)
    }

    @Test
    fun `다른 프로젝트의 TC id 가 와도 그 TC 를 바꾸지 않는다`(): Unit = runBlocking {
        val run = runningRun()
        val now = Instant.now()
        val otherProject = projectRepository.save(
            ProjectEntity(name = "other", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        val foreign = testCaseRepository.save(
            TestCaseEntity(projectId = otherProject.id!!, scene = "S", step = "남의 것", expectedValue = "x")
        )

        reportStep(run.tryId, step = 2, caseId = foreign.id!!, verification = true, passed = true)

        assertThat(testCaseRepository.findById(foreign.id!!)!!.verificationStatus).isEqualTo("DRAFT")
        assertThat(caseVerdictLogs(run.tryId)).isEmpty()
    }

    private data class RunningRun(val tryId: Long, val gameBuildId: Long, val passedCaseId: Long, val failedCaseId: Long)

    private suspend fun runningRun(): RunningRun {
        val owner = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "994", login = "verdict",
                displayName = "verdict", avatarUrl = null, email = "verdict@example.com"
            )
        )!!
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val projectId = projectRepository.save(
            ProjectEntity(name = "verdict-p", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = ownerId, role = ProjectRole.OWNER.name, createdAt = now)
        )
        val build = gameBuildRepository.save(
            GameBuildEntity(projectId = projectId, version = "1.0.0", createdAt = now, updatedAt = now)
        )
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId, name = "inst", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(),
                lastGameBuildId = build.id, createdAt = now, updatedAt = now
            )
        )!!
        val passedCase = testCaseRepository.save(
            TestCaseEntity(projectId = projectId, scene = "S", step = "통과할 것", expectedValue = "x")
        )
        val failedCase = testCaseRepository.save(
            TestCaseEntity(projectId = projectId, scene = "S", step = "실패할 것", expectedValue = "y")
        )
        val testRun = testRunRepository.save(TestRunEntity(projectId = projectId, name = "런"))
        val draft = ScenarioDraft(
            title = "판정",
            description = "",
            steps = listOf(
                ScenarioStep(action = "1", caseId = passedCase.id),
                ScenarioStep(action = "2", caseId = passedCase.id),
                ScenarioStep(action = "3", caseId = failedCase.id),
            )
        )
        val scenarioId = scenarioRepository.save(TestScenarioEntity(projectId = projectId).withDraft(draft, objectMapper)).id!!
        val started = persistence.createRunStarting(
            testRun.id!!, instance.id!!, ownerId, listOf(scenarioId), gameBuildId = build.id
        )
        val tryId = requireNotNull(started.tries.first().id)
        persistence.attachRunAndMarkRunning(
            started.qaRun, tryId, "session-verdict-1",
            objectMapper.readTree("""{"model":"anthropic/claude-sonnet-5"}""")
        )
        return RunningRun(tryId, build.id!!, passedCase.id!!, failedCase.id!!)
    }

    private suspend fun reportStep(
        qaTryId: Long,
        step: Int,
        caseId: Long?,
        verification: Boolean,
        passed: Boolean,
        messageId: String = UUID.randomUUID().toString(),
    ) {
        val payload = objectMapper.createObjectNode()
            .put("status", if (passed) "COMPLETED" else "FAILED")
            .put("step", step)
            .put("is_verification", verification)
            .put("message", "step $step")
        caseId?.let { payload.put("case_id", it) }
        router.handle(
            QaAgentEnvelope(
                messageId = messageId,
                type = "STATUS",
                qaTryId = qaTryId.toString(),
                timestamp = Instant.now(),
                payload = payload,
            )
        )
    }

    private suspend fun caseVerdictLogs(qaTryId: Long) =
        qaLogRepository.findAll().toList()
            .filter { it.qaTryId == qaTryId && it.type == "CASE_VERDICT" }
            .sortedBy { it.id }
            .map { objectMapper.readTree(it.payload.asString()) }
}
