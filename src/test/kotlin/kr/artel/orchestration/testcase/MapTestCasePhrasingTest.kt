package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.testcase.generator.MapTestCasePhrasing
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 지도 한 줄이 **사람이 읽는 세 문장**이 된다(ARTEL-554).
 *
 * TC 는 사용자가 보는 정보다. 문장이 나빠지면 바로 티가 나고, 그때는 이미 사람이 그걸로 QA 를 돌린
 * 뒤다. 그래서 이 파일은 문장을 글자까지 못 박는다 — 바꾸려면 여기가 먼저 깨져야 한다.
 *
 * 모든 입력은 적재기가 앉힌 word-venture 지도에서 실제로 나온 모양이다.
 */
class MapTestCasePhrasingTest {

    private val mapper = ObjectMapper()

    private fun condition(json: String) = EvidenceParser(mapper).parseCondition(mapper.readTree(json))

    private fun effect(kind: String, target: String?, detail: String? = null, category: String = "observable") =
        CapabilityEffectEntity(
            capabilityId = 1, category = category, kind = kind, target = target, detail = detail,
        )

    // --- 사전조건 ---------------------------------------------------------------------

    @Test
    fun `조건이 없으면 화면만 사전조건이다`() {
        val text = MapTestCasePhrasing.precondition("Map_scene", condition("""{"kind":"always"}"""))

        // "그 화면이기만 하면 된다"도 사전조건이다.
        assertThat(text).isEqualTo("Map_scene 화면인 상태")
    }

    /** 기존 케이스와 같은 모양이라 저작의 사전조건 파서가 그대로 읽는다. */
    @Test
    fun `조건은 화면 뒤에 슬래시로 붙는다`() {
        val text = MapTestCasePhrasing.precondition(
            "Map_scene",
            condition("""{"kind":"test","left":"MapMove.position","operator":"==","right":"0","context":"static"}"""),
        )

        assertThat(text).isEqualTo("Map_scene 화면인 상태 / MapMove.position == 0")
    }

    /**
     * 입력은 **사람이 하는 일**이지 화면이 이미 그런 상태여야 한다는 말이 아니다. 남기면
     * "Return 키가 눌린 상태에서 Return 키를 누른다"가 된다.
     */
    @Test
    fun `입력 조건은 사전조건에서 빠진다`() {
        val text = MapTestCasePhrasing.precondition(
            "TurnBattleScene",
            condition(
                """{"kind":"every","parts":[
                     {"kind":"gesture","input":"key:Return (down)","offset":704},
                     {"kind":"test","left":"InteractionLock.IsLocked","operator":"==","right":"0","context":"static"}]}"""
            ),
        )

        assertThat(text).isEqualTo("TurnBattleScene 화면인 상태 / InteractionLock.IsLocked == 0")
    }

    /**
     * 사람에게는 명세가 말한 대로 보여 준다 — 코드가 근거로 쓸 때만 좁힌다
     * (`ScenarioConditionTree.guards` 가 `either` 를 교집합으로 접는 것과 대비된다).
     */
    @Test
    fun `either 는 또는으로 잇는다`() {
        val text = MapTestCasePhrasing.precondition(
            "Map_scene",
            condition(
                """{"kind":"either","parts":[
                     {"kind":"test","left":"MapMove.position","operator":"==","right":"0","context":"static"},
                     {"kind":"test","left":"MapMove.position","operator":"==","right":"1","context":"static"}]}"""
            ),
        )

        assertThat(text).isEqualTo("Map_scene 화면인 상태 / MapMove.position == 0 또는 MapMove.position == 1")
    }

    /** 못 읽은 조건을 적으면 실행하는 사람이 만들 수 없는 상태를 요구받는다. */
    @Test
    fun `못 읽은 조건은 사전조건에 적지 않는다`() {
        val text = MapTestCasePhrasing.precondition(
            "StoryScene",
            condition("""{"kind":"unknown","reason":"unread-branch","unread":"brtrue.s"}"""),
        )

        assertThat(text).isEqualTo("StoryScene 화면인 상태")
    }

    // --- 행동 -------------------------------------------------------------------------

    @Test
    fun `버튼은 조준 대상을 부른다`() {
        assertThat(MapTestCasePhrasing.step("click", null, null, "Canvas/MapSceneButton"))
            .isEqualTo("`Canvas/MapSceneButton` 을(를) 클릭한다")
    }

    /** 이름표가 있으면 경로보다 그쪽이 사람에게 가깝다. */
    @Test
    fun `이름표가 있으면 이름표를 부른다`() {
        assertThat(MapTestCasePhrasing.step("click", null, "Combine", "CombineSystem/CombineZone/Button"))
            .isEqualTo("`Combine` 을(를) 클릭한다")
    }

    @Test
    fun `키 입력은 키 이름을 부른다`() {
        assertThat(MapTestCasePhrasing.step("press", "RightArrow", null, null))
            .isEqualTo("`RightArrow` 키를 누른다")
    }

    /**
     * `any` 를 그대로 쓰면 "`any` 키를 누른다"가 되어 무엇을 누르라는 것인지 알 수 없다.
     * 실측에서 이 값이 흔하다 — 대사 넘기기가 전부 `any` 다.
     */
    @Test
    fun `아무 키나 되는 자리는 그렇게 적는다`() {
        assertThat(MapTestCasePhrasing.step("press", "any", null, null))
            .isEqualTo("아무 키나 누른다")
    }

    // --- 기대결과 ---------------------------------------------------------------------

    @Test
    fun `씬 전환은 어느 화면으로 가는지 말한다`() {
        assertThat(MapTestCasePhrasing.expectedEach(listOf(effect("scene", "Map_scene"))))
            .containsExactly("`Map_scene` 화면으로 전환된다")
    }

    /**
     * **값이 바뀌는 것은 기대결과가 아니다.** 화면에서 확인할 수 없다 —
     * `EffectCategory.assertable` 이 그 판정을 들고 있고 `v_spec_gap` 도 같은 규칙으로 센다.
     */
    @Test
    fun `상태 쓰기만 있으면 기대결과가 없다`() {
        val onlyState = listOf(effect("write", "MapMove.StagePosition", "+1", category = "state"))

        assertThat(MapTestCasePhrasing.expectedEach(onlyState)).isEmpty()
    }

    @Test
    fun `확인할 수 있는 것과 없는 것이 섞이면 확인할 수 있는 것만 낸다`() {
        val mixed = listOf(
            effect("write", "MapMove.StagePosition", "+1", category = "state"),
            effect("scene", "GameClearScene"),
        )

        assertThat(MapTestCasePhrasing.expectedEach(mixed)).containsExactly("`GameClearScene` 화면으로 전환된다")
    }

    /**
     * **효과 하나가 케이스 하나다.** 합쳐서 한 줄로 내면 실행하는 사람이 무엇을 볼지 모른다 —
     * 실측(`Map.MapMove.CharacterMove`)에서 그 기능 하나가 결과 아홉 가지를 낸다.
     */
    @Test
    fun `효과가 여럿이면 줄도 여럿이다`() {
        val many = listOf(
            effect("active-state", "Canvas/continue", "false"),
            effect("ui-value", "Text.text", "score"),
        )

        assertThat(MapTestCasePhrasing.expectedEach(many)).containsExactly(
            "`Canvas/continue` 의 표시 상태가 `false`",
            "`Text.text` 표시가 `score` 로 갱신된다",
        )
    }

    /** 새 `kind` 가 생겼을 때 그 효과가 조용히 사라지는 것보다, 어색해도 보이는 편이 낫다. */
    @Test
    fun `어휘를 모르는 효과도 버리지 않는다`() {
        assertThat(MapTestCasePhrasing.expectedEach(listOf(effect("haptic", "Gamepad.rumble", "0.5"))))
            .containsExactly("`Gamepad.rumble` 이(가) `0.5` 이 된다")
    }

    @Test
    fun `효과가 하나도 없으면 기대결과가 없다`() {
        assertThat(MapTestCasePhrasing.expectedEach(emptyList())).isEmpty()
    }
}
