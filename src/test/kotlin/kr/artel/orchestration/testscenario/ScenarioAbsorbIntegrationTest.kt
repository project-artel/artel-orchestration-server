package kr.artel.orchestration.testscenario

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.ScenarioAbsorbService
import kr.artel.orchestration.testscenario.service.ScenarioReconcileService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * "이 둘을 합쳐 줘" 가 **끝까지** 가는지를 본다.
 *
 * 없을 때 무슨 일이 났는지가 이 테스트의 이유다. 수정 갈래는 시나리오 하나만 써낼 수 있어서
 * 합친 본문을 한쪽에 적는 것까지가 전부였고, 원본 한쪽이 그대로 남아 같은 흐름이 두 벌이 됐다.
 * 걷어내기는 되돌릴 수 없으므로 여기서 보는 것은 "지웠는가" 만이 아니라 **"지우지 말아야 할 때
 * 안 지웠는가"** 다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScenarioAbsorbIntegrationTest {

    @Autowired private lateinit var absorbService: ScenarioAbsorbService
    @Autowired private lateinit var reconcileService: ScenarioReconcileService
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository

    private fun scenario(title: String, vararg caseIds: Long) = ScenarioResult(
        title = title,
        steps = caseIds.map { ChatScenarioStep(action = "행위", caseId = it) },
    )

    @Test
    fun `합친 쪽이 전부 담았으면 흡수된 쪽을 런에서 걷어낸다`(): Unit = runBlocking {
        val (projectId, runId, cases) = fixture(3)
        val userId = memberOf(projectId)
        reconcileService.reconcile(
            runId, projectId, userId,
            listOf(scenario("앞 흐름", cases[0]), scenario("뒤 흐름", cases[1])),
        )
        val (keeperId, absorbedId) = idsOf(runId, "앞 흐름", "뒤 흐름")

        val outcome = absorbService.absorb(
            runId, keeperId, scenario("합친 흐름", cases[0], cases[1]), listOf(absorbedId),
        )

        assertThat(outcome.absorbed).containsExactly("뒤 흐름")
        assertThat(outcome.kept).isEmpty()
        assertThat(titlesIn(runId)).containsExactly("앞 흐름")
        // 어느 런에도 안 남는 시나리오는 본체까지 지운다 — 남겨 두면 커버리지가 계속 센다.
        assertThat(scenarioRepository.findById(absorbedId)).isNull()
    }

    @Test
    fun `검증이 하나라도 빠지면 그것은 합치기가 아니라 소실이므로 남긴다`(): Unit = runBlocking {
        val (projectId, runId, cases) = fixture(3)
        reconcileService.reconcile(
            runId, projectId, memberOf(projectId),
            listOf(scenario("앞 흐름", cases[0]), scenario("뒤 흐름", cases[1], cases[2])),
        )
        val (keeperId, absorbedId) = idsOf(runId, "앞 흐름", "뒤 흐름")

        // 합쳤다면서 셋째 케이스를 안 담았다.
        val outcome = absorbService.absorb(
            runId, keeperId, scenario("합친 흐름", cases[0], cases[1]), listOf(absorbedId),
        )

        assertThat(outcome.absorbed).isEmpty()
        assertThat(outcome.kept).singleElement().asString().contains("뒤 흐름")
        assertThat(titlesIn(runId)).containsExactlyInAnyOrder("앞 흐름", "뒤 흐름")
    }

    @Test
    fun `이 런의 것이 아니거나 없는 번호는 아무 일도 하지 않는다`(): Unit = runBlocking {
        val (projectId, runId, cases) = fixture(2)
        val other = runRepository.save(TestRunEntity(projectId = projectId, name = "다른 런")).id!!
        reconcileService.reconcile(
            runId, projectId, memberOf(projectId), listOf(scenario("앞 흐름", cases[0])),
        )
        reconcileService.reconcile(
            other, projectId, memberOf(projectId), listOf(scenario("남의 흐름", cases[1])),
        )
        val keeperId = idsOf(runId, "앞 흐름").first()
        val strangerId = idsOf(other, "남의 흐름").first()

        val outcome = absorbService.absorb(
            runId, keeperId, scenario("합친 흐름", cases[0], cases[1]),
            listOf(strangerId, keeperId, 9_999_999L),
        )

        assertThat(outcome.absorbed).isEmpty()
        assertThat(titlesIn(other)).containsExactly("남의 흐름")
        assertThat(scenarioRepository.findById(keeperId)).isNotNull()
    }

    /** 런에 담긴 시나리오 제목들, 조합 순서대로. */
    private suspend fun titlesIn(runId: Long): List<String> =
        runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
            .mapNotNull { scenarioRepository.findById(it.testScenarioId)?.title }

    private suspend fun idsOf(runId: Long, vararg titles: String): List<Long> {
        val byTitle = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
            .mapNotNull { scenarioRepository.findById(it.testScenarioId) }
            .associate { it.title to it.id!! }
        return titles.map { byTitle.getValue(it) }
    }

    private suspend fun memberOf(projectId: Long): Long =
        projectMemberRepository.findByProjectId(projectId).toList().first().appUserId

    private suspend fun fixture(count: Int): Triple<Long, Long, List<Long>> {
        val now = Instant.now()
        val userId = appUserRepository.save(testAppUser("absorb-user", now)).id!!
        val projectId = projectRepository.save(
            ProjectEntity(name = "absorb-project", genre = "ACTION", createdAt = now, updatedAt = now)
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
