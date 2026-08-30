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
