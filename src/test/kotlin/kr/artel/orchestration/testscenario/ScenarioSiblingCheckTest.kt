package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.service.Guard
import kr.artel.orchestration.testscenario.service.ScenarioSiblingCheck
import kr.artel.orchestration.testscenario.service.ScenarioSiblingCheck.CaseFact
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 같은 자리의 케이스들(ARTEL-466). 실측에서 가져온 무리로 고정한다 —
 * `StoryScene에 진입해 관찰한다` 가 셋이고 각각 `i < …` · `StagePosition != 5` · `== 5` 였다.
 *
 * **막는 것 하나, 말하는 것 둘**이라는 구분이 이 테스트의 요지다.
 */
class ScenarioSiblingCheckTest {

    private fun case(
        id: Long,
        step: String = "StoryScene에 진입해 관찰한다",
        scene: String? = "StoryScene",
        guards: List<Guard> = emptyList(),
        declared: Map<String, String> = emptyMap(),
    ) = CaseFact(id, scene, step, guards, declared)

    private val notFive = case(133, guards = listOf(Guard("StagePosition", "!=", "5")))
    private val five = case(
        134,
        guards = listOf(Guard("StagePosition", "==", "5")),
        declared = mapOf("StagePosition" to "5"),
    )
    private val early = case(132, guards = listOf(Guard("i", "<", "9")))

    @Test
    fun `동시에 성립할 수 없는 둘이 한 시나리오에 있으면 막는다`() {
        // 취향이 아니라 실행이 불가능하다 — 어느 쪽도 통과할 수 없는 시나리오가 된다.
        val findings = ScenarioSiblingCheck.analyze(
            listOf(notFive, five, early),
            split = listOf(listOf(133L, 134L)),
        )

        assertThat(findings.conflicting).containsExactly(133L to 134L)
    }

    @Test
    fun `배타적인 둘이 다른 시나리오에 있으면 아무 말도 하지 않는다`() {
        // 갈래가 갈린 것은 정상이다. 오히려 이것이 맞는 모양이다.
        val findings = ScenarioSiblingCheck.analyze(
            listOf(notFive, five, early),
            split = listOf(listOf(133L), listOf(134L)),
        )

        assertThat(findings.conflicting).isEmpty()
        assertThat(findings.splitApart).isEmpty()
    }

    @Test
    fun `동시에 성립하는 형제가 갈렸으면 말만 한다`() {
        // 런 107 에서 실제로 나온 모양이다. 막지 않는 이유는 일부러 나눴을 수 있기 때문이다.
        val findings = ScenarioSiblingCheck.analyze(
            listOf(notFive, five, early),
            split = listOf(listOf(132L), listOf(133L)),
        )

        assertThat(findings.splitApart).containsExactly(132L to 133L)
        assertThat(findings.conflicting).isEmpty()
    }

    @Test
    fun `한 갈래만 담겼으면 나머지 갈래를 알린다`() {
        val findings = ScenarioSiblingCheck.analyze(
            listOf(notFive, five, early),
            split = listOf(listOf(133L)),
        )

        assertThat(findings.untestedArms).containsExactly(133L to 134L)
    }

    @Test
    fun `안 담긴 형제라도 배타적이지 않으면 말하지 않는다`() {
        // 132 는 133 과 동시에 성립한다. 안 담겼다는 사실 자체는 판정(reviewed) 쪽 질문이지
        // 여기서 말할 것이 아니다 — 여기서까지 말하면 매 턴 같은 줄이 붙는다.
        val findings = ScenarioSiblingCheck.analyze(
            listOf(notFive, five, early),
            split = listOf(listOf(133L, 134L)),
        )

        assertThat(findings.untestedArms).isEmpty()
    }

    @Test
    fun `확정된 값이 없으면 배타적이라고 하지 않는다`() {
        // 둘 다 부등식만 말한다. 모르는 것을 충돌이라 부르면 멀쩡한 시나리오가 막힌다.
        val loose = case(150, guards = listOf(Guard("StagePosition", ">=", "1")))
        val looser = case(151, guards = listOf(Guard("StagePosition", "<=", "3")))

        val findings = ScenarioSiblingCheck.analyze(
            listOf(loose, looser),
            split = listOf(listOf(150L, 151L)),
        )

        assertThat(findings.conflicting).isEmpty()
    }

    @Test
    fun `스텝 문구가 다르면 형제가 아니다`() {
        val other = case(160, step = "StoryScene에서 Space 입력을 한다")

        val findings = ScenarioSiblingCheck.analyze(
            listOf(notFive, other),
            split = listOf(listOf(133L), listOf(160L)),
        )

        assertThat(findings.splitApart).isEmpty()
    }

    @Test
    fun `씬이 다르면 형제가 아니다`() {
        val elsewhere = case(170, scene = "Map_scene")

        val findings = ScenarioSiblingCheck.analyze(
            listOf(notFive, elsewhere),
            split = listOf(listOf(133L), listOf(170L)),
        )

        assertThat(findings.splitApart).isEmpty()
    }
}
