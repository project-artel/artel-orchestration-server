package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.service.Guard
import kr.artel.orchestration.testscenario.service.ScenarioFlowMatrix.Link.BESIDE
import kr.artel.orchestration.testscenario.service.ScenarioFlowMatrix.Link.BLOCKED
import kr.artel.orchestration.testscenario.service.ScenarioFlowMatrix.Link.BY_OPERATION
import kr.artel.orchestration.testscenario.service.ScenarioFlowMatrix.Link.BY_PLAY
import kr.artel.orchestration.testscenario.service.ScenarioFlowPlan
import kr.artel.orchestration.testscenario.service.ScenarioFlowPlan.Case
import kr.artel.orchestration.testscenario.service.ScenarioFlowPlan.Link
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 흐름 계산을 **실측에서 틀렸던 모양으로** 고정한다(ARTEL-657).
 *
 * word-venture 의 지도 흐름이 기준이다 — 진행도는 전투에서만 오르고, 위치는 방향키로 걷는다.
 */
class ScenarioFlowPlanTest {

    private fun needs(variable: String, operator: String, value: String) =
        Guard(variable = variable, operator = operator, value = value)

    /** 사이에 아무것도 필요 없는 자리들은 한 흐름으로 붙는다. */
    @Test
    fun `이어지는 자리는 한 흐름이 된다`() {
        val cases = listOf(Case(id = 1), Case(id = 2), Case(id = 3))

        val flows = ScenarioFlowPlan.of(cases) { _, _ -> Link(BESIDE) }

        assertThat(flows).hasSize(1)
        assertThat(flows.single().caseIds).containsExactly(1L, 2L, 3L)
        assertThat(flows.single().gaps).isZero()
    }

    /** 이을 길이 없으면 흐름이 갈린다 — 붙일 수 없는 것을 붙이지 않는다. */
    @Test
    fun `아예 못 가는 자리는 다른 흐름이 된다`() {
        val cases = listOf(Case(id = 1), Case(id = 2))

        val flows = ScenarioFlowPlan.of(cases) { _, _ -> Link(BLOCKED) }

        assertThat(flows).hasSize(2)
    }

    /**
     * **어긋나는 자리는 애초에 안 놓는다**(런 216, 시나리오 703).
     *
     * `진행도 == 5` 다음에 `진행도 != 5` 는 어떤 스텝으로도 성립하지 않는다. 사이에 그 값이
     * 모르게 되는 자리가 없으면 다른 흐름으로 가야 한다.
     */
    @Test
    fun `앞에서 정해진 값을 부정하는 자리는 같은 흐름에 안 놓는다`() {
        val cases = listOf(
            Case(id = 1720, requires = listOf(needs("StagePosition", "==", "5")),
                 sets = mapOf("StagePosition" to "5")),
            Case(id = 1625, requires = listOf(needs("StagePosition", "!=", "5"))),
        )

        val flows = ScenarioFlowPlan.of(cases) { _, _ -> Link(BESIDE) }

        assertThat(flows).hasSize(2)
    }

    /** 사이에서 그 값이 모르게 되면 이어도 된다 — 전투를 지나면 진행도가 오른다. */
    @Test
    fun `사이에서 모르게 되는 값이면 이어 놓는다`() {
        val cases = listOf(
            Case(id = 1636, requires = listOf(needs("StagePosition", ">=", "1")),
                 sets = mapOf("StagePosition" to "1")),
            Case(id = 1637, requires = listOf(needs("StagePosition", ">=", "2"))),
        )

        val flows = ScenarioFlowPlan.of(cases) { _, _ ->
            Link(BY_PLAY, clears = setOf("StagePosition"))
        }

        assertThat(flows).hasSize(1)
        assertThat(flows.single().caseIds).containsExactly(1636L, 1637L)
        // 지나가야 하는 자리가 하나 있다.
        assertThat(flows.single().gaps).isEqualTo(1)
    }

    /**
     * **시작 조건은 걸으면서 나온다**(런 217, 시나리오 712).
     *
     * 흐름이 스스로 만들지 못하고 한 번도 모르게 된 적 없는 요구만 시작 조건이다. 다 쓰고 나서
     * 훑어 적으면 흐름 안에서 갈리는 값의 한쪽만 골라 첫 스텝과 어긋난다.
     */
    @Test
    fun `스스로 만들지 못하는 요구만 시작 조건이 된다`() {
        val cases = listOf(
            Case(id = 1, requires = listOf(needs("saveData", "!=", "-1")), sets = mapOf("saveData" to "0")),
            Case(id = 2, requires = listOf(needs("saveData", "!=", "-1"))),
        )

        val flows = ScenarioFlowPlan.of(cases) { _, _ -> Link(BESIDE) }

        // 첫 자리의 요구만 시작 조건이다 — 둘째는 첫째가 만들어 놓았다.
        assertThat(flows.single().opening.map { it.variable }).containsExactly("saveData")
    }

    /** 사이에서 모르게 된 값을 다시 요구하는 것은 시작 조건이 아니라 지나갈 자리다. */
    @Test
    fun `모르게 된 값을 다시 요구하는 것은 시작 조건이 아니다`() {
        val cases = listOf(
            Case(id = 1, requires = listOf(needs("StagePosition", ">=", "1")),
                 sets = mapOf("StagePosition" to "1"), clears = setOf("StagePosition")),
            Case(id = 2, requires = listOf(needs("StagePosition", ">=", "2"))),
        )

        val flows = ScenarioFlowPlan.of(cases) { _, _ -> Link(BESIDE) }

        assertThat(flows.single().caseIds).containsExactly(1L, 2L)
        assertThat(flows.single().opening.map { it.value }).containsExactly("1")
    }

    /** 싼 것부터 놓는다 — 아무것도 안 넣는 자리가 지나가야 하는 자리보다 먼저다. */
    @Test
    fun `사이에 아무것도 안 드는 자리를 먼저 놓는다`() {
        val cases = listOf(Case(id = 1), Case(id = 2), Case(id = 3))

        val flows = ScenarioFlowPlan.of(cases) { from, to ->
            when {
                from == 1L && to == 3L -> Link(BESIDE)
                from == 1L && to == 2L -> Link(BY_PLAY)
                else -> Link(BY_OPERATION)
            }
        }

        assertThat(flows.single().caseIds).containsExactly(1L, 3L, 2L)
    }
}
