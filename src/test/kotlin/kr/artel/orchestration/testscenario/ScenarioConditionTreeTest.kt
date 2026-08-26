package kr.artel.orchestration.testscenario

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.testscenario.service.Guard
import kr.artel.orchestration.testscenario.service.ScenarioConditionTree
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 조건 트리를 저작이 쓰는 모양으로 읽는다(ARTEL-533).
 *
 * 트리는 **적재기가 실제로 채우는 유일한 조건 표현**이다. 여기서 잘못 읽으면 실제 지도에서
 * 사전조건이 통째로 사라지거나(빠뜨림) 없는 조건이 생긴다(지어냄). 둘 다 실행이 막히는
 * 시나리오로 이어지므로, 아래 사례는 실측 문서에서 실제로 나온 모양만 세운다.
 */
class ScenarioConditionTreeTest {

    private val mapper = ObjectMapper()

    private fun tree(json: String) = mapper.readTree(json)

    // --- 실측에서 나온 모양 ---------------------------------------------------------

    /**
     * `wv-editor-latest.json` 의 기능 63번. `MapMove.StagePosition` 을 올리는 유일한 코드이고,
     * `TurnBattleScene → GameClearScene` 간선이 타는 기능이기도 하다.
     */
    @Test
    fun `비교 하나를 그대로 읽는다`() {
        val node = tree(
            """{"kind":"test","left":"BattleWaveController.wave","operator":">=",
               "right":"BattleWaveController.battleScript.GetBattleWaveDatas().Count",
               "offset":216,"context":"this"}"""
        )

        assertThat(ScenarioConditionTree.guards(node)).containsExactly(
            // 변수 이름은 사전조건과 같은 규칙(마지막 마디)으로 맞춘다.
            Guard("wave", ">=", "BattleWaveController.battleScript.GetBattleWaveDatas().Count")
        )
        // **괄호가 잘리지 않는다.** 문자열을 쪼개던 예전 경로에서는 `GetBattleWaveDatas` 로 끝나
        // 웨이브 수가 메서드 이름이 됐다.
        assertThat(ScenarioConditionTree.text(node))
            .isEqualTo("BattleWaveController.wave >= BattleWaveController.battleScript.GetBattleWaveDatas().Count")
    }

    @Test
    fun `every 는 모두 필요하므로 합집합이다`() {
        val node = tree(
            """{"kind":"every","parts":[
                 {"kind":"test","left":"InteractionLock.IsLocked","operator":"==","right":"0","context":"static"},
                 {"kind":"test","left":"CardManager.selectCard","operator":"!=","right":"0","context":"static"}]}"""
        )

        assertThat(ScenarioConditionTree.guards(node)).containsExactly(
            Guard("IsLocked", "==", "0"), Guard("selectCard", "!=", "0"),
        )
        assertThat(ScenarioConditionTree.text(node)).contains("그리고")
    }

    @Test
    fun `either 는 하나면 되므로 공통인 것만 확실하다`() {
        val node = tree(
            """{"kind":"either","parts":[
                 {"kind":"every","parts":[
                   {"kind":"test","left":"InteractionLock.IsLocked","operator":"==","right":"0","context":"static"},
                   {"kind":"test","left":"MapMove.position","operator":"==","right":"0","context":"static"}]},
                 {"kind":"every","parts":[
                   {"kind":"test","left":"InteractionLock.IsLocked","operator":"==","right":"0","context":"static"},
                   {"kind":"test","left":"MapMove.position","operator":"==","right":"1","context":"static"}]}]}"""
        )

        // 어느 가지로 가든 잠금은 풀려 있어야 하지만, position 은 가지마다 다르므로 단정할 수 없다.
        assertThat(ScenarioConditionTree.guards(node)).containsExactly(Guard("IsLocked", "==", "0"))
        assertThat(ScenarioConditionTree.text(node)).contains("또는")
    }

    /** 실측 8건이 `every` 를 대문자로 담고 있다. 잘못 읽으면 그 조건이 통째로 사라진다. */
    @Test
    fun `대문자 EVERY 도 같은 것으로 읽는다`() {
        val node = tree(
            """{"kind":"EVERY","parts":[
                 {"kind":"test","left":"Player.Hp","operator":">","right":"0","context":"this"}]}"""
        )

        assertThat(ScenarioConditionTree.guards(node)).containsExactly(Guard("Hp", ">", "0"))
    }

    // --- 단정하지 않는 자리 ---------------------------------------------------------

    /**
     * `context` 가 null 인 비교는 주어를 못 찾은 것이다(실측 47건).
     *
     * `i < objCount` 의 `i` 를 게임 상태로 읽으면 없는 사전조건이 생기고, 그 자리는 영영 못 가는
     * 길이 된다. 사람에게는 보여 주되 코드가 근거로 쓰지 않는다.
     */
    @Test
    fun `주어를 못 찾은 비교는 판단에 쓰지 않는다`() {
        val node = tree(
            """{"kind":"test","left":"i","operator":"<","right":"objCount",
               "offset":123,"context":null,"subjectLost":"left:ldloc.s"}"""
        )

        assertThat(ScenarioConditionTree.guards(node)).isEmpty()
        assertThat(ScenarioConditionTree.text(node)).isEqualTo("i < objCount")
    }

    @Test
    fun `조건 없음은 빈 목록이다`() {
        assertThat(ScenarioConditionTree.guards(tree("""{"kind":"always"}"""))).isEmpty()
        assertThat(ScenarioConditionTree.text(tree("""{"kind":"always"}"""))).isNull()
    }

    @Test
    fun `입력 조건은 값 비교가 아니다`() {
        val node = tree("""{"kind":"gesture","input":"key:Return (down)","offset":704}""")

        // 상태가 아니라 사람이 하는 일이다. 값으로 세면 없는 변수가 사전조건이 된다.
        assertThat(ScenarioConditionTree.guards(node)).isEmpty()
        assertThat(ScenarioConditionTree.text(node)).isEqualTo("key:Return (down)")
    }

    @Test
    fun `못 읽은 조건이 섞였는지 말해 준다`() {
        val node = tree(
            """{"kind":"every","parts":[
                 {"kind":"test","left":"Player.Hp","operator":">","right":"0","context":"this"},
                 {"kind":"unknown","reason":"unread-branch","unread":"brtrue.s"}]}"""
        )

        assertThat(ScenarioConditionTree.incomplete(node)).isTrue()
        // 읽을 수 있는 것은 그대로 쓴다 — `every` 라 그 비교는 어차피 성립해야 한다.
        assertThat(ScenarioConditionTree.guards(node)).containsExactly(Guard("Hp", ">", "0"))
    }

    @Test
    fun `트리가 없으면 빈 목록이다`() {
        assertThat(ScenarioConditionTree.guards(null)).isEmpty()
        assertThat(ScenarioConditionTree.text(null)).isNull()
        assertThat(ScenarioConditionTree.incomplete(null)).isFalse()
    }
}
