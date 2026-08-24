package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.service.Guard
import kr.artel.orchestration.testscenario.service.ScenarioOrderCheck
import kr.artel.orchestration.testscenario.service.ScenarioOrdering
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 순서 판정(ARTEL-466). **말하지 않는 쪽이 기본값이라는 것**이 이 테스트의 절반이다.
 *
 * 실제 데이터에서 가져온 사례로 고정한다 — 141번이 `position 0→1`, 142번이 `1→2`다. 이 둘의
 * 순서는 케이스가 선언한 상태만으로 결정되고, 그 밖의 경우에는 판단하지 않는다.
 */
class ScenarioOrderCheckTest {

    private fun guard(variable: String, value: String, operator: String = "==") =
        Guard(variable, operator, value)

    @Test
    fun `앞 케이스가 남긴 값이 뒤 케이스의 요구를 만족하면 이어진다`() {
        // 141(0→1) 다음 142(1→2).
        val verdict = ScenarioOrderCheck.verdict(
            fromAfter = mapOf("position" to "1"),
            fromBefore = listOf(guard("position", "0")),
            toAfter = mapOf("position" to "2"),
            toBefore = listOf(guard("position", "1")),
        )

        assertThat(verdict).isEqualTo(ScenarioOrdering.CHAINED)
    }

    @Test
    fun `뒤집으면 이어지는 경우에만 순서를 지적한다`() {
        // 142(1→2) 다음 141(0→1) — 실제로 관측된 어긋남이다.
        val verdict = ScenarioOrderCheck.verdict(
            fromAfter = mapOf("position" to "2"),
            fromBefore = listOf(guard("position", "1")),
            toAfter = mapOf("position" to "1"),
            toBefore = listOf(guard("position", "0")),
        )

        assertThat(verdict).isEqualTo(ScenarioOrdering.REVERSED)
    }

    @Test
    fun `어긋나지만 뒤집어도 안 이어지면 판단하지 않는다`() {
        // 0→1 다음 3→4. 순서를 바꿔도 이어지지 않으므로 순서 문제라고 말할 근거가 없다 —
        // 사이에 다른 케이스가 있어야 하는 것일 수도 있다.
        val verdict = ScenarioOrderCheck.verdict(
            fromAfter = mapOf("position" to "1"),
            fromBefore = listOf(guard("position", "0")),
            toAfter = mapOf("position" to "4"),
            toBefore = listOf(guard("position", "3")),
        )

        assertThat(verdict).isEqualTo(ScenarioOrdering.NO_OPINION)
    }

    // ---- 말하지 않는 자리 -------------------------------------------------------------

    @Test
    fun `실행 뒤 상태를 선언하지 않은 케이스는 판단하지 않는다`() {
        // **일반화하지 않는다**: before 에 조작 효과를 더하면 after 가 된다고 가정하지 않는다.
        // 한 조작이 두 값을 건드리거나 효과가 조건부일 수 있어, 선언이 없으면 그것으로 끝이다.
        val verdict = ScenarioOrderCheck.verdict(
            fromAfter = emptyMap(),
            fromBefore = listOf(guard("position", "0")),
            toAfter = emptyMap(),
            toBefore = listOf(guard("position", "1")),
        )

        assertThat(verdict).isEqualTo(ScenarioOrdering.NO_OPINION)
    }

    @Test
    fun `선언된 값이 그 가드를 결정하지 못하면 판단하지 않는다`() {
        // 남긴 값과 요구하는 값이 서로 다른 변수다. 비교가 성립하지 않는 자리에서 순서를 말하면
        // 그게 곧 지어내는 것이다.
        val verdict = ScenarioOrderCheck.verdict(
            fromAfter = mapOf("position" to "1"),
            fromBefore = listOf(guard("position", "0")),
            toAfter = mapOf("hp" to "3"),
            toBefore = listOf(guard("StagePosition", "2")),
        )

        assertThat(verdict).isEqualTo(ScenarioOrdering.NO_OPINION)
    }

    @Test
    fun `요구가 아예 없으면 판단하지 않는다`() {
        val verdict = ScenarioOrderCheck.verdict(
            fromAfter = mapOf("position" to "1"),
            fromBefore = emptyList(),
            toAfter = mapOf("position" to "2"),
            toBefore = emptyList(),
        )

        assertThat(verdict).isEqualTo(ScenarioOrdering.NO_OPINION)
    }

    @Test
    fun `부등식 요구도 선언된 값으로 판정한다`() {
        // `StagePosition >= 2` 를 남긴 값 3 이 만족한다.
        val verdict = ScenarioOrderCheck.verdict(
            fromAfter = mapOf("StagePosition" to "3"),
            fromBefore = listOf(guard("StagePosition", "1")),
            toAfter = mapOf("StagePosition" to "4"),
            toBefore = listOf(guard("StagePosition", "2", ">=")),
        )

        assertThat(verdict).isEqualTo(ScenarioOrdering.CHAINED)
    }

    @Test
    fun `결정되는 가드 하나라도 어긋나면 그 순서로는 안 이어진다`() {
        // 두 요구 중 하나만 결정되고 그것이 어긋난다. 나머지를 모른다고 통과시키면 어긋남이 묻힌다.
        val verdict = ScenarioOrderCheck.verdict(
            fromAfter = mapOf("position" to "2"),
            fromBefore = listOf(guard("position", "1")),
            toAfter = mapOf("position" to "1"),
            toBefore = listOf(guard("position", "0"), guard("InteractionLock", "0")),
        )

        assertThat(verdict).isEqualTo(ScenarioOrdering.REVERSED)
    }
}
