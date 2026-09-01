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

    /** 짝수끼리·홀수끼리는 되고, 짝수와 홀수는 `parity` 라는 값을 두고 어긋난다. */
    private val parity: (Long, Long) -> Set<String> =
        { a, b -> if ((a % 2) != (b % 2)) setOf("parity") else emptySet() }

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
        val exclusive: (Long, Long) -> Set<String> = { a, b -> if (a != b) setOf("모두") else emptySet() }
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

    // --- 순서를 안다 (ARTEL-581) -------------------------------------------------------

    /**
     * **앞이 바꿔 놓은 값을 뒤가 전제로 삼는 것은 모순이 아니다.**
     *
     * 실측(런 159)에서 이것이 없어 걸어가는 시나리오가 네 조각이 났다:
     *
     * ```
     * position == 0 인 자리에서 오른쪽 → position 이 1이 된다
     * position == 1 인 자리에서 오른쪽 → position 이 2가 된다
     * ```
     *
     * 두 전제는 한 순간에는 함께 설 수 없다. 그런데 앞엣것이 뒤엣것의 자리를 만든다.
     */
    @Test
    fun `앞 스텝이 바꾸는 값이면 나누지 않는다`() {
        val walking = scenario(step(2), step(3), step(4), title = "걸어간다")

        val outcome = ScenarioConflictSplit.apply(listOf(walking), parity) { setOf("parity") }

        assertThat(outcome.scenarios).isEqualTo(listOf(walking))
        assertThat(outcome.notes).isEmpty()
    }

    /**
     * **다른 값을 바꾸는 것은 도움이 안 된다.** 어긋난 값 그 자체가 바뀌어야 이어서 설 수 있다.
     * 그러지 않으면 아무 효과나 하나 있으면 무엇이든 합쳐지고, 나누는 일 자체가 무의미해진다.
     */
    @Test
    fun `어긋난 값이 아닌 다른 값을 바꾸면 그대로 나눈다`() {
        val outcome = ScenarioConflictSplit.apply(
            listOf(scenario(step(2), step(3))), parity,
        ) { setOf("전혀 다른 값") }

        assertThat(outcome.scenarios).hasSize(2)
    }

    /** 바꾸는 것을 아무것도 모르면(지도를 못 되짚는 구버전 케이스) 예전처럼 나눈다. */
    @Test
    fun `바꾸는 값을 모르면 예전처럼 나눈다`() {
        val outcome = ScenarioConflictSplit.apply(listOf(scenario(step(2), step(3))), parity)

        assertThat(outcome.scenarios).hasSize(2)
    }

    /**
     * **순서가 뒤집힌 자리는 여전히 막는다.**
     *
     * 뒤엣것이 앞엣것의 전제를 만드는 경우다. 그것은 나눌 일이 아니라 순서를 고칠 일이고,
     * `ScenarioOrderCheck` 가 그 자리를 따로 답한다. 여기서 합쳐 버리면 실행할 수 없는 순서가
     * 아무 말 없이 저장된다.
     */
    @Test
    fun `뒤엣것만 값을 바꾸면 나눈다`() {
        val outcome = ScenarioConflictSplit.apply(
            listOf(scenario(step(2), step(3))), parity,
        ) { id -> if (id == 3L) setOf("parity") else emptySet() }

        assertThat(outcome.scenarios).hasSize(2)
    }

    /** 지도가 부르는 이름과 사전조건이 적은 이름이 꼬리로 만나면 같은 값이다. */
    @Test
    fun `값 이름은 꼬리로 견준다`() {
        val outcome = ScenarioConflictSplit.apply(
            listOf(scenario(step(2), step(3))),
            { _, _ -> setOf("position") },
        ) { setOf("MapMove.position") }

        assertThat(outcome.scenarios).hasSize(1)
    }
}
