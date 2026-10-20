package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.repository.QaTryScoreRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaTryPersistenceService
import kr.artel.orchestration.qa.service.QaVerdictService
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.testcase.service.TestCaseVerdictService
import org.assertj.core.api.Assertions.assertThatThrownBy
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
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
 * 판정이 어느 build 것인지 남긴다, `BROKEN` 은 건드리지 않는다, 재전송은 두 번 세지 않는다. 판정은 그 게임 빌드에
 * 든 케이스에만 적고, editor 실행은 같은 버전의 게임 빌드 판정이다(ARTEL-1014).
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
    @Autowired private lateinit var versions: TestCaseVersionRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var gameBuildRepository: GameBuildRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var qaVerdictService: QaVerdictService
    @Autowired private lateinit var caseVerdictService: TestCaseVerdictService

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
        versions.deleteAll()
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

        // 판정은 그 build 의 버전 몫에 적히고(ARTEL-1018), 정체는 그 몫을 비춘다.
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!.verificationStatus)
            .isEqualTo("VERIFIED")
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.failedCaseId, run.gameBuildId)!!.verificationStatus)
            .isEqualTo("FAILED")

        val verdicts = caseVerdictLogs(run.tryId)
        assertThat(verdicts.map { it.path("case_id").asLong() to it.path("verification_status").asText() })
            .containsExactly(run.passedCaseId to "VERIFIED", run.failedCaseId to "FAILED")
        assertThat(verdicts).allSatisfy { assertThat(it.path("game_build_id").asLong()).isEqualTo(run.gameBuildId) }
    }

    @Test
    fun `판정 근거가 TC 판정에 이어지고 런의 판정 목록으로 읽힌다`(): Unit = runBlocking {
        val run = runningRun()

        reportStep(run.tryId, step = 1, caseId = run.passedCaseId, verification = false, passed = true)
        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true) { payload ->
            payload.put("reason", "Stage 글자가 떴다").put("evidence", "verified")
            payload.putArray("checks").addObject()
                .put("expected", "Stage 글자").put("observed", "Stage : 0").put("met", "yes")
                .putArray("sources").addObject()
                .put("kind", "pulse").put("key", "MapScene::stage").put("value", "Stage : 0")
                .put("recorded", "Stage : 0").put("citation", "match")
        }
        // 근거 칸이 없는 판정의 이유는 바로 앞 판정 도구 호출의 `thought` 다.
        qaLogRepository.save(
            QaLogEntity(
                qaTryId = run.tryId, direction = "AGENT_TO_ORCHE", type = "TOOL", message = "report_step",
                payload = Json.of("""{"tool":"report_step","args":{"step":3,"passed":false,"thought":"적이 남아 있다"}}"""),
            )
        )
        reportStep(run.tryId, step = 3, caseId = run.failedCaseId, verification = true, passed = false)

        // TC 판정은 그 판정을 실은 STATUS 행을 가리킨다.
        val stepTwo = qaLogRepository.findStepVerdicts(run.tryId).toList()
            .single { objectMapper.readTree(it.payload.asString()).path("step").asInt() == 2 }
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!.verdictQaLogId)
            .isEqualTo(stepTwo.id)
        assertThat(caseVerdictLogs(run.tryId).first().path("verdict_log_id").asLong()).isEqualTo(stepTwo.id)
        val history = caseVerdictService.history(run.projectId, run.passedCaseId, run.ownerId).items.single()
        assertThat(listOf(history.qaRunId, history.qaTryId, history.verdictLogId))
            .containsExactly(run.qaRunId.toString(), run.tryId.toString(), stepTwo.id.toString())

        // 런의 판정 목록은 스텝 순서로 근거를 싣고, 근거 칸이 없는 판정은 `none` 이다.
        val verdicts = qaVerdictService.list(run.qaRunId, run.ownerId).tries.single().verdicts
        assertThat(verdicts.map { it.step }).containsExactly(1, 2, 3)
        val judged = verdicts[1]
        assertThat(judged.logId).isEqualTo(stepTwo.id.toString())
        assertThat(judged.stepTitle).isEqualTo("2")
        assertThat(judged.reason).isEqualTo("Stage 글자가 떴다")
        assertThat(judged.evidence).isEqualTo("verified")
        assertThat(judged.checks!!.path(0).path("sources").path(0).path("citation").asText()).isEqualTo("match")
        assertThat(verdicts[2].passed).isFalse()
        assertThat(verdicts[2].evidence).isEqualTo("none")
        assertThat(verdicts[2].reason).isEqualTo("적이 남아 있다")
        assertThat(verdicts[0].reason).isNull()

        // 보이지 않는 런은 없는 것이다.
        assertThatThrownBy { runBlocking { qaVerdictService.list(run.qaRunId, run.ownerId + 999) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `앞 버전 몫은 새 버전에서 난 판정이 와도 그대로다`(): Unit = runBlocking {
        val run = runningRun()
        val testCase = testCaseRepository.findById(run.passedCaseId)!!
        val project = testCase.projectId
        val now = Instant.now()
        val older = gameBuildRepository.save(
            GameBuildEntity(projectId = project, version = "0.9.0", createdAt = now.minusSeconds(3600), updatedAt = now)
        )
        versions.save(TestCaseVersionEntity.snapshotOf(testCase, older.id!!).copy(verificationStatus = "VERIFIED"))

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = false)

        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, older.id!!)!!.verificationStatus)
            .isEqualTo("VERIFIED")
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!.verificationStatus)
            .isEqualTo("FAILED")
        assertThat(testCaseRepository.findById(run.passedCaseId)!!.verificationStatus).isEqualTo("FAILED")
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
    fun `그 게임 버전에서 BROKEN 인 TC 는 판정이 와도 그대로다`(): Unit = runBlocking {
        val run = runningRun()
        versions.save(versionOf(run.passedCaseId).copy(verificationStatus = "BROKEN"))
        versions.syncIdentities(listOf(run.passedCaseId))

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        assertThat(versionOf(run.passedCaseId).verificationStatus).isEqualTo("BROKEN")
        assertThat(testCaseRepository.findById(run.passedCaseId)!!.verificationStatus).isEqualTo("BROKEN")
        assertThat(caseVerdictLogs(run.tryId)).isEmpty()
    }

    /** 깨졌는지는 판정할 게임 버전의 몫으로 본다(ARTEL-1069). 다른 버전에서 깨졌어도 이 버전 몫에는 적는다. */
    @Test
    fun `다른 버전에서만 BROKEN 인 TC 는 이 게임 버전에 판정을 적는다`(): Unit = runBlocking {
        val run = runningRun()
        testCaseRepository.save(testCaseRepository.findById(run.passedCaseId)!!.copy(verificationStatus = "BROKEN"))

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        assertThat(versionOf(run.passedCaseId).verificationStatus).isEqualTo("VERIFIED")
        assertThat(caseVerdictLogs(run.tryId)).hasSize(1)
    }

    private suspend fun versionOf(testCaseId: Long) = versions.findAll().toList().single { it.testCaseId == testCaseId }

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

    @Test
    fun `이 게임 빌드에 없는 TC 는 판정이 와도 그대로다`(): Unit = runBlocking {
        val run = runningRun()
        versions.delete(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!)

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)).isNull()
        assertThat(testCaseRepository.findById(run.passedCaseId)!!.verificationStatus).isEqualTo("DRAFT")
        assertThat(caseVerdictLogs(run.tryId)).isEmpty()
    }

    /** editor 는 게임 버전이 아니다(ARTEL-1014). editor 에서 돌린 판정은 editor 가 보고한 버전의 게임 빌드 몫이다. */
    @Test
    fun `editor 실행의 판정은 같은 버전의 게임 빌드에 적힌다`(): Unit = runBlocking {
        val run = runningRun(onEditor = true)

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        assertThat(run.runBuildId).isNotEqualTo(run.gameBuildId)
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!.verificationStatus)
            .isEqualTo("VERIFIED")
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.runBuildId)).isNull()
        assertThat(testCaseRepository.findById(run.passedCaseId)!!.lastVerifiedBuildId).isEqualTo(run.gameBuildId)
        assertThat(caseVerdictLogs(run.tryId).single().path("game_build_id").asLong()).isEqualTo(run.gameBuildId)
    }

    @Test
    fun `같은 버전의 게임 빌드가 없는 editor 실행은 판정을 적지 않는다`(): Unit = runBlocking {
        val run = runningRun(onEditor = true, editorVersion = "1.1.0")

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!.verificationStatus)
            .isEqualTo("DRAFT")
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.runBuildId)).isNull()
        assertThat(caseVerdictLogs(run.tryId)).isEmpty()
    }

    /** [gameBuildId] 는 케이스가 든 게임 빌드, [runBuildId] 는 qa_run 이 붙은 build 다. editor 실행이면 둘이 다르다. */
    private data class RunningRun(
        val tryId: Long,
        val gameBuildId: Long,
        val runBuildId: Long,
        val passedCaseId: Long,
        val failedCaseId: Long,
        val qaRunId: Long,
        val projectId: Long,
        val ownerId: Long,
    )

    /**
     * 게임 빌드 1.0.0 에 든 케이스 둘을 검증하는 시나리오를 돌리는 중인 run. [onEditor] 면 그 run 은 [editorVersion] 을
     * 보고한 editor 행에 붙는다.
     */
    private suspend fun runningRun(onEditor: Boolean = false, editorVersion: String = "1.0.0"): RunningRun {
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
        val runBuild = if (!onEditor) build else gameBuildRepository.save(
            GameBuildEntity(projectId = projectId, version = editorVersion, isEditor = true, createdAt = now, updatedAt = now)
        )
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId, name = "inst", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(),
                lastGameBuildId = runBuild.id, createdAt = now, updatedAt = now
            )
        )!!
        val passedCase = testCaseRepository.save(
            TestCaseEntity(projectId = projectId, scene = "S", step = "통과할 것", expectedValue = "x")
        )
        val failedCase = testCaseRepository.save(
            TestCaseEntity(projectId = projectId, scene = "S", step = "실패할 것", expectedValue = "y")
        )
        versions.save(TestCaseVersionEntity.snapshotOf(passedCase, build.id!!))
        versions.save(TestCaseVersionEntity.snapshotOf(failedCase, build.id!!))
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
            testRun.id!!, instance.id!!, ownerId, listOf(scenarioId), gameBuildId = runBuild.id
        )
        val tryId = requireNotNull(started.tries.first().id)
        persistence.attachRunAndMarkRunning(
            started.qaRun, tryId, "session-verdict-1",
            objectMapper.readTree("""{"model":"anthropic/claude-sonnet-5"}""")
        )
        return RunningRun(
            tryId, build.id!!, runBuild.id!!, passedCase.id!!, failedCase.id!!, started.qaRun.id!!, projectId, ownerId
        )
    }

    private suspend fun reportStep(
        qaTryId: Long,
        step: Int,
        caseId: Long?,
        verification: Boolean,
        passed: Boolean,
        messageId: String = UUID.randomUUID().toString(),
        evidence: (ObjectNode) -> Unit = {},
    ) {
        val payload = objectMapper.createObjectNode()
            .put("status", if (passed) "COMPLETED" else "FAILED")
            .put("step", step)
            .put("is_verification", verification)
            .put("message", "step $step")
        caseId?.let { payload.put("case_id", it) }
        evidence(payload)
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
