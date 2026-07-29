package kr.artel.orchestration.testcase

import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testrun.dto.TestRunCreateRequest
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioCaseRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant

/**
 * 3계층(TestCase → TestScenario → TestRun) 구조 통합 테스트. 서비스 레이어로 검증한다(HTTP/인증 우회).
 *
 * 검증: 케이스 CRUD, 프로젝트 참여 인가(비참여자 빈 결과), 시나리오↔케이스 junction의 순서형 조합·
 * 정방향/역방향 조회, 런↔시나리오 조합. app_user/project는 FK가 있어 실제 행을 시딩한다(자동 id라 격리됨).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TestCaseHierarchyIntegrationTest {

    @Autowired private lateinit var testCaseService: TestCaseService
    @Autowired private lateinit var compositionService: ScenarioCompositionService
    @Autowired private lateinit var testRunService: TestRunService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var testScenarioCaseRepository: TestScenarioCaseRepository

    private val timeout = Duration.ofSeconds(5)

    private fun newUser(): Long {
        val now = Instant.now()
        return appUserRepository.save(AppUserEntity(displayName = "tc-user", createdAt = now, updatedAt = now))
            .block(timeout)!!.id!!
    }

    /** 새 프로젝트 + 그 프로젝트에 참여하는 사용자 하나를 만든다(실 행, 자동 id). */
    private fun newProjectWithMember(): Pair<Long, Long> {
        val now = Instant.now()
        val userId = newUser()
        val projectId = projectRepository.save(
            ProjectEntity(name = "tc-project", genre = "ACTION", createdAt = now, updatedAt = now)
        ).block(timeout)!!.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "MEMBER", createdAt = now)
        ).block(timeout)
        return projectId to userId
    }

    private fun newScenario(projectId: Long): Long =
        testScenarioRepository.save(TestScenarioEntity(projectId = projectId, payload = Json.of("{}")))
            .block(timeout)!!.id!!

    @Test
    fun `케이스 CRUD와 시나리오·런 조합 전체 흐름`() {
        val (projectId, userId) = newProjectWithMember()

        val c1 = testCaseService.create(
            projectId, userId, TestCaseCreateRequest(category = "CONTROL", title = "상점 입장", expected = "상점 화면 진입")
        ).block(timeout)!!
        val c2 = testCaseService.create(
            projectId, userId,
            TestCaseCreateRequest(category = "RULE", title = "검 구매", precondition = "골드 10 이상", expected = "골드 차감 + 검 획득")
        ).block(timeout)!!

        val list = testCaseService.list(projectId, userId, null, null).block(timeout)!!
        assertThat(list.items).hasSize(2)
        assertThat(list.items.map { it.verificationStatus }).containsOnly("DRAFT")

        val verified = testCaseService.update(
            c1.id.toLong(), userId, TestCaseUpdateRequest(verificationStatus = "verified")
        ).block(timeout)!!
        assertThat(verified.verificationStatus).isEqualTo("VERIFIED")

        // 시나리오 조합(참조 순서 = position). c2 먼저, c1 나중.
        val scenarioId = newScenario(projectId)
        val composed = compositionService.setCases(scenarioId, userId, listOf(c2.id.toLong(), c1.id.toLong())).block(timeout)!!
        assertThat(composed.items.map { it.position }).containsExactly(0, 1)
        assertThat(composed.items.map { it.case.title }).containsExactly("검 구매", "상점 입장")

        val fetched = compositionService.getCases(scenarioId, userId).block(timeout)!!
        assertThat(fetched.items.map { it.case.id }).containsExactly(c2.id, c1.id)

        // 역방향: 케이스 c1을 쓰는 시나리오
        val usedIn = testScenarioCaseRepository.findByTestCaseId(c1.id.toLong()).collectList().block(timeout)!!
        assertThat(usedIn.map { it.testScenarioId }).contains(scenarioId)

        // 런 ↔ 시나리오 조합
        val run = testRunService.create(projectId, userId, TestRunCreateRequest(name = "회귀 스위트")).block(timeout)!!
        val runScenarios = testRunService.setScenarios(run.id.toLong(), userId, listOf(scenarioId)).block(timeout)!!
        assertThat(runScenarios.items.map { it.testScenarioId }).containsExactly(scenarioId.toString())
    }

    @Test
    fun `재조합은 이전 조합을 대체한다`() {
        val (projectId, userId) = newProjectWithMember()
        val c1 = testCaseService.create(projectId, userId, TestCaseCreateRequest(category = "MISC", title = "A", expected = "a")).block(timeout)!!
        val c2 = testCaseService.create(projectId, userId, TestCaseCreateRequest(category = "MISC", title = "B", expected = "b")).block(timeout)!!
        val scenarioId = newScenario(projectId)

        compositionService.setCases(scenarioId, userId, listOf(c1.id.toLong())).block(timeout)
        val replaced = compositionService.setCases(scenarioId, userId, listOf(c2.id.toLong())).block(timeout)!!
        assertThat(replaced.items).hasSize(1)
        assertThat(replaced.items.single().case.id).isEqualTo(c2.id)
    }

    @Test
    fun `비참여자는 빈 결과이고 생성할 수 없다`() {
        val (projectId, _) = newProjectWithMember()
        val stranger = newUser()

        assertThat(testCaseService.list(projectId, stranger, null, null).block(timeout)!!.items).isEmpty()
        val created = testCaseService.create(
            projectId, stranger, TestCaseCreateRequest(category = "X", title = "Y", expected = "Z")
        ).block(timeout)
        assertThat(created).isNull()
    }
}
