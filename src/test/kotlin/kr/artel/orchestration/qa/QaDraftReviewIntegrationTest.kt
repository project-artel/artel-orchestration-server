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
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
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

    private var userId = 0L
    private var instanceId = 0L
    private var testRunId = 0L
    private var readyScenarioId = 0L
    private var draftScenarioId = 0L

    @BeforeEach
    fun seed(): Unit = runBlocking {
        wipe()
        userId = oauth.upsert(OAuthIdentity("github", "review-qa", "review-qa", "review-qa", null, null)).userId.toLong()
        val now = Instant.now()
        val projectId = projects.save(ProjectEntity(name = "draft-review", genre = "ACTION", createdAt = now, updatedAt = now)).id!!
        members.save(ProjectMemberEntity(projectId = projectId, appUserId = userId, role = ProjectRole.OWNER.name, createdAt = now))
        instanceId = instances.save(GameInstanceEntity(projectId = projectId, name = "review", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)).id!!
        val draftCaseId = cases.save(TestCaseEntity(projectId = projectId, scene = "Title", step = "Start", expectedValue = "Game starts", reviewStatus = "DRAFT")).id!!
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
    fun `런의 두 번째 시나리오에 초안이 있어도 아무 실행 행을 만들지 않는다`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.createRun(testRunId, instanceId, userId) } }
            .isInstanceOf(BadRequestException::class.java)
        assertThat(tries.count()).isZero()
        assertThat(runs.count()).isZero()
    }

    @Test
    fun `force 요청도 초안을 발견하면 기존 실행을 종료하지 않는다`(): Unit = runBlocking {
        val active = persistence.createRunStarting(testRunId, instanceId, userId, listOf(readyScenarioId))
        assertThatThrownBy { runBlocking { service.createRun(testRunId, instanceId, userId, force = true) } }
            .isInstanceOf(BadRequestException::class.java)
        assertThat(runs.count()).isEqualTo(1)
        assertThat(tries.count()).isEqualTo(1)
        assertThat(runs.findById(active.qaRun.id!!)!!.status).isEqualTo("STARTING")
    }
}
