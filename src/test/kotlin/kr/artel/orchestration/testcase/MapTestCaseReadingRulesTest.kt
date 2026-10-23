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

    private fun either(vararg parts: ConditionNode) = ConditionNode.Group(GroupKind.EITHER, parts.toList())

    /** 실측 1.4.0 `GameClearController.Update`: `flag == 0` 아래에서 부른 메서드의 조건 트리가 `flag != 0` 갈래까지 들고 왔다. */
    @Test
    fun `바깥 조건과 부딪히는 또는 갈래를 뺀다`() {
        val tree = every(
            test("GameClearController.flag", "==", "0"),
            either(every(test("GameClearController.flag", "!=", "0"), test("InteractionLock.IsLocked", "==", "0")), test("InteractionLock.IsLocked", "==", "0")),
        )

        val out = MapTestCaseReadingRules.simplified(tree)

        assertThat(out.impossible).isFalse()
        assertThat(out.condition).isEqualTo(every(test("GameClearController.flag", "==", "0"), test("InteractionLock.IsLocked", "==", "0")))
    }

    @Test
    fun `갈래가 다 부딪히면 갈 수 없는 길이다`() {
        val tree = every(test("A.b", "==", "0"), either(test("A.b", "==", "1"), test("A.b", "==", "2")))
        assertThat(MapTestCaseReadingRules.simplified(tree).impossible).isTrue()
    }

    @Test
    fun `같은 덩어리는 한 번만 쓴다`() {
        val arm = either(test("A.b", "==", "1"), test("A.c", "==", "1"))
        assertThat(MapTestCaseReadingRules.simplified(every(arm, arm)).condition).isEqualTo(arm)
    }

    @Test
    fun `효과가 쓰고 조건이 읽는 값이 토글이다`() {
        val tree = every(test("GameClearController.flag", "==", "0"), test("InteractionLock.IsLocked", "==", "0"))
        assertThat(MapTestCaseReadingRules.toggles(tree, setOf("GameClearController.flag", "Player.Hp"))).containsExactly("GameClearController.flag")
    }

    @Test
    fun `값 나열은 그중 하나로 줄여 적는다`() {
        val list = either(*(0..4).map { test("StageDataSingleton.stagePosition", "==", "$it") }.toTypedArray())
        assertThat(kr.artel.orchestration.testcase.generator.MapTestCasePhrasing.precondition("MapScene", list, compactValues = true))
            .isEqualTo("MapScene 화면인 상태 / StageDataSingleton.stagePosition 가 0 · 1 · 2 · 3 · 4 중 하나")
    }

    /** 실측 1.4.0: TitleScene 의 `Escape` 키 TC 에 MapScene 에만 있는 `MapMove` 의 조건이 붙었다. */
    @Test
    fun `그 씬에 없는 오브젝트를 요구하는 갈래는 그 씬에서 갈 수 없다`() {
        val here = setOf("SettingsMenu", "TitleSceneManager")
        val anywhere = here + "MapMove"
        val lock = ConditionNode.Test(left = "InteractionLock.IsLocked", operator = "==", right = "0", context = "static", offset = 0)
        val open = test("SettingsMenu.IsOpen", "!=", "0")

        // `그리고` 로 걸리면 갈래째 갈 수 없다.
        val all = MapTestCaseReadingRules.simplified(MapTestCaseReadingRules.inScene(every(test("MapMove.movement", "==", "0"), lock), here, anywhere))
        assertThat(all.impossible).isTrue()
        // `또는` 의 한쪽이면 그 쪽만 빠진다. 어느 씬에도 안 놓인 타입(`TutorialController`)은 막지 않는다.
        val one = MapTestCaseReadingRules.simplified(
            MapTestCaseReadingRules.inScene(every(open, either(test("MapMove.movement", "==", "0"), test("TutorialController.finished", "==", "0"))), here, anywhere),
        )
        assertThat(one.condition).isEqualTo(every(open, test("TutorialController.finished", "==", "0")))
    }

    @Test
    fun `한 비교의 참 거짓만 다른 두 갈래는 그 비교를 지운다`() {
        val c = test("MapMove.enteringStage", "==", "0")
        val tree = either(every(test("MapMove.movement", "==", "0"), c), every(test("MapMove.movement", "!=", "0"), c))

        assertThat(MapTestCaseReadingRules.simplified(tree).condition).isEqualTo(c)
    }

    /** 실측 1.4.0: `또는` 의 양쪽이던 `IsActive(movement) != 0` 과 `== 0` 이 낱개로 나열돼 "둘 다 준비하라" 가 됐다. */
    @Test
    fun `준비할 상태도 원래 구조를 지킨다`() {
        val c = test("MapMove.enteringStage", "==", "0")
        val tree = either(
            every(test("TweenExtensions.IsActive(MapMove.movement)", "!=", "0", readable = false), c),
            every(test("TweenExtensions.IsActive(MapMove.movement)", "==", "0", readable = false), c),
        )

        // 지운 두 비교는 참 거짓만 다른 갈래라 서로 지운다. 준비할 상태가 남지 않는다.
        assertThat(MapTestCaseReadingRules.readable(tree).setup).isEmpty()
    }
}
