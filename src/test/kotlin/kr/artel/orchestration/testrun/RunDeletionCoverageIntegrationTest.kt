package kr.artel.orchestration.testrun

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testrun.dto.TestRunCreateRequest
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 런을 지운 뒤 **커버리지가 따라오는가**(ARTEL-487).
 *
 * 런 삭제는 조합만 끊고 시나리오는 남기는데, 커버리지는 런과 무관하게 프로젝트의 모든 시나리오를
 * 센다. 그래서 런만 지우면 어디에도 담기지 않은 시나리오가 케이스를 계속 "담긴 것"으로 만들고,
 * 사용자가 보기에는 지웠는데 숫자가 그대로다. 지울 때 함께 지울지 묻고, 그 답이 실제로 숫자를
 * 움직이는지가 여기서 보는 것이다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RunDeletionCoverageIntegrationTest {

    @Autowired private lateinit var testCaseService: TestCaseService
    @Autowired private lateinit var runService: TestRunService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `런만 지우면 커버리지가 그대로고, 함께 지우면 풀린다`(): Unit = runBlocking {
        val (projectId, userId) = project()
        val caseId = case(projectId, userId)
        val scenarioId = scenario(projectId, caseId)
        val run = runService.create(projectId, userId, TestRunCreateRequest(name = "런"))!!
        runService.setScenarios(run.id.toLong(), userId, listOf(scenarioId))
        assertThat(testCaseService.coverage(projectId, userId).unauthored).isZero()

        // 남기면 시나리오가 살아 있고, 그 시나리오가 케이스를 여전히 담고 있다.
        runService.delete(run.id.toLong(), userId, dropScenarios = false)
        assertThat(scenarioRepository.findById(scenarioId)).isNotNull()
        assertThat(testCaseService.coverage(projectId, userId).unauthored).isZero()

        val second = runService.create(projectId, userId, TestRunCreateRequest(name = "런2"))!!
        runService.setScenarios(second.id.toLong(), userId, listOf(scenarioId))

        val result = runService.delete(second.id.toLong(), userId, dropScenarios = true)

        assertThat(result.deletedScenarioCount).isEqualTo(1)
        assertThat(scenarioRepository.findById(scenarioId)).isNull()
        assertThat(testCaseService.coverage(projectId, userId).unauthored).isEqualTo(1)
    }

    /** 다른 런에도 든 시나리오는 남긴다 — 한 런을 정리하다 남의 조합을 무너뜨리면 안 된다. */
    @Test
    fun `다른 런에도 든 시나리오는 함께 지우지 않는다`(): Unit = runBlocking {
        val (projectId, userId) = project()
        val shared = scenario(projectId, case(projectId, userId))
        val mine = scenario(projectId, case(projectId, userId))
        val keeper = runService.create(projectId, userId, TestRunCreateRequest(name = "지킬 런"))!!
        val doomed = runService.create(projectId, userId, TestRunCreateRequest(name = "지울 런"))!!
        runService.setScenarios(keeper.id.toLong(), userId, listOf(shared))
        runService.setScenarios(doomed.id.toLong(), userId, listOf(shared, mine))

        val preview = runService.deletionPreview(doomed.id.toLong(), userId)!!
        val result = runService.delete(doomed.id.toLong(), userId, dropScenarios = true)

        assertThat(preview.scenarioCount).isEqualTo(2)
        assertThat(preview.removableScenarioCount).isEqualTo(1)
        assertThat(result.deletedScenarioCount).isEqualTo(1)
        assertThat(scenarioRepository.findById(shared)).isNotNull()
        assertThat(scenarioRepository.findById(mine)).isNull()
        // 남은 런의 조합은 그대로다.
        assertThat(runService.getScenarios(keeper.id.toLong(), userId)!!.items).hasSize(1)
    }

    /** 남의 프로젝트 런은 세어 주지도, 지우지도 않는다. */
    @Test
    fun `비참여자에게는 아무 일도 일어나지 않는다`(): Unit = runBlocking {
        val (projectId, userId) = project()
        val scenarioId = scenario(projectId, case(projectId, userId))
        val run = runService.create(projectId, userId, TestRunCreateRequest(name = "런"))!!
        runService.setScenarios(run.id.toLong(), userId, listOf(scenarioId))
        val stranger = user()

        assertThat(runService.deletionPreview(run.id.toLong(), stranger)).isNull()
        assertThat(runService.delete(run.id.toLong(), stranger, dropScenarios = true).deletedScenarioCount).isZero()
        assertThat(scenarioRepository.findById(scenarioId)).isNotNull()
        assertThat(runService.get(run.id.toLong(), userId)).isNotNull()
    }

    private suspend fun user(): Long {
        val now = Instant.now()
        return appUserRepository.save(AppUserEntity(displayName = "run-del", createdAt = now, updatedAt = now)).id!!
    }

    private suspend fun project(): Pair<Long, Long> {
        val now = Instant.now()
        val userId = user()
        val projectId = projectRepository.save(
            ProjectEntity(name = "run-del", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        memberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "MEMBER", createdAt = now)
        )
        return projectId to userId
    }

    private suspend fun case(projectId: Long, userId: Long): Long =
        testCaseService.createTestCase(
            projectId, userId,
            TestCaseCreateRequest(scene = "TitleScene", step = "시작을 누른다", expectedValue = "다음 화면"),
        )!!.id.toLong()

    private suspend fun scenario(projectId: Long, caseId: Long): Long =
        scenarioRepository.save(
            TestScenarioEntity(
                projectId = projectId,
                title = "시나리오",
                steps = Json.of(
                    objectMapper.writeValueAsString(
                        listOf(ScenarioStep(action = "시작을 누른다", caseId = caseId))
                    )
                ),
            )
        ).id!!
}
