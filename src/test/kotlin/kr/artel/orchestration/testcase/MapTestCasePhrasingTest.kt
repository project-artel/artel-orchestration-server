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

    private fun step(
        interaction: String,
        inputKey: String?,
        controlLabel: String?,
        controlPath: String?,
        repeats: Boolean = false,
        does: List<String> = emptyList(),
    ): String = MapTestCasePhrasing.trial(
        MapTestCasePhrasing.act(interaction, inputKey, controlLabel, controlPath), repeats, does,
    )

    @Test
    fun `버튼은 조준 대상을 부른다`() {
        assertThat(step("click", null, null, "Canvas/MapSceneButton"))
            .isEqualTo("`Canvas/MapSceneButton` 을(를) 클릭한다")
    }

    /** 이름표가 있으면 경로보다 그쪽이 사람에게 가깝다. */
    @Test
    fun `이름표가 있으면 이름표를 부른다`() {
        assertThat(step("click", null, "Combine", "CombineSystem/CombineZone/Button"))
            .isEqualTo("`Combine` 을(를) 클릭한다")
    }

    @Test
    fun `키 입력은 키 이름을 부른다`() {
        assertThat(step("press", "RightArrow", null, null)).isEqualTo("`RightArrow` 키를 누른다")
    }

    /**
     * `any` 를 그대로 쓰면 "`any` 키를 누른다"가 되어 무엇을 누르라는 것인지 알 수 없다.
     * 실측에서 이 값이 흔하다 — 대사 넘기기가 전부 `any` 다.
     */
    @Test
    fun `아무 키나 되는 자리는 그렇게 적는다`() {
        assertThat(step("press", "any", null, null)).isEqualTo("아무 키나 누른다")
    }

    // --- 이름 -------------------------------------------------------------------------

    /**
     * **조작만으로는 이름이 안 된다.** `아무 키나 누른다` 는 실측 33건 중 여덟 줄이었고, 표만
     * 보고는 무엇을 검증하라는 것인지 알 수 없다. 동사는 `capability_effect.kind` 가 준다.
     */
    @Test
    fun `이름은 조작에 이어 무엇이 되는지를 말한다`() {
        assertThat(step("press", "any", null, null, does = listOf("`Map_scene` 화면으로 넘어간다")))
            .isEqualTo("아무 키나 눌러 `Map_scene` 화면으로 넘어간다")
        assertThat(step("click", null, "Combine", null, does = listOf("`CombineZone` 을(를) 켠다")))
            .isEqualTo("`Combine` 을(를) 클릭해 `CombineZone` 을(를) 켠다")
    }

    /**
     * 대표를 고르면 그 판단이 게임마다 다르다. 앞의 하나는 **지도가 실은 순서**이지 우리 순위가
     * 아니고, 나머지는 기대결과 칸에 그대로 있다.
     */
    @Test
    fun `결과가 여럿이면 앞의 하나만 부르고 몇 건인지 적는다`() {
        val does = listOf("`Congratulation` 을(를) 켠다", "`MagicCard` 을(를) 만든다", "`text` 표시를 갱신한다")

        assertThat(step("press", "any", null, null, does = does))
            .isEqualTo("아무 키나 눌러 `Congratulation` 을(를) 켠다 외 2건")
    }

    /** 되풀이는 조작 쪽에 붙는다 — 되풀이하는 것은 결과가 아니라 누르는 일이다. */
    @Test
    fun `되풀이해야 닿는 자리는 조작에 그것을 적는다`() {
        assertThat(step("press", "any", null, null, repeats = true, does = listOf("`Map_scene` 화면으로 넘어간다")))
            .isEqualTo("아무 키나 더 진행되지 않을 때까지 눌러 `Map_scene` 화면으로 넘어간다")
    }

    /** 조작을 못 부르는 자리는 활용할 꼴이 없다. 억지로 잇지 않고 줄표로 붙인다. */
    @Test
    fun `부를 조작이 없으면 결과를 줄표로 잇는다`() {
        assertThat(step("swipe", null, null, null, does = listOf("`Map_scene` 화면으로 넘어간다")))
            .isEqualTo("조작 미상(swipe) — `Map_scene` 화면으로 넘어간다")
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
