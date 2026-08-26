package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.service.Guard
import kr.artel.orchestration.testscenario.service.ScenarioReachabilityCheck
import kr.artel.orchestration.testscenario.service.ScenarioReachabilityCheck.CaseFact
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource
import kr.artel.orchestration.testscenario.service.SceneMove
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 실행이 도중에 막히는 시나리오를 찾는다(ARTEL-528).
 *
 * 기준은 실측이다 — 런 155 에서 시나리오 22개 중 4개가 실행 불가였고 막힌 자리가 10군데였다.
 * 네 가지 모양이었고, 아래 테스트가 그 넷을 하나씩 세운다.
 */
class ScenarioReachabilityCheckTest {

    private fun step(caseId: Long?) = ChatScenarioStep(action = "확인", caseId = caseId)

    /** 지도가 아는 조작(코드가 끼워 넣는 브리지가 이 모양이다). */
    private fun bridge() = ChatScenarioStep(
        action = "누른다", caseId = null, stepSource = ScenarioStepSource.CAPABILITY,
    )

    private fun scenario(vararg caseIds: Long?) = ScenarioResult(
        title = "t", description = "d", steps = caseIds.map(::step),
    )

    private fun scenarioOf(vararg steps: ChatScenarioStep) =
        ScenarioResult(title = "t", description = "d", steps = steps.toList())

    private fun case(
        scene: String?,
        moves: SceneMove = SceneMove.Stays,
        requires: List<Guard> = emptyList(),
        declares: Map<String, String> = emptyMap(),
        leaves: Map<String, String> = emptyMap(),
    ) = CaseFact(scene, moves, requires, declares, leaves)

    private fun analyze(scenario: ScenarioResult, facts: Map<Long, CaseFact>) =
        ScenarioReachabilityCheck.analyze(listOf(scenario), facts)

    // --- 실측 넷 -------------------------------------------------------------------

    /** 런 155 시나리오 289 — `Return` 으로 전투에 들어간 뒤 맵 화면을 요구한다. 복귀 스텝이 없다. */
    @Test
    fun `화면을 떠난 뒤 그 화면을 다시 요구하면 막힌다`() {
        val blocked = analyze(
            scenario(1L, 2L),
            mapOf(
                1L to case("Map_scene", moves = SceneMove.To("TurnBattleScene")),
                2L to case("Map_scene"),
            ),
        )

        assertThat(blocked).hasSize(1)
        assertThat(blocked.single().stepIndex).isEqualTo(1)
        assertThat(blocked.single().reason).contains("TurnBattleScene").contains("Map_scene")
    }

    /** 런 155 시나리오 293 — 사망으로 게임오버에 간 뒤 전투 화면 케이스가 셋 이어진다. */
    @Test
    fun `사이의 준비 스텝이 복귀를 대신하지 못한다`() {
        val blocked = analyze(
            scenario(1L, null, 2L),
            mapOf(
                1L to case("TurnBattleScene", moves = SceneMove.To("GameOverScene")),
                2L to case("TurnBattleScene"),
            ),
        )

        // 케이스 없는 스텝은 무엇을 했는지 모른다 — 화면을 되돌렸다고 쳐 주지 않는다.
        assertThat(blocked).hasSize(1)
        assertThat(blocked.single().stepIndex).isEqualTo(2)
    }

    /** 런 155 시나리오 300·301 — 한 케이스의 검증 구간에 다른 케이스가 끼었다. */
    @Test
    fun `검증 구간에 다른 케이스가 끼면 말한다`() {
        val blocked = analyze(
            scenario(1L, 2L, 1L),
            mapOf(
                1L to case("EndingScene", moves = SceneMove.To("TitleScene")),
                2L to case("EndingScene"),
            ),
        )

        assertThat(blocked.map { it.stepIndex }).contains(0)
        assertThat(blocked.first { it.stepIndex == 0 }.reason).contains("검증 구간")
    }

    @Test
    fun `요구하는 값이 이미 어긋났으면 막힌다`() {
        val blocked = analyze(
            scenario(1L, 2L),
            mapOf(
                1L to case("Map_scene", leaves = mapOf("position" to "5")),
                2L to case("Map_scene", requires = listOf(Guard("position", "==", "4"))),
            ),
        )

        assertThat(blocked).hasSize(1)
        assertThat(blocked.single().reason).contains("position").contains("5")
    }

    // --- 조용해야 하는 자리 ---------------------------------------------------------

    @Test
    fun `한 케이스를 여러 스텝으로 나눠 봐도 막히지 않는다`() {
        // "하기"와 "확인하기"가 한 케이스의 검증 구간으로 나뉜 것이다. 화면은 구간의 끝에서 넘어간다.
        val blocked = analyze(
            scenario(1L, 1L),
            mapOf(1L to case("StoryScene", moves = SceneMove.To("Map_scene"))),
        )

        assertThat(blocked).isEmpty()
    }

    @Test
    fun `화면을 넘긴 뒤 그 화면의 케이스가 오면 막히지 않는다`() {
        val blocked = analyze(
            scenario(1L, 2L),
            mapOf(
                1L to case("StoryScene", moves = SceneMove.To("Map_scene")),
                2L to case("Map_scene"),
            ),
        )

        assertThat(blocked).isEmpty()
    }

    @Test
    fun `케이스 없는 스텝이 값을 바꿨을 수 있으므로 그 뒤는 따지지 않는다`() {
        // 브리지가 무엇을 만들었는지 모른다. 놓치는 쪽으로 기울지, 없는 막힘을 만들지는 않는다.
        val blocked = analyze(
            scenario(1L, null, 2L),
            mapOf(
                1L to case("Map_scene", leaves = mapOf("position" to "5")),
                2L to case("Map_scene", requires = listOf(Guard("position", "==", "4"))),
            ),
        )

        assertThat(blocked).isEmpty()
    }

    @Test
    fun `어디로 가는지 모르면 그 뒤를 판정하지 않는다`() {
        // 모르면서 머문다고 하면 없는 확신을 만드는 것이고, 떠난다고 해도 어디로 갈지 모른다.
        val blocked = analyze(
            scenario(1L, 2L),
            mapOf(
                1L to case("EndingScene", moves = SceneMove.Unknown),
                2L to case("Map_scene"),
            ),
        )

        assertThat(blocked).isEmpty()
    }

    @Test
    fun `모르는 케이스는 건드리지 않는다`() {
        val blocked = analyze(scenario(9001L, 9002L), emptyMap())

        assertThat(blocked).isEmpty()
    }

    @Test
    fun `시나리오가 여럿이면 각자 처음부터 굴린다`() {
        // 앞 시나리오가 남긴 화면을 다음 시나리오가 물려받으면, 따로 실행되는 것을 이어 붙이는 셈이다.
        val facts = mapOf(
            1L to case("Map_scene", moves = SceneMove.To("TurnBattleScene")),
            2L to case("Map_scene"),
        )

        val blocked = ScenarioReachabilityCheck.analyze(
            listOf(scenario(1L), scenario(2L)), facts,
        )

        assertThat(blocked).isEmpty()
    }

    @Test
    fun `지도가 아는 조작이 화면을 넘겼을 수 있다`() {
        // 코드가 스스로 끼워 넣은 씬 이동 브리지가 이 모양이다. 이것을 막힘이라 부르면 이 검사는
        // 자기가 고친 자리를 지적하는 셈이 된다.
        val blocked = ScenarioReachabilityCheck.analyze(
            listOf(scenarioOf(step(1L), bridge(), step(2L))),
            mapOf(
                1L to case("Map_scene"),
                2L to case("TurnBattleScene"),
            ),
        )

        assertThat(blocked).isEmpty()
    }
}
