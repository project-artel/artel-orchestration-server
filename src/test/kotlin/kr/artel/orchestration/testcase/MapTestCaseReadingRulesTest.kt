package kr.artel.orchestration.testcase

import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.GroupKind
import kr.artel.orchestration.testcase.generator.MapTestCase
import kr.artel.orchestration.testcase.generator.MapTestCaseCheck
import kr.artel.orchestration.testcase.generator.MapTestCaseReadingRules
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 판독 기준으로 TC 를 다시 묶는 규칙(ARTEL-1209). 입력은 word-venture 1.0 · 1.4.0 에서 실제로 나온 모양이다. */
class MapTestCaseReadingRulesTest {

    private fun test(left: String, op: String, right: String, readable: Boolean = true) =
        ConditionNode.Test(left = left, operator = op, right = right, context = "this", offset = 0, readable = readable)

    private fun every(vararg parts: ConditionNode) = ConditionNode.Group(GroupKind.EVERY, parts.toList())

    @Test
    fun `한 값이 둘일 수 없는 사전조건은 성립하지 않는다`() {
        assertThat(MapTestCaseReadingRules.impossible(every(test("MemberView.serverSave", "!=", "0"), test("MemberView.serverSave", "==", "0")), "MapScene")).isTrue()
        assertThat(MapTestCaseReadingRules.impossible(every(test("MapMove.position", "==", "1"), test("MapMove.position", "==", "2")), "MapScene")).isTrue()
        assertThat(MapTestCaseReadingRules.impossible(every(test("MapMove.position", "==", "1"), test("MapMove.position", "!=", "2")), "MapScene")).isFalse()
    }

    @Test
    fun `다른 씬이어야 성립하는 사전조건은 성립하지 않는다`() {
        val other = test("SceneManager.GetActiveScene().name", "==", "\"GameClearScene\"")
        assertThat(MapTestCaseReadingRules.impossible(other, "MapScene")).isTrue()
        assertThat(MapTestCaseReadingRules.impossible(other, "GameClearScene")).isFalse()
    }

    @Test
    fun `또는 안의 모순은 한쪽 갈래일 뿐이라 따지지 않는다`() {
        val either = ConditionNode.Group(GroupKind.EITHER, listOf(test("A.b", "==", "0"), test("A.b", "!=", "0")))
        assertThat(MapTestCaseReadingRules.impossible(either, "S")).isFalse()
    }

    @Test
    fun `판독이 못 보는 비교는 지우고 지운 것을 서명으로 남긴다`() {
        val result = MapTestCaseReadingRules.readable(
            every(test("InteractionLock.IsLocked", "==", "0"), test("TitleSceneManager.saveLoadController.LoadPlayData()", "==", "-1", readable = false)),
        )

        assertThat(result.condition).isEqualTo(test("InteractionLock.IsLocked", "==", "0"))
        assertThat(result.removed).contains("LoadPlayData()")
    }

    @Test
    fun `기대 항목은 씬 전환 · 게임 타입 멤버 · 지도가 아는 경로일 때만 판독으로 확인된다`() {
        fun check(kind: String, lookAt: String?, name: String) = MapTestCaseCheck(kind, lookAt, "x", null, name)
        val names = setOf("Canvas/ChatWindow/AnyKeyPrompt", "Background1", "Background2")

        assertThat(MapTestCaseReadingRules.collectable(check("scene", null, "MapScene"), emptySet())).isTrue()
        assertThat(MapTestCaseReadingRules.collectable(check("ui-value", "ChatWindowController.chatName", "ChatWindowController.chatName.text"), emptySet())).isTrue()
        assertThat(MapTestCaseReadingRules.collectable(check("active-state", null, "Canvas/ChatWindow/AnyKeyPrompt"), names)).isTrue()
        assertThat(MapTestCaseReadingRules.collectable(check("active-state", null, "`Background1` · `Background2`"), names)).isTrue()
        // 타입 이름이나 호출식은 어느 것에도 들지 않는다. 이름을 목록으로 들지 않고 이 규칙으로 갈린다.
        assertThat(MapTestCaseReadingRules.collectable(check("transform", null, "DraggableCard"), names)).isFalse()
        assertThat(MapTestCaseReadingRules.collectable(check("ui-value", null, "GameObject.GetComponentInChildren().text"), names)).isFalse()
    }

    @Test
    fun `씬만 다른 같은 TC 는 입구 씬 하나로 낸다`() {
        fun case(scene: String) = MapTestCase(
            capabilityKey = "k-$scene", scene = scene, precondition = "$scene 화면인 상태", step = "`Escape` 키를 눌러 `SettingsMenu/Menu` 을(를) 켠다",
            expected = "`SettingsMenu/Menu` 의 표시 상태가 `true`", status = "runnable", identity = "id-$scene",
        )

        val out = MapTestCaseReadingRules.acrossScenes(listOf(case("EndingScene"), case("TitleScene"), case("MapScene")), entryScene = "TitleScene")

        assertThat(out).hasSize(1)
        assertThat(out.single().scene).isEqualTo("TitleScene")
        assertThat(out.single().step).endsWith(MapTestCaseReadingRules.ANY_SCENE)
    }
}
