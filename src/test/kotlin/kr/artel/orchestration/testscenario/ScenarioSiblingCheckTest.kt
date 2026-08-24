package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.service.Guard
import kr.artel.orchestration.testscenario.service.ScenarioSiblingCheck
import kr.artel.orchestration.testscenario.service.ScenarioStateReader
import kr.artel.orchestration.testscenario.service.ScenarioSiblingCheck.CaseFact
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 같은 자리의 케이스들(ARTEL-466). 실측에서 가져온 무리로 고정한다 —
 * `StoryScene에 진입해 관찰한다` 가 셋이고 각각 `i < …` · `StagePosition != 5` · `== 5` 였다.
 *
 * 세는 것은 **배타성 하나뿐**이라는 것이 이 테스트의 요지다. 그 위의 판단(막을지·나눌지)은
 * 부르는 쪽 몫이고, 지금은 셋 다 막지 않는다(ARTEL-497).
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
    fun `동시에 성립할 수 없는 둘이 한 시나리오에 있으면 센다`() {
        // 실행이 불가능한 조합이다. 세는 것까지가 여기 몫이고, 막을지 물을지는 부르는 쪽이 정한다.
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

    // --- 범위끼리의 배타 (ARTEL-497) ------------------------------------------------

    @Test
    fun `부등식끼리도 겹치는 값이 없으면 배타다`() {
        // word-venture TurnBattleScene 24건에서 실제로 나온 모양이다. 사망(hp <= 0) 2건과
        // 생존(hp > 0) 8건이 한 시나리오에 담겼는데, 양쪽 다 확정값이 없어 16쌍이 통과했다.
        val dead = case(1284, step = "EnemyProjectile 충돌", guards = listOf(Guard("hp", "<=", "0", "Player.hp")))
        val alive = case(1293, step = "TakeHit 이후 관찰", guards = listOf(Guard("hp", ">", "0", "Player.hp")))

        val findings = ScenarioSiblingCheck.analyze(listOf(dead, alive), split = listOf(listOf(1284L, 1293L)))

        assertThat(findings.conflicting).containsExactly(1284L to 1293L)
    }

    @Test
    fun `같은 값을 다른 경로로 불러도 같은 변수로 본다`() {
        // `Player.hp` 와 `Player.PlayerInt().hp` 는 같은 값이다. 사전조건 파서가 뒤엣것에서
        // `hp` 만 건져 오므로, 경로가 서로의 꼬리이면 같은 변수로 맞춘다.
        val dead = case(1284, guards = listOf(Guard("hp", "<=", "0", "Player.hp")))
        val alive = case(1300, guards = listOf(Guard("hp", ">", "0", "hp")))

        val findings = ScenarioSiblingCheck.analyze(listOf(dead, alive), split = listOf(listOf(1284L, 1300L)))

        assertThat(findings.conflicting).containsExactly(1284L to 1300L)
    }

    @Test
    fun `마디만 겹치는 다른 변수는 충돌이 아니다`() {
        // `magicTypeCards.Count` 와 `spellCards.Count` 는 둘 다 `Count` 지만 다른 값이다.
        // 뭉개면 멀쩡한 시나리오에 대고 잘못된 질문을 하게 된다.
        val one = case(1296, guards = listOf(Guard("Count", "==", "1", "magicTypeCards.Count")))
        val two = case(1287, guards = listOf(Guard("Count", "==", "2", "spellCards.Count")))

        val findings = ScenarioSiblingCheck.analyze(listOf(one, two), split = listOf(listOf(1296L, 1287L)))

        assertThat(findings.conflicting).isEmpty()
    }

    @Test
    fun `구간이 한 점에서라도 겹치면 배타가 아니다`() {
        val atLeast = case(1, guards = listOf(Guard("wave", ">=", "1", "wave")))
        val atMost = case(2, guards = listOf(Guard("wave", "<=", "1", "wave")))

        val findings = ScenarioSiblingCheck.analyze(listOf(atLeast, atMost), split = listOf(listOf(1L, 2L)))

        assertThat(findings.conflicting).isEmpty()
    }

    @Test
    fun `비교할 수 없는 값은 충돌이라 부르지 않는다`() {
        // 문자열에 부등식을 쓴 사전조건이 무슨 뜻인지 코드는 모른다. 모르는 것을 충돌이라 부르면
        // 멀쩡한 요청이 잘못된 질문을 받는다.
        val a = case(1, guards = listOf(Guard("tag", ">", "Enemy", "collision.tag")))
        val b = case(2, guards = listOf(Guard("tag", "<", "Enemy", "collision.tag")))

        val findings = ScenarioSiblingCheck.analyze(listOf(a, b), split = listOf(listOf(1L, 2L)))

        assertThat(findings.conflicting).isEmpty()
    }

    @Test
    fun `같은 점을 빼는 것과 그 점을 요구하는 것은 배타다`() {
        val not5 = case(1, guards = listOf(Guard("StagePosition", "!=", "5", "StagePosition")))
        val is5 = case(2, guards = listOf(Guard("StagePosition", "==", "5", "StagePosition")))

        val findings = ScenarioSiblingCheck.analyze(listOf(not5, is5), split = listOf(listOf(1L, 2L)))

        assertThat(findings.conflicting).containsExactly(1L to 2L)
    }

    @Test
    fun `그 밖의 값을 빼는 것은 배타가 아니다`() {
        val not5 = case(1, guards = listOf(Guard("StagePosition", "!=", "5", "StagePosition")))
        val over10 = case(2, guards = listOf(Guard("StagePosition", ">", "10", "StagePosition")))

        val findings = ScenarioSiblingCheck.analyze(listOf(not5, over10), split = listOf(listOf(1L, 2L)))

        assertThat(findings.conflicting).isEmpty()
    }

    // --- 실측 회귀: 런 152 TurnBattleScene (ARTEL-497) ------------------------------

    /**
     * 사전조건 글자 그대로 읽어 만든다. [Guard] 를 손으로 적으면 읽개의 잘못이 테스트를 통과한다 —
     * 실제로 런 152 에서 틀린 것이 읽개였다.
     */
    private fun real(id: Long, step: String, precondition: String) = CaseFact(
        id = id,
        scene = "TurnBattleScene",
        step = step,
        guards = ScenarioStateReader.guardsOf(precondition),
        declared = ScenarioStateReader.knownValuesOf(precondition),
    )

    @Test
    fun `갈래가 있는 사전조건은 함께 담을 수 있다`() {
        // 런 152 에서 "함께 담을 수 없다"고 판정된 19쌍 중 이 여섯 건이 만든 것이 대부분이었다.
        // 전부 `Player.hp > 0`(생존 중)이라 한 번의 실행으로 다 볼 수 있는 케이스들이다.
        val cases = listOf(
            real(1293, "TakeHit 이후 관찰한다", "TurnBattleScene 화면인 상태 / (damage > 0 그리고 Player.hp > 0)"),
            real(
                1297, "SpellObj 충돌",
                "TurnBattleScene 화면인 상태 / (collision.tag != \"Enemy\" 그리고 " +
                    "collision.tag == SpellObj.target.gameObject.tag 그리고 " +
                    "((damage > 0 그리고 Player.hp > 0) 또는 (damage <= 0 그리고 Player.hp > 0)))",
            ),
            real(
                1298, "EnemyProjectile 충돌",
                "TurnBattleScene 화면인 상태 / (collision.tag == \"Me\" 그리고 " +
                    "((damage > 0 그리고 Player.hp > 0) 또는 (damage <= 0 그리고 Player.hp > 0)))",
            ),
            real(
                1299, "TakeHit 이후 hpText 관찰",
                "TurnBattleScene 화면인 상태 / " +
                    "((damage > 0 그리고 Player.hp > 0) 또는 (damage <= 0 그리고 Player.hp > 0))",
            ),
            real(1301, "SpellObj 충돌", "TurnBattleScene 화면인 상태 / (damage > 0 그리고 Player.hp > 0)"),
            real(
                1302, "Attack 이후 관찰한다",
                "TurnBattleScene 화면인 상태 / (Enemy.damage > 0 그리고 Player.PlayerInt().hp > 0)",
            ),
        )

        val findings = ScenarioSiblingCheck.analyze(cases, split = listOf(cases.map { it.id }))

        assertThat(findings.conflicting).isEmpty()
    }

    @Test
    fun `생존과 사망은 여전히 함께 담을 수 없다`() {
        // 갈래를 읽게 했다고 진짜 충돌까지 놓치면 아무것도 얻지 못한 것이다.
        val alive = real(1293, "TakeHit 이후 관찰한다", "TurnBattleScene 화면인 상태 / (damage > 0 그리고 Player.hp > 0)")
        val dead = real(1284, "EnemyProjectile 충돌", "TurnBattleScene 화면인 상태 / (collision.tag == \"Me\" 그리고 Player.hp <= 0)")

        val findings = ScenarioSiblingCheck.analyze(listOf(alive, dead), split = listOf(listOf(1293L, 1284L)))

        assertThat(findings.conflicting).containsExactly(1284L to 1293L)
    }
}
