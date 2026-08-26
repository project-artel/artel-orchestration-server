package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.service.ScenarioStateReader
import kr.artel.orchestration.testscenario.service.SceneMove
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 사전조건에서 **무엇을 요구하는지** 읽는다(ARTEL-466 · 497).
 *
 * 여기서 틀리면 뒤가 전부 틀린다. 실제로 런 152 에서 "함께 담을 수 없다"고 판정된 19쌍이 전부
 * 이 읽개의 잘못이었다 — `또는` 을 `그리고` 처럼 긁어모아, 갈래가 둘인 사전조건이 자기 자신과
 * 모순되는 요구 목록이 됐다.
 */
class ScenarioStateReaderTest {

    private fun guards(precondition: String) =
        ScenarioStateReader.guardsOf(precondition).map { "${it.variable} ${it.operator} ${it.value}" }

    @Test
    fun `그리고로 이어진 것은 모두 요구다`() {
        assertThat(guards("Map_scene 화면인 상태 / (MapMove.StagePosition >= 1 그리고 MapMove.position == 0)"))
            .containsExactly("StagePosition >= 1", "position == 0")
    }

    @Test
    fun `또는로 갈린 것은 모든 갈래에 있는 것만 요구다`() {
        // word-venture 1299. 어느 갈래든 `hp > 0` 이지만 `damage` 는 갈래마다 다르다.
        // 둘 다 요구로 읽으면 이 케이스는 `damage > 0` 인 모든 케이스와 충돌한다.
        assertThat(
            guards(
                "TurnBattleScene 화면인 상태 / " +
                    "((damage > 0 그리고 Player.hp > 0) 또는 (damage <= 0 그리고 Player.hp > 0))"
            )
        ).containsExactly("hp > 0")
    }

    @Test
    fun `갈래가 다른 항과 함께 있으면 밖의 항은 그대로 요구다`() {
        // word-venture 1298.
        assertThat(
            guards(
                "TurnBattleScene 화면인 상태 / (collision.tag == \"Me\" 그리고 " +
                    "((damage > 0 그리고 Player.hp > 0) 또는 (damage <= 0 그리고 Player.hp > 0)))"
            )
        ).containsExactly("tag == \"Me\"", "hp > 0")
    }

    @Test
    fun `모든 갈래가 아무것도 공유하지 않으면 요구가 없다`() {
        assertThat(guards("A 화면인 상태 / ((x == 1) 또는 (y == 2))")).isEmpty()
    }

    @Test
    fun `오른쪽이 다른 변수면 값을 아는 것이 아니다`() {
        // `collision.tag == SpellObj.target.gameObject.tag` 는 두 값이 같아야 한다는 말이지
        // `tag` 가 무엇이라는 말이 아니다. 확정값으로 읽으면 `tag == "Me"` 와 어긋난다고 나온다.
        val pre = "TurnBattleScene 화면인 상태 / (collision.tag == SpellObj.target.gameObject.tag)"

        assertThat(ScenarioStateReader.knownValuesOf(pre)).isEmpty()
        assertThat(ScenarioStateReader.guardsOf(pre).single().symbolic).isTrue()
        // 비교할 수 없으니 위반이라 말하지 않는다.
        assertThat(ScenarioStateReader.guardsOf(pre).single().holds("\"Me\"")).isTrue()
    }

    @Test
    fun `리터럴은 그대로 확정값이다`() {
        assertThat(ScenarioStateReader.knownValuesOf("Map_scene 화면인 상태 / (StagePosition == 5)"))
            .containsEntry("StagePosition", "5")
    }

    @Test
    fun `씬 접두가 없는 사전조건도 읽는다`() {
        // word-venture 1277. 씬은 `scene` 컬럼에만 있고 사전조건은 식 하나뿐이다. 구분자가 없다고
        // 빈 문자열을 읽으면 이 케이스의 요구가 코드에 통째로 안 보인다 — 그 값이면 GameClearScene
        // 이 입력 없이 EndingScene 으로 빠진다는 사실을 아무도 모르게 된다.
        assertThat(guards("StageDataSingleton.StagePosition == 4"))
            .containsExactly("StagePosition == 4")
        assertThat(ScenarioStateReader.knownValuesOf("StageDataSingleton.StagePosition == 4"))
            .containsEntry("StagePosition", "4")
    }

    @Test
    fun `씬 접두는 가드가 아니다`() {
        // 앞부분을 함께 읽어도 안전한 이유 — 접두에는 비교 연산자가 없다.
        assertThat(guards("Map_scene 화면인 상태")).isEmpty()
    }

    // --- 도착 화면 읽기 (ARTEL-528) --------------------------------------------------

    private fun caseWith(scene: String, expected: String) = kr.artel.orchestration.testcase.entity.TestCaseEntity(
        projectId = 1, scene = scene, step = "s", precondition = "$scene 화면인 상태", expectedValue = expected,
    )

    @Test
    fun `시작 씬이 아닌 이름 하나면 거기로 간다`() {
        val move = ScenarioStateReader.sceneAfter(
            caseWith("StoryScene", "`Map_scene` 화면으로 전환된다"),
            setOf("StoryScene", "Map_scene", "TitleScene"),
        )

        assertThat(move).isEqualTo(SceneMove.To("Map_scene"))
    }

    @Test
    fun `아무 이름도 없으면 머문다`() {
        val move = ScenarioStateReader.sceneAfter(
            caseWith("Map_scene", "`wordHead` 가 `battle1` 위치로 바뀐다"),
            setOf("StoryScene", "Map_scene"),
        )

        assertThat(move).isEqualTo(SceneMove.Stays)
    }

    @Test
    fun `여러 이름을 말하면 가릴 수 없다`() {
        val move = ScenarioStateReader.sceneAfter(
            caseWith("StoryScene", "`Map_scene` 또는 `TitleScene` 으로 간다"),
            setOf("StoryScene", "Map_scene", "TitleScene"),
        )

        assertThat(move).isEqualTo(SceneMove.Unknown)
    }

    @Test
    fun `이름이 다른 이름의 일부면 긴 쪽만 본다`() {
        // `Map` 과 `Map_scene` 이 둘 다 씬일 수 있다. 짧은 쪽은 긴 쪽을 읽다가 걸린 것이다.
        val move = ScenarioStateReader.sceneAfter(
            caseWith("StoryScene", "`Map_scene` 화면으로 전환된다"),
            setOf("StoryScene", "Map", "Map_scene"),
        )

        assertThat(move).isEqualTo(SceneMove.To("Map_scene"))
    }

    @Test
    fun `낱말 한가운데 걸린 것은 언급이 아니다`() {
        // 씬 이름은 개발자가 짓는다. 흔한 낱말이면 아무 문장에나 걸리는데, 그때 이 검사가 반응하면
        // 알림 전체가 못 믿을 것이 된다.
        val move = ScenarioStateReader.sceneAfter(
            caseWith("TitleScene", "Mapping 이 갱신된다"),
            setOf("TitleScene", "Map"),
        )

        assertThat(move).isEqualTo(SceneMove.Stays)
    }
}
