package kr.artel.orchestration.testscenario

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioStepKind
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.ScenarioGapFiller
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 사용자가 알려준 방법이 **그 자리에** 들어가는가(ARTEL-487).
 *
 * 실측에서 답이 엉뚱한 자리에 들어가고 알림은 그대로 남았다 — 모델에게 다시 쓰게 했기 때문이다.
 * 여기서 보는 것은 자리가 유지되는지 하나다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ScenarioGapFillerTest {

    @Autowired private lateinit var filler: ScenarioGapFiller
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository

    private val seq = AtomicLong(900_000)

    private var projectId: Long = 0
    private var runId: Long = 0

    @BeforeEach
    fun fixture(): Unit = runBlocking {
        val now = Instant.now()
        projectId = projectRepository.save(
            ProjectEntity(name = "gap-${seq.incrementAndGet()}", genre = "RPG", createdAt = now, updatedAt = now)
        )!!.id!!
        runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
    }

    /** 알림 블록 앞뒤 스텝은 그대로 두고, 그 한 줄만 사용자 말로 바뀐다. */
    @Test
    fun `알림 블록이 있던 자리에 사용자 말이 들어간다`(): Unit = runBlocking {
        val scenarioId = save(
            step("StoryScene에서 Space 입력을 한다.", caseId = 11),
            gap("StoryScene→Map_scene"),
            step("Map_scene에 진입해 튜토리얼 대화를 확인한다.", caseId = 12),
        )

        val filled = filler.fill(runId, "StoryScene→Map_scene", "대화가 끝날 때까지 Space를 누른다.")

        assertThat(filled).isEqualTo(1)
        val steps = scenarioRepository.findById(scenarioId)!!.toDraft(objectMapper).steps
        assertThat(steps.map { it.action }).containsExactly(
            "StoryScene에서 Space 입력을 한다.",
            "대화가 끝날 때까지 Space를 누른다.",
            "Map_scene에 진입해 튜토리얼 대화를 확인한다.",
        )
        // 사람이 알려준 자리다. 케이스가 붙지 않으므로 실행이 통과/실패를 매기지 않는다.
        assertThat(steps[1].stepSource).isEqualTo(ScenarioStepSource.HUMAN)
        assertThat(steps[1].stepKind).isEqualTo(ScenarioStepKind.ACTION)
        assertThat(steps[1].caseId).isNull()
        assertThat(steps[1].stepUnknownReason).isNull()
    }

    /** 다른 구간의 알림은 건드리지 않는다 — 한 답이 시나리오 전체를 덮으면 안 된다. */
    @Test
    fun `물어본 구간의 알림만 바뀐다`(): Unit = runBlocking {
        val scenarioId = save(
            gap("StoryScene→Map_scene"),
            gap("Map_scene→TurnBattleScene"),
        )

        val filled = filler.fill(runId, "StoryScene→Map_scene", "저절로 넘어간다.")

        assertThat(filled).isEqualTo(1)
        val steps = scenarioRepository.findById(scenarioId)!!.toDraft(objectMapper).steps
        assertThat(steps[0].stepKind).isEqualTo(ScenarioStepKind.ACTION)
        assertThat(steps[1].stepKind).isEqualTo(ScenarioStepKind.GAP)
        assertThat(steps[1].stepUnknownReason).isEqualTo("Map_scene→TurnBattleScene")
    }

    /**
     * 채울 자리가 없으면 아무것도 하지 않고 0 을 돌려준다. 부르는 쪽은 그 답을 삼키지 않고
     * 평소대로 모델에게 넘긴다 — 사용자가 이미 손으로 채운 뒤 답했을 수 있다.
     */
    @Test
    fun `채울 자리가 없으면 그대로 둔다`(): Unit = runBlocking {
        val scenarioId = save(step("StoryScene에서 Space 입력을 한다.", caseId = 11))

        val filled = filler.fill(runId, "StoryScene→Map_scene", "대화를 넘긴다.")

        assertThat(filled).isZero()
        assertThat(scenarioRepository.findById(scenarioId)!!.toDraft(objectMapper).steps).hasSize(1)
    }

    /** 빈 답은 답이 아니다. 그것으로 알림을 지우면 미상이 빈 줄로 바뀔 뿐이다. */
    @Test
    fun `빈 답으로는 채우지 않는다`(): Unit = runBlocking {
        save(gap("StoryScene→Map_scene"))

        assertThat(filler.fill(runId, "StoryScene→Map_scene", "   ")).isZero()
    }

    private fun step(action: String, caseId: Long) = ScenarioStep(
        action = action, caseId = caseId, stepSource = ScenarioStepSource.CASE,
        stepKind = ScenarioStepKind.ACTION,
    )

    private fun gap(blockedBy: String) = ScenarioStep(
        action = "이 구간의 경로를 확인할 수 없습니다 — $blockedBy",
        stepSource = ScenarioStepSource.UNKNOWN,
        stepKind = ScenarioStepKind.GAP,
        stepUnknownReason = blockedBy,
    )

    private suspend fun save(vararg steps: ScenarioStep): Long {
        val scenario = scenarioRepository.save(
            TestScenarioEntity(
                projectId = projectId,
                title = "시나리오",
                description = "설명",
                steps = Json.of(objectMapper.writeValueAsString(steps.toList())),
            )
        )
        val position = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().size
        runScenarioRepository.save(
            TestRunScenarioEntity(testRunId = runId, testScenarioId = scenario.id!!, position = position)
        )
        return scenario.id!!
    }
}
