package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.service.Guard
import kr.artel.orchestration.testscenario.service.ScenarioContradictionCheck
import kr.artel.orchestration.testscenario.service.ScenarioContradictionCheck.Step
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 흐름이 **자기가 말한 것과 어긋나는** 자리를 고정한다(ARTEL-656).
 *
 * 모양은 실측(런 216, 시나리오 703)에서 그대로 가져왔다 — 20번을 살리려고 코드가 끼운 브리지가
 * 두 칸 뒤를 죽였다. 게임을 돌리지 않고도 잡히는 자리라는 것이 이 검사의 전부다.
 */
class ScenarioContradictionCheckTest {

    private fun needs(variable: String, operator: String, value: String) =
        Guard(variable = variable, operator = operator, value = value)

    @Test
    fun `앞에서 만들어 놓은 값을 뒤에서 부정하면 짚는다`() {
        val walk = listOf(
            Step(at = 1, caseId = 1720, requires = listOf(needs("StagePosition", "==", "5")),
                 sets = mapOf("StagePosition" to "5")),
            // 코드가 끼운 브리지 — 다음 자리를 살리려고 0 으로 만든다.
            Step(at = 2, caseId = null, sets = mapOf("StagePosition" to "0")),
            Step(at = 3, caseId = 1625, requires = listOf(needs("StagePosition", "!=", "5"))),
            Step(at = 4, caseId = 1626, requires = listOf(needs("StagePosition", "==", "5"))),
        )

        val found = ScenarioContradictionCheck.find(walk)

        assertThat(found).hasSize(1)
        assertThat(found.single().at).isEqualTo(4)
        assertThat(found.single().caseId).isEqualTo(1626)
        assertThat(found.single().have).isEqualTo("0")
        // **무엇이 그렇게 만들었는지까지 말한다** — 고칠 자리는 요구한 쪽이 아니라 만든 쪽일 수 있다.
        assertThat(found.single().madeAt).isEqualTo(2)
        assertThat(found.single().describe()).contains("2번째 스텝이")
    }

    /**
     * **모르면 아무 말도 안 한다.** 저절로 바뀌는 화면을 지나면 그 값을 놓아 준다 — 전투에
     * 들어갔다 나오면 진행도가 올라 있고, 그것을 안 놓으면 멀쩡한 흐름이 전부 어긋나 보인다.
     */
    @Test
    fun `사이에 그 값이 모르게 되는 자리가 있으면 짚지 않는다`() {
        val walk = listOf(
            Step(at = 1, caseId = 1636, requires = listOf(needs("StagePosition", ">=", "1")),
                 sets = mapOf("StagePosition" to "1")),
            Step(at = 2, caseId = null, clears = setOf("StagePosition")),
            Step(at = 3, caseId = 1637, requires = listOf(needs("StagePosition", ">=", "2"))),
        )

        assertThat(ScenarioContradictionCheck.find(walk)).isEmpty()
    }

    /** 값을 아예 모르는 자리는 판단하지 않는다 — 첫 스텝의 요구는 시작 안내가 말할 자리다. */
    @Test
    fun `한 번도 정해진 적 없는 값은 짚지 않는다`() {
        val walk = listOf(
            Step(at = 1, caseId = 1626, requires = listOf(needs("StagePosition", "==", "5"))),
        )

        assertThat(ScenarioContradictionCheck.find(walk)).isEmpty()
    }

    /** 걸어가는 것은 어긋남이 아니다 — 앞엣것이 뒤엣것의 자리를 만든다. */
    @Test
    fun `앞 스텝이 만든 자리를 뒤 스텝이 요구하는 것은 어긋남이 아니다`() {
        val walk = listOf(
            Step(at = 1, caseId = 700, requires = listOf(needs("position", "==", "0")),
                 sets = mapOf("position" to "0")),
            Step(at = 2, caseId = null, sets = mapOf("position" to "1")),
            Step(at = 3, caseId = 701, requires = listOf(needs("position", "==", "1")),
                 sets = mapOf("position" to "1")),
        )

        assertThat(ScenarioContradictionCheck.find(walk)).isEmpty()
    }

    /**
     * **시작 조건은 같은 걸음에서 나온다**(ARTEL-660).
     *
     * 흐름이 스스로 만들지 못하는 것만 시작 조건이다. 따로 계산하면 규칙이 둘이 되고 갈라진다 —
     * 실측(런 233)에서 저장된 안내가 계산과 정반대를 적었다.
     */
    @Test
    fun `스스로 만들지 못하는 요구가 시작 조건으로 나온다`() {
        val walk = listOf(
            Step(at = 1, caseId = 1, requires = listOf(needs("saveData", "!=", "-1")),
                 sets = mapOf("saveData" to "0")),
            Step(at = 2, caseId = 2, requires = listOf(needs("saveData", "!=", "-1"))),
        )

        val found = ScenarioContradictionCheck.walk(walk)

        assertThat(found.contradictions).isEmpty()
        // 둘째는 첫째가 만들어 놓았다 — 한 번만 적는다.
        assertThat(found.opening.map { it.variable }).containsExactly("saveData")
    }

    /**
     * **흐름이 사이에서 만드는 값은 시작 조건이 아니다.** 그것까지 미리 와 있으라고 하면
     * "보스 앞까지 이겨 놓고 와서 전투를 세 번 더 해라"가 된다(런 233, 시나리오 784).
     */
    @Test
    fun `사이에서 모르게 되는 값은 시작 조건이 아니다`() {
        val walk = listOf(
            Step(at = 1, caseId = 1, sets = mapOf("flag" to "1")),
            Step(at = 2, caseId = null, clears = setOf("StagePosition")),
            Step(at = 3, caseId = 2, requires = listOf(needs("StagePosition", ">=", "4"))),
        )

        assertThat(ScenarioContradictionCheck.walk(walk).opening).isEmpty()
    }

    /**
     * **오르기만 하는 자리를 지나면 바닥을 들고 간다**(ARTEL-672).
     *
     * 전투를 지나면 진행도가 얼마인지는 모른다. 그런데 **줄지 않았다는 것은 안다** — 지도가
     * 그 값을 올리는 법을 `+1` 하나로만 말했기 때문이다. 통째로 놓아 주면 그 뒤로 무엇을
     * 요구해도 할 말이 없어지고, 실측(런 247)에서 검사가 0건을 답하는 사이 거꾸로 놓인 자리가
     * 네 군데 있었다 — 넷 다 사이에 전투가 끼어 있었다.
     */
    @Test
    fun `오르기만 하는 자리를 지난 뒤 더 낮은 값을 요구하면 짚는다`() {
        val walk = listOf(
            Step(at = 1, caseId = 1917, requires = listOf(needs("StagePosition", "==", "3")),
                 sets = mapOf("StagePosition" to "3")),
            Step(at = 2, caseId = null, climbs = setOf("StagePosition")),
            Step(at = 3, caseId = 1896, requires = listOf(needs("StagePosition", "==", "0"))),
        )

        val found = ScenarioContradictionCheck.find(walk)

        assertThat(found).hasSize(1)
        assertThat(found.single().caseId).isEqualTo(1896L)
        assertThat(found.single().have).isEqualTo("적어도 3")
    }

    /** 더 높이 요구하는 것은 걸어 오르면 되는 자리다 — 어긋남이 아니다. */
    @Test
    fun `오른 자리를 지난 뒤 더 높은 값을 요구하는 것은 짚지 않는다`() {
        val walk = listOf(
            Step(at = 1, caseId = 1, requires = listOf(needs("StagePosition", ">=", "1")),
                 sets = mapOf("StagePosition" to "1")),
            Step(at = 2, caseId = null, climbs = setOf("StagePosition")),
            Step(at = 3, caseId = 2, requires = listOf(needs("StagePosition", ">=", "4"))),
        )

        val found = ScenarioContradictionCheck.walk(walk)

        assertThat(found.contradictions).isEmpty()
        // 걸어서 오를 수 있는 자리라 **시작 조건도 아니다** — 미리 와 있으라고 하면 안 된다.
        assertThat(found.opening.map { it.value }).containsExactly("1")
    }

    /** 바닥을 모르면 예전 그대로다 — 모르는 것을 위반이라 하지 않는다. */
    @Test
    fun `한 번도 정해진 적 없는 값이면 올라도 짚지 않는다`() {
        val walk = listOf(
            Step(at = 1, caseId = null, climbs = setOf("StagePosition")),
            Step(at = 2, caseId = 1, requires = listOf(needs("StagePosition", "==", "0"))),
        )

        assertThat(ScenarioContradictionCheck.find(walk)).isEmpty()
    }

    /** 사이에서 값을 **다시 정하면** 바닥은 사라진다 — 타이틀에서 불러오면 그것이 새 값이다. */
    @Test
    fun `다시 정해진 값은 바닥을 지운다`() {
        val walk = listOf(
            Step(at = 1, caseId = 1, sets = mapOf("StagePosition" to "5")),
            Step(at = 2, caseId = null, climbs = setOf("StagePosition")),
            Step(at = 3, caseId = null, sets = mapOf("StagePosition" to "0")),
            Step(at = 4, caseId = 2, requires = listOf(needs("StagePosition", "==", "0"))),
        )

        assertThat(ScenarioContradictionCheck.find(walk)).isEmpty()
    }

    /** 무엇이 될지 모르는 값은 비교할 수 없다 — 위반이라 말하지 않는 것이 이 저장소의 규칙이다. */
    @Test
    fun `기호 값은 어긋난다고 하지 않는다`() {
        val walk = listOf(
            Step(at = 1, caseId = 800, sets = mapOf("tag" to "Me")),
            Step(at = 2, caseId = 801, requires = listOf(needs("tag", "==", "SpellObj.target.tag"))),
        )

        assertThat(ScenarioContradictionCheck.find(walk)).isEmpty()
    }
}
