package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.service.Guard
import kr.artel.orchestration.testscenario.service.ScenarioSiblingLabel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 같은 문구의 스텝이 서로 다른 케이스를 볼 때 무엇이 다른지 붙인다.
 *
 * 기준은 실측이다(런 152, TS 247) — 2번과 7번이 글자까지 같은 줄이었고, 실행하는 사람은 중복인지
 * 아닌지 알 방법이 없었다.
 */
class ScenarioSiblingLabelTest {

    private val guards = mapOf(
        1297L to listOf(
            Guard("tag", "!=", "\"Enemy\"", "collision.tag"),
            Guard("tag", "==", "SpellObj.target.gameObject.tag", "collision.tag"),
            Guard("hp", ">", "0", "Player.hp"),
        ),
        1301L to listOf(Guard("damage", ">", "0", "damage"), Guard("hp", ">", "0", "Player.hp")),
    )
    private val guardsOf: (Long) -> List<Guard> = { id -> guards[id].orEmpty() }

    private fun scenario(vararg steps: ChatScenarioStep) =
        ScenarioResult(title = "전투", description = "d", steps = steps.toList())

    private val collide = "TurnBattleScene의 SpellObj에서 OnTriggerEnter2D 충돌이 발생한다."

    private fun apply(vararg steps: ChatScenarioStep) =
        ScenarioSiblingLabel.apply(listOf(scenario(*steps)), guardsOf).single().steps.map { it.action }

    @Test
    fun `같은 문구가 다른 케이스를 보면 다른 부분만 붙인다`() {
        val actions = apply(
            ChatScenarioStep(action = collide, caseId = 1297),
            ChatScenarioStep(action = collide, caseId = 1301),
        )

        // 공통인 `hp > 0` 은 붙지 않는다 — 붙이면 두 줄이 다시 길고 비슷해져 가르는 데 도움이 안 된다.
        assertThat(actions[0]).isEqualTo("$collide (tag != \"Enemy\")")
        assertThat(actions[1]).isEqualTo("$collide (damage > 0)")
    }

    @Test
    fun `값을 모르는 비교는 라벨에 쓰지 않는다`() {
        // `tag == SpellObj.target.gameObject.tag` 는 읽는 사람에게 무엇이 다른지 말해 주지 않는다.
        val actions = apply(
            ChatScenarioStep(action = collide, caseId = 1297),
            ChatScenarioStep(action = collide, caseId = 1301),
        )

        assertThat(actions[0]).doesNotContain("SpellObj.target")
    }

    @Test
    fun `같은 케이스의 검증 구간은 그대로 둔다`() {
        // "하기"와 "확인하기"가 한 케이스로 나뉜 것이라 가를 것이 없다.
        val actions = apply(
            ChatScenarioStep(action = collide, caseId = 1301),
            ChatScenarioStep(action = collide, caseId = 1301),
        )

        assertThat(actions).containsExactly(collide, collide)
    }

    @Test
    fun `문구가 다르면 붙이지 않는다`() {
        val actions = apply(
            ChatScenarioStep(action = collide, caseId = 1297),
            ChatScenarioStep(action = "적 투사체 충돌을 발생시킨다.", caseId = 1301),
        )

        assertThat(actions).containsExactly(collide, "적 투사체 충돌을 발생시킨다.")
    }

    @Test
    fun `두 번 돌려도 라벨이 늘지 않는다`() {
        // 모델은 지난 턴의 시나리오를 그대로 돌려받는다. 멱등하지 않으면 턴마다 라벨이 붙는다.
        val once = ScenarioSiblingLabel.apply(
            listOf(
                scenario(
                    ChatScenarioStep(action = collide, caseId = 1297),
                    ChatScenarioStep(action = collide, caseId = 1301),
                )
            ),
            guardsOf,
        )
        val twice = ScenarioSiblingLabel.apply(once, guardsOf)

        assertThat(twice.single().steps.map { it.action })
            .isEqualTo(once.single().steps.map { it.action })
    }

    @Test
    fun `모르는 케이스는 건드리지 않는다`() {
        // 케이스 조회가 터진 턴이다. 가를 근거가 없으면 원문 그대로 흘려보낸다.
        val actions = apply(
            ChatScenarioStep(action = collide, caseId = 9001),
            ChatScenarioStep(action = collide, caseId = 9002),
        )

        assertThat(actions).containsExactly(collide, collide)
    }
}
