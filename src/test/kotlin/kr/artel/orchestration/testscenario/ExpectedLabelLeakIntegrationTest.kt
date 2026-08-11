package kr.artel.orchestration.testscenario

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testrun.service.RunScenarioReader
import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import kr.artel.orchestration.testscenario.service.ScenarioReconcileService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 기대 판정 라벨이 **에이전트에게 절대 나가지 않는지**(ARTEL-301).
 *
 * 이 파일이 이 기능의 유일한 치명적 실패에 대한 방어선이다. `expected_passed`가 에이전트가 읽는
 * 어떤 경로로든 새면 그 순간 이 측정 전체가 무의미해지고, **그 사고는 조용하다** — 점수가 좋아
 * 보일 뿐이라 아무도 알아채지 못한다. 그래서 필드를 하나하나 확인하는 대신 **직렬화 결과 문자열
 * 전체에 그 키가 없음**을 단언한다. 나중에 저작 모델에 필드가 늘 때 이 테스트가 걸린다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExpectedLabelLeakIntegrationTest {

    @Autowired private lateinit var compositionService: ScenarioCompositionService
    @Autowired private lateinit var reconcileService: ScenarioReconcileService
    @Autowired private lateinit var runScenarioReader: RunScenarioReader
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var objectMapper: ObjectMapper

    private var projectId: Long = 0
    private var caseId: Long = 0

    @BeforeEach
    fun seed(): Unit = runBlocking {
        wipe()
        val now = Instant.now()
        projectId = requireNotNull(
            projectRepository.save(
                ProjectEntity(name = "label-p", genre = "ACTION", createdAt = now, updatedAt = now)
            )
        ).id!!
        caseId = requireNotNull(
            testCaseRepository.save(
                TestCaseEntity(
                    projectId = projectId, category = "상점", title = "구매 버튼을 누른다",
                    precondition = "골드 100 이상", expected = "인벤토리에 아이템이 들어온다"
                )
            ).id
        )
    }

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        runScenarioRepository.deleteAll()
        runRepository.deleteAll()
        scenarioRepository.deleteAll()
        testCaseRepository.deleteAll()
        projectRepository.deleteAll()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `QA 실행 계약에 expected_passed가 실리지 않는다`(): Unit = runBlocking {
        val scenarioId = saveScenario(labelledDraft())

        val contract = compositionService.agentScenario(storedDraft(scenarioId))
        val wire = objectMapper.writeValueAsString(contract)

        // 계약이 실제로 이 시나리오를 담고 있는지부터 확인한다 — 빈 계약은 아무것도 증명하지 못한다.
        assertThat(wire).contains("구매 버튼을 누른다")
        assertThat(wire).contains("인벤토리에 아이템이 들어온다")
        // 그리고 정답지는 없다.
        assertThat(wire).doesNotContain("expected_passed")
        assertThat(wire).doesNotContain("expectedPassed")
    }

    @Test
    fun `작성 챗봇 컨텍스트에도 expected_passed가 실리지 않는다`(): Unit = runBlocking {
        val scenarioId = saveScenario(labelledDraft())
        val runId = linkToRun(scenarioId)

        val current = runScenarioReader.currentScenarios(runId)
        val wire = objectMapper.writeValueAsString(current)

        assertThat(current).hasSize(1)
        assertThat(wire).contains("구매 버튼을 누른다")
        assertThat(wire).doesNotContain("expected_passed")
        assertThat(wire).doesNotContain("expectedPassed")
    }

    @Test
    fun `라벨은 저장 payload에는 그대로 남는다`(): Unit = runBlocking {
        val scenarioId = saveScenario(labelledDraft())

        val stored = storedSteps(scenarioId)

        // 계약에서 뺀 것이지 저장에서 뺀 것이 아니다 — 여기 없으면 채점할 것이 없다.
        assertThat(stored.map { it.expectedPassed }).containsExactly(true, false, null)
    }

    @Test
    fun `챗봇이 시나리오를 고쳐도 그대로인 스텝의 라벨은 살아남는다`(): Unit = runBlocking {
        val scenarioId = saveScenario(labelledDraft())
        val runId = linkToRun(scenarioId)

        // 에이전트는 1·2번 스텝을 그대로 두고 3번의 문구만 고쳐 돌려준다.
        reconcileService.reconcile(
            runId, projectId,
            listOf(
                ScenarioResult(
                    scenarioId = scenarioId, title = "상점", description = "구매 흐름",
                    steps = listOf(
                        ChatScenarioStep(action = "상점을 연다", caseId = null),
                        ChatScenarioStep(action = "구매 버튼을 누른다", caseId = caseId),
                        ChatScenarioStep(action = "완전히 다른 행위", caseId = null),
                    )
                )
            )
        )

        val stored = storedSteps(scenarioId)
        assertThat(stored.map { it.action })
            .containsExactly("상점을 연다", "구매 버튼을 누른다", "완전히 다른 행위")
        // 안 바뀐 두 스텝의 라벨은 살아 있다. 살리지 않으면 챗봇 편집 한 번에 정답지가 통째로 사라진다.
        assertThat(stored[0].expectedPassed).isTrue()
        assertThat(stored[1].expectedPassed).isFalse()
        // 행위가 바뀐 스텝에는 옮겨 붙이지 않는다 — 그 라벨은 더 이상 이 스텝에 대한 판단이 아니다.
        assertThat(stored[2].expectedPassed).isNull()
    }

    @Test
    fun `스텝이 끼어들면 뒤 스텝에 옛 라벨이 밀려 붙지 않는다`(): Unit = runBlocking {
        val scenarioId = saveScenario(labelledDraft())
        val runId = linkToRun(scenarioId)

        // 맨 앞에 스텝 하나가 끼어들었다. 위치만 보고 이으면 라벨이 통째로 한 칸씩 밀린다.
        reconcileService.reconcile(
            runId, projectId,
            listOf(
                ScenarioResult(
                    scenarioId = scenarioId, title = "상점", description = "구매 흐름",
                    steps = listOf(
                        ChatScenarioStep(action = "로그인한다", caseId = null),
                        ChatScenarioStep(action = "상점을 연다", caseId = null),
                        ChatScenarioStep(action = "구매 버튼을 누른다", caseId = caseId),
                    )
                )
            )
        )

        // 잘못 달린 라벨은 없는 라벨보다 나쁘다 — 기계가 지어낸 정답지가 되기 때문이다.
        assertThat(storedSteps(scenarioId).map { it.expectedPassed }).containsExactly(null, null, null)
    }

    @Test
    fun `라벨 없는 기존 시나리오도 그대로 실행 계약이 만들어진다`(): Unit = runBlocking {
        // 이 기능 이전에 만들어진 시나리오다 — 저장된 스텝에 라벨 키 자체가 없다.
        val legacy = ScenarioDraft(
            title = "옛", description = "", steps = listOf(ScenarioStep(action = "연다"))
        )
        val scenarioId = saveScenario(legacy)

        val contract = compositionService.agentScenario(storedDraft(scenarioId))

        assertThat(contract.steps).hasSize(1)
        assertThat(contract.steps.single().action).isEqualTo("연다")
        assertThat(storedSteps(scenarioId).single().expectedPassed).isNull()
    }

    // ----------------------------------------------------------------- helpers

    /** 통과 기대 / 실패 기대 / 미지정을 한 시나리오에 모두 담는다. */
    private fun labelledDraft() = ScenarioDraft(
        title = "상점",
        description = "구매 흐름",
        steps = listOf(
            ScenarioStep(action = "상점을 연다", expectedPassed = true),
            ScenarioStep(action = "구매 버튼을 누른다", caseId = caseId, expectedPassed = false),
            ScenarioStep(action = "완전히 다른 행위"),
        )
    )

    private suspend fun saveScenario(draft: ScenarioDraft): Long =
        requireNotNull(
            scenarioRepository.save(
                TestScenarioEntity(projectId = projectId).withDraft(draft, objectMapper)
            ).id
        )

    private suspend fun linkToRun(scenarioId: Long): Long {
        val runId = requireNotNull(runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id)
        runScenarioRepository.save(
            TestRunScenarioEntity(testRunId = runId, testScenarioId = scenarioId, position = 0)
        )
        return runId
    }

    private suspend fun storedDraft(scenarioId: Long): ScenarioDraft =
        requireNotNull(scenarioRepository.findById(scenarioId)).toDraft(objectMapper)

    private suspend fun storedSteps(scenarioId: Long): List<ScenarioStep> =
        storedDraft(scenarioId).steps
}
