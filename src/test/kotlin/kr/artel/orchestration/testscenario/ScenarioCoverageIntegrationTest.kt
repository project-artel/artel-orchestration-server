package kr.artel.orchestration.testscenario

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ReviewedCases
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.ScenarioReconcileService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 검수가 **실제 저장을 막는지**를 본다. 규칙 자체는 [ScenarioCoverageAuditTest]가 DB 없이 따로
 * 검증하고, 여기서는 "막혔다면 정말 한 줄도 안 들어갔는가"만 확인한다 — 부분 저장은 "일부만 검증된
 * 시나리오"를 남기고, 그건 검사를 안 한 것보다 나쁘다(믿을 수 있어 보인다).
 *
 * 미커버 조회도 함께 본다. 커버 집합을 테이블로 저장하지 않고 `steps`의 `case_id`에서 매번 계산하므로,
 * 이 질의가 틀리면 화면 수치와 Agent 제안이 동시에 틀린다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScenarioCoverageIntegrationTest {

    @Autowired private lateinit var reconcileService: ScenarioReconcileService
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var testCaseService: kr.artel.orchestration.testcase.service.TestCaseService
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository

    private fun scenario(vararg caseIds: Long?) = ScenarioResult(
        title = "저작된 시나리오",
        steps = caseIds.map { ChatScenarioStep(action = "행위", caseId = it) },
    )

    @Test
    fun `판정한 케이스가 빠지면 한 줄도 저장되지 않는다`(): Unit = runBlocking {
        val (projectId, runId, cases) = fixture(3)

        val outcome = reconcileService.reconcile(
            runId, projectId,
            listOf(scenario(cases[0])),
            ReviewedCases(included = listOf(cases[0], cases[1]), excluded = listOf(cases[2])),
        )

        assertThat(outcome.rejected).isTrue()
        assertThat(outcome.applied).isZero()
        assertThat(outcome.findings.missing).containsExactly(cases[1])
        // 부분 저장이 없다는 것이 이 테스트의 요점이다.
        assertThat(scenarioRepository.findByProjectId(projectId).toList()).isEmpty()
    }

    @Test
    fun `검토하지 않은 케이스가 있으면 저장되지 않는다`(): Unit = runBlocking {
        val (projectId, runId, cases) = fixture(3)

        // 판정이 두 건만 덮는다. 셋째는 어느 배열에도 없다 = 보지 않았다.
        val outcome = reconcileService.reconcile(
            runId, projectId,
            listOf(scenario(cases[0])),
            ReviewedCases(included = listOf(cases[0]), excluded = listOf(cases[1])),
        )

        assertThat(outcome.findings.unreviewed).containsExactly(cases[2])
        assertThat(scenarioRepository.findByProjectId(projectId).toList()).isEmpty()
    }

    @Test
    fun `없는 케이스 번호를 가리키면 저장되지 않는다`(): Unit = runBlocking {
        val (projectId, runId, cases) = fixture(2)
        val ghost = cases.max() + 9_999

        val outcome = reconcileService.reconcile(
            runId, projectId,
            listOf(scenario(cases[0], ghost)),
            ReviewedCases(included = listOf(cases[0]), excluded = listOf(cases[1])),
        )

        assertThat(outcome.findings.ghost).containsExactly(ghost)
        assertThat(scenarioRepository.findByProjectId(projectId).toList()).isEmpty()
    }

    @Test
    fun `검수를 통과하면 평소대로 저장된다`(): Unit = runBlocking {
        val (projectId, runId, cases) = fixture(3)

        val outcome = reconcileService.reconcile(
            runId, projectId,
            listOf(scenario(cases[0], null), scenario(cases[1])),
            ReviewedCases(included = listOf(cases[0], cases[1]), excluded = listOf(cases[2])),
        )

        assertThat(outcome.rejected).isFalse()
        assertThat(outcome.applied).isEqualTo(2)
        assertThat(scenarioRepository.findByProjectId(projectId).toList()).hasSize(2)
    }

    @Test
    fun `판정이 없으면 검사 없이 저장된다`(): Unit = runBlocking {
        // 구버전 Agent 경로. 이 null 하나가 롤백 스위치이므로 저장이 막히면 안 된다.
        val (projectId, runId, cases) = fixture(2)

        val outcome = reconcileService.reconcile(runId, projectId, listOf(scenario(cases[0])))

        assertThat(outcome.rejected).isFalse()
        assertThat(outcome.applied).isEqualTo(1)
        assertThat(scenarioRepository.findByProjectId(projectId).toList()).hasSize(1)
    }

    @Test
    fun `미커버 조회는 시나리오가 건드리지 않은 케이스만 낸다`(): Unit = runBlocking {
        val (projectId, runId, cases) = fixture(4)

        // 셋 중 둘만 담는다. 브리지 스텝(case_id null)은 아무것도 커버하지 않아야 한다.
        reconcileService.reconcile(runId, projectId, listOf(scenario(cases[0], null, cases[2])))

        assertThat(testCaseRepository.findUncoveredIdsByProjectId(projectId).toList())
            .containsExactly(cases[1], cases[3])
    }

    @Test
    fun `시나리오가 없으면 전량이 미커버다`(): Unit = runBlocking {
        val (projectId, _, cases) = fixture(3)

        assertThat(testCaseRepository.findUncoveredIdsByProjectId(projectId).toList())
            .containsExactlyElementsOf(cases)
    }

    @Test
    fun `커버리지는 저작 축과 검증 축을 함께 낸다`(): Unit = runBlocking {
        val (projectId, runId, cases) = fixture(4)
        // 하나는 QA 런이 통과시킨 상태로 만든다 — 저작 여부와 검증 여부는 다른 축이다.
        testCaseRepository.findById(cases[0])!!
            .let { testCaseRepository.save(it.copy(verificationStatus = "VERIFIED")) }
        reconcileService.reconcile(runId, projectId, listOf(scenario(cases[0], cases[1])))

        val coverage = testCaseService.coverage(projectId, memberOf(projectId))

        assertThat(coverage.total).isEqualTo(4)
        assertThat(coverage.authored).isEqualTo(2)
        assertThat(coverage.unauthored).isEqualTo(2)
        // 저작된 2건 중 1건만 검증됐다. 두 축이 같은 값이 아니라는 것이 이 응답의 요점이다.
        assertThat(coverage.verified).isEqualTo(1)
        assertThat(coverage.draft).isEqualTo(3)
        assertThat(coverage.broken).isZero()
        assertThat(coverage.uncoveredScenes.sumOf { it.count }).isEqualTo(2)
    }

    @Test
    fun `비참여자에게는 전부 0으로 답한다`(): Unit = runBlocking {
        val (projectId, _, _) = fixture(3)
        val outsider = appUserRepository.save(
            AppUserEntity(displayName = "outsider", createdAt = Instant.now(), updatedAt = Instant.now())
        ).id!!

        val coverage = testCaseService.coverage(projectId, outsider)

        // 건수를 흘리는 것도 존재를 알리는 일이다 — 목록과 같은 판단.
        assertThat(coverage.total).isZero()
        assertThat(coverage.uncoveredScenes).isEmpty()
    }

    /** 이 프로젝트의 참여자 id. fixture가 만든 사용자를 되찾는다. */
    private suspend fun memberOf(projectId: Long): Long =
        projectMemberRepository.findByProjectId(projectId).toList().first().appUserId

    /** 프로젝트 + 참여자 + 런 + 케이스 [count]건. 케이스 id는 오름차순. */
    private suspend fun fixture(count: Int): Triple<Long, Long, List<Long>> {
        val now = Instant.now()
        val userId = appUserRepository.save(
            AppUserEntity(displayName = "coverage-user", createdAt = now, updatedAt = now)
        ).id!!
        val projectId = projectRepository.save(
            ProjectEntity(name = "coverage-project", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "MEMBER", createdAt = now)
        )
        val runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        val cases = (1..count).map { n ->
            testCaseRepository.save(
                TestCaseEntity(
                    projectId = projectId,
                    scene = "TitleScene",
                    step = "스텝 $n",
                    expectedValue = "기대 $n",
                )
            ).id!!
        }
        return Triple(projectId, runId, cases)
    }
}
