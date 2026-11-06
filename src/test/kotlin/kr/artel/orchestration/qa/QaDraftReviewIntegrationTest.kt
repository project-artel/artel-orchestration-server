package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.BadRequestException
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
import kr.artel.orchestration.qa.service.QaTryPersistenceService
import kr.artel.orchestration.qa.service.QaTryService
import kr.artel.orchestration.qa.service.SdkDisconnectedException
import kr.artel.orchestration.qa.service.TestRunReviewPendingException
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testcase.contract.CaseBatchItem
import kr.artel.orchestration.testcase.contract.CaseBatchRequest
import kr.artel.orchestration.testcase.contract.CaseBatchService
import kr.artel.orchestration.testcase.dto.ReviewDecision
import kr.artel.orchestration.testcase.dto.ReviewDecisionRequest
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.CaseReviewService
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaDraftReviewIntegrationTest {
    @Autowired private lateinit var service: QaTryService
    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var runs: QaRunRepository
    @Autowired private lateinit var tries: QaTryRepository
    @Autowired private lateinit var logs: QaLogRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var runScenarios: TestRunScenarioRepository
    @Autowired private lateinit var testRunService: TestRunService
    @Autowired private lateinit var scenarios: TestScenarioRepository
    @Autowired private lateinit var cases: TestCaseRepository
    @Autowired private lateinit var instances: GameInstanceRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var users: AppUserRepository
    @Autowired private lateinit var identities: OAuthIdentityRepository
    @Autowired private lateinit var oauth: OAuthUserService
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var batches: CaseBatchService
    @Autowired private lateinit var caseReviews: CaseReviewService

    private var userId = 0L
    private var instanceId = 0L
    private var testRunId = 0L
    private var readyScenarioId = 0L
    private var draftScenarioId = 0L
    private var draftCaseId = 0L

    @BeforeEach
    fun seed(): Unit = runBlocking {
        wipe()
        userId = oauth.upsert(OAuthIdentity("github", "review-qa", "review-qa", "review-qa", null, null)).userId.toLong()
        val now = Instant.now()
        val projectId = projects.save(ProjectEntity(name = "draft-review", genre = "ACTION", createdAt = now, updatedAt = now)).id!!
        members.save(ProjectMemberEntity(projectId = projectId, appUserId = userId, role = ProjectRole.OWNER.name, createdAt = now))
        instanceId = instances.save(GameInstanceEntity(projectId = projectId, name = "review", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)).id!!
        draftCaseId = cases.save(TestCaseEntity(projectId = projectId, scene = "Title", step = "Start", expectedValue = "Game starts", reviewStatus = "DRAFT")).id!!
        readyScenarioId = scenarios.save(TestScenarioEntity(projectId = projectId)).id!!
        // 저장된 옛 시나리오가 나중에 초안으로 바뀐 TC를 참조하는 경우를 재현한다.
        draftScenarioId = scenarios.save(TestScenarioEntity(projectId = projectId).withDraft(
            ScenarioDraft(steps = listOf(ScenarioStep(action = "Start", caseId = draftCaseId))), mapper
        )).id!!
        testRunId = testRuns.save(TestRunEntity(projectId = projectId, name = "review")).id!!
        testRunService.setScenarios(testRunId, userId, listOf(readyScenarioId, draftScenarioId))
    }

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        logs.deleteAll()
        tries.deleteAll()
        runs.deleteAll()
        runScenarios.deleteAll()
        testRuns.deleteAll()
        scenarios.deleteAll()
        cases.deleteAll()
        instances.deleteAll()
        members.deleteAll()
        projects.deleteAll()
        identities.deleteAll()
        users.deleteAll()
    }

    @Test
    fun `단일 QA는 초안 TC를 참조하면 실행 행을 만들기 전에 거절한다`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.create(draftScenarioId, instanceId, userId) } }
            .isInstanceOf(BadRequestException::class.java)
        assertThat(tries.count()).isZero()
        assertThat(runs.count()).isZero()
    }

    @Test
    fun `런의 두 번째 시나리오가 초안 TC를 쓰면 건수를 알리고 아무 실행 행을 만들지 않는다`(): Unit = runBlocking {
        val refused = refusedRun()
        assertThat(refused.code).isEqualTo("test_run_review_pending")
        assertThat(refused.status.value()).isEqualTo(409)
        assertThat(refused.message).isEqualTo("승인 대기 중인 Test Case 1건이 있어 QA 를 시작할 수 없습니다. 승인한 뒤 다시 시작해 주세요.")
        assertThat(tries.count()).isZero()
        assertThat(runs.count()).isZero()
    }

    @Test
    fun `일부 시나리오만 승인 대기인 런은 나머지만 돌리지 않고 거절한다`(): Unit = runBlocking {
        cases.save(cases.findById(draftCaseId)!!.copy(reviewStatus = "READY"))
        scenarios.save(scenarios.findById(readyScenarioId)!!.copy(reviewStatus = "DRAFT", reviewRequestedBy = userId))

        val refused = refusedRun()
        assertThat(refused.message).isEqualTo("승인 대기 중인 시나리오 1건이 있어 QA 를 시작할 수 없습니다. 승인한 뒤 다시 시작해 주세요.")
        assertThat(tries.count()).isZero()
        assertThat(runs.count()).isZero()
    }

    @Test
    fun `모든 시나리오가 승인 대기인 런도 빈 런이 아니라 승인 대기로 거절한다`(): Unit = runBlocking {
        scenarios.save(scenarios.findById(readyScenarioId)!!.copy(reviewStatus = "DRAFT", reviewRequestedBy = userId))
        scenarios.save(scenarios.findById(draftScenarioId)!!.copy(reviewStatus = "DRAFT", reviewRequestedBy = userId))

        val refused = refusedRun()
        assertThat(refused.code).isEqualTo("test_run_review_pending")
        assertThat(refused.message).contains("시나리오 2건과 Test Case 1건이")
    }

    @Test
    fun `시나리오가 쓰는 READY TC를 MCP로 고치면 재승인 전까지 런을 시작하지 못한다`(): Unit = runBlocking {
        val case = cases.save(cases.findById(draftCaseId)!!.copy(reviewStatus = "READY"))
        assertThatThrownBy { runBlocking { service.createRun(testRunId, instanceId, userId) } }
            .describedAs("승인 대기가 없으면 다음 검사까지 간다").isInstanceOf(SdkDisconnectedException::class.java)

        val saved = batches.save(case.projectId, userId, CaseBatchRequest(cases = listOf(
            CaseBatchItem(testCaseId = draftCaseId.toString(), scene = case.scene, step = "Press start", expectedValue = case.expectedValue),
        )))
        assertThat(saved.rejected).isEmpty()
        assertThat(refusedRun().message).contains("Test Case 1건이")
        assertThat(tries.count()).isZero()

        val item = caseReviews.queue(case.projectId, userId).single()
        caseReviews.decide(case.projectId, draftCaseId, userId, ReviewDecisionRequest(ReviewDecision.APPROVE, item.updatedAt))
        assertThatThrownBy { runBlocking { service.createRun(testRunId, instanceId, userId) } }
            .describedAs("승인하면 시나리오를 고치지 않아도 풀린다").isInstanceOf(SdkDisconnectedException::class.java)
    }

    @Test
    fun `force 요청도 초안을 발견하면 기존 실행을 종료하지 않는다`(): Unit = runBlocking {
        val active = persistence.createRunStarting(testRunId, instanceId, userId, listOf(readyScenarioId))
        assertThatThrownBy { runBlocking { service.createRun(testRunId, instanceId, userId, force = true) } }
            .isInstanceOf(TestRunReviewPendingException::class.java)
        assertThat(runs.count()).isEqualTo(1)
        assertThat(tries.count()).isEqualTo(1)
        assertThat(runs.findById(active.qaRun.id!!)!!.status).isEqualTo("STARTING")
    }

    private fun refusedRun(): TestRunReviewPendingException =
        assertThrows(TestRunReviewPendingException::class.java) {
            runBlocking { service.createRun(testRunId, instanceId, userId) }
        }
}
