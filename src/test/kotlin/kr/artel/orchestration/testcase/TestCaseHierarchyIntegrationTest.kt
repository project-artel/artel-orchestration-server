package kr.artel.orchestration.testcase

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
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
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 3계층(TestCase → TestScenario → TestRun) 구조 통합 테스트(코루틴). 서비스 레이어로 검증한다(HTTP/인증 우회).
 *
 * 재설계(2026-08-07): 시나리오 본문은 payload의 steps 리스트이고, 시나리오↔케이스 junction(조합)은 폐기됐다.
 * 여기서는 케이스 CRUD·프로젝트 참여 인가·런↔시나리오 조합을 검증한다(케이스↔시나리오 매핑은 이제 스텝의
 * caseId로 표현되며 실행 계약 테스트에서 다룬다). app_user/project는 FK가 있어 실제 행을 시딩한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TestCaseHierarchyIntegrationTest {

    @Autowired private lateinit var testCaseService: TestCaseService
    @Autowired private lateinit var testRunService: TestRunService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository

    private suspend fun newUser(): Long {
        val now = Instant.now()
        return appUserRepository.save(AppUserEntity(displayName = "tc-user", createdAt = now, updatedAt = now))
            .id!!
    }

    /** 새 프로젝트 + 그 프로젝트에 참여하는 사용자 하나를 만든다(실 행, 자동 id). */
    private suspend fun newProjectWithMember(): Pair<Long, Long> {
        val now = Instant.now()
        val userId = newUser()
        val projectId = projectRepository.save(
            ProjectEntity(name = "tc-project", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "MEMBER", createdAt = now)
        )
        return projectId to userId
    }

    private suspend fun newScenario(projectId: Long): Long =
        testScenarioRepository.save(TestScenarioEntity(projectId = projectId, payload = Json.of("{}")))
            .id!!

    @Test
    fun `케이스 CRUD와 런·시나리오 조합 흐름`(): Unit = runBlocking {
        val (projectId, userId) = newProjectWithMember()

        val c1 = testCaseService.create(
            projectId, userId, TestCaseCreateRequest(category = "CONTROL", title = "상점 입장", expected = "상점 화면 진입")
        )!!
        testCaseService.create(
            projectId, userId,
            TestCaseCreateRequest(category = "RULE", title = "검 구매", precondition = "골드 10 이상", expected = "골드 차감 + 검 획득")
        )!!

        val list = testCaseService.list(projectId, userId, null, null)
        assertThat(list.items).hasSize(2)
        assertThat(list.items.map { it.verificationStatus }).containsOnly("DRAFT")

        val verified = testCaseService.update(c1.id.toLong(), userId, TestCaseUpdateRequest(verificationStatus = "verified"))!!
        assertThat(verified.verificationStatus).isEqualTo("VERIFIED")

        // 런 ↔ 시나리오 조합(런 링크는 그대로 존속).
        val scenarioId = newScenario(projectId)
        val run = testRunService.create(projectId, userId, TestRunCreateRequest(name = "회귀 스위트"))!!
        val runScenarios = testRunService.setScenarios(run.id.toLong(), userId, listOf(scenarioId))!!
        assertThat(runScenarios.items.map { it.testScenarioId }).containsExactly(scenarioId.toString())
    }

    @Test
    fun `비참여자는 빈 결과이고 생성할 수 없다`(): Unit = runBlocking {
        val (projectId, _) = newProjectWithMember()
        val stranger = newUser()

        assertThat(testCaseService.list(projectId, stranger, null, null).items).isEmpty()
        val created = testCaseService.create(
            projectId, stranger, TestCaseCreateRequest(category = "X", title = "Y", expected = "Z")
        )
        assertThat(created).isNull()
    }
}
