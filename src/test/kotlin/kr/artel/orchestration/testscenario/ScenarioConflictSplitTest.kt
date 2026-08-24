package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.service.ScenarioConflictSplit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 함께 담을 수 없는 케이스를 묻지 않고 나눈다(ARTEL-497).
 *
 * 되묻기로 해 봤을 때 무엇이 안 됐는지가 이 테스트의 기준이다(런 152) — "나눠 주세요"가 모델에게
 * 갔지만 어느 쌍이 문제인지는 함께 가지 않아, 같은 묶음이 돌아오고 같은 질문이 다시 나갔다.
 */
class ScenarioConflictSplitTest {

    private fun step(caseId: Long?, action: String = "확인") =
        ChatScenarioStep(action = action, caseId = caseId)

    private fun scenario(vararg steps: ChatScenarioStep, id: Long? = null, title: String = "전투 전량") =
        ScenarioResult(scenarioId = id, title = title, description = "d", steps = steps.toList())

    /** 짝수끼리·홀수끼리는 되고, 짝수와 홀수는 안 된다. */
    private val parity: (Long, Long) -> Boolean = { a, b -> (a % 2) != (b % 2) }

    @Test
    fun `어긋나는 것이 없으면 그대로 둔다`() {
        val one = scenario(step(2), step(4))

        val outcome = ScenarioConflictSplit.apply(listOf(one), parity)

        assertThat(outcome.scenarios).isEqualTo(listOf(one))
        assertThat(outcome.notes).isEmpty()
    }

    @Test
    fun `함께 볼 수 없는 것끼리 가른다`() {
        val outcome = ScenarioConflictSplit.apply(
            listOf(scenario(step(2), step(3), step(4), step(5))), parity,
        )

        assertThat(outcome.scenarios).hasSize(2)
        assertThat(outcome.scenarios[0].steps.mapNotNull { it.caseId }).containsExactly(2L, 4L)
        assertThat(outcome.scenarios[1].steps.mapNotNull { it.caseId }).containsExactly(3L, 5L)
        assertThat(outcome.notes).containsExactly("전투 전량" to 2)
    }

    @Test
    fun `첫 조각이 원래 시나리오다`() {
        // 물려받지 않으면 수정 요청 한 번에 원본이 남고 사본이 생긴다.
        val outcome = ScenarioConflictSplit.apply(
            listOf(scenario(step(2), step(3), id = 77L)), parity,
        )

        assertThat(outcome.scenarios.map { it.scenarioId }).containsExactly(77L, null)
        assertThat(outcome.scenarios.map { it.title }).containsExactly("전투 전량", "전투 전량 (2)")
    }

    @Test
    fun `케이스 없는 스텝은 뒤따르는 검증을 따라간다`() {
        // 준비 동작은 다음에 볼 것을 위한 것이다. 앞으로 붙이면 준비만 남은 조각이 생긴다.
        val outcome = ScenarioConflictSplit.apply(
            listOf(scenario(step(2), step(null, "홀수를 준비한다"), step(3))), parity,
        )

        assertThat(outcome.scenarios[0].steps.map { it.action }).containsExactly("확인")
        assertThat(outcome.scenarios[1].steps.map { it.action })
            .containsExactly("홀수를 준비한다", "확인")
    }

    @Test
    fun `마지막에 남은 스텝은 앞을 따라간다`() {
        val outcome = ScenarioConflictSplit.apply(
            listOf(scenario(step(2), step(3), step(null, "정리한다"))), parity,
        )

        assertThat(outcome.scenarios[1].steps.map { it.action }).containsExactly("확인", "정리한다")
    }

    @Test
    fun `순서는 지킨다`() {
        // 나눈 조각 안의 상대 순서가 흔들리면 순서 판정이 뒤에서 엉뚱한 것을 지적한다.
        val outcome = ScenarioConflictSplit.apply(
            listOf(scenario(step(5), step(4), step(3), step(2))), parity,
        )

        assertThat(outcome.scenarios[0].steps.mapNotNull { it.caseId }).containsExactly(5L, 3L)
        assertThat(outcome.scenarios[1].steps.mapNotNull { it.caseId }).containsExactly(4L, 2L)
    }

    @Test
    fun `한 무리 안에는 어긋나는 쌍이 남지 않는다`() {
        // 셋 이상이 서로 어긋나면 무리도 셋이 된다 — 무리 안의 **모든** 원소와 견주기 때문이다.
        val exclusive: (Long, Long) -> Boolean = { a, b -> a != b }
        val outcome = ScenarioConflictSplit.apply(
            listOf(scenario(step(1), step(2), step(3))), exclusive,
        )

        assertThat(outcome.scenarios).hasSize(3)
        assertThat(outcome.notes).containsExactly("전투 전량" to 3)
    }

    // --- 조각의 제목과 자리 (ARTEL-518) ---------------------------------------------

    @Test
    fun `이미 나뉜 조각을 또 나눠도 괄호는 하나다`() {
        // 실측(런 155): 사용자가 지운 케이스를 "다시 담아줘" 하자 `…(5)` 를 다시 나눴고,
        // 제목이 `…(5) (2)` 가 됐다. 한 번 더 나누면 `(5) (2) (2)` 다.
        val outcome = ScenarioConflictSplit.apply(
            listOf(scenario(step(2), step(3), title = "맵 이동 (5)")), parity,
        )

        assertThat(outcome.scenarios.map { it.title }).containsExactly("맵 이동 (5)", "맵 이동 (5.2)")
    }

    @Test
    fun `조각이 어느 것에서 갈라졌는지 남긴다`() {
        // 원본 옆에 놓으려면 이것이 필요하다. 첫 조각은 원본 자신이라 들어 있지 않다.
        val outcome = ScenarioConflictSplit.apply(
            listOf(scenario(step(1)), scenario(step(2), step(3), step(5))), parity,
        )

        assertThat(outcome.scenarios).hasSize(3)
        assertThat(outcome.anchorOf).isEqualTo(mapOf(2 to 1))
    }

    @Test
    fun `나눌 것이 없으면 갈라진 자리도 없다`() {
        val outcome = ScenarioConflictSplit.apply(listOf(scenario(step(2), step(4))), parity)

        assertThat(outcome.anchorOf).isEmpty()
    }
}
