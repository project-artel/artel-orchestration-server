package kr.artel.orchestration.contentmap.render

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * 실측 WordVenture 캡처(`wv-editor-latest.json`, 1.4MB, schema 6)를 렌더해 리소스로 고정한
 * 골든과 바이트 동일 비교한다. 회귀 그물 — 네 가지 수정의 계약 자체는 [ExpressionWriterTest],
 * [MethodRendererTest], [WiringIndexTest] 가 진다.
 *
 * 렌더는 한 번만 한다(`PER_CLASS` + `@BeforeAll`) — 37개 파일마다 1.4MB 문서를 통째로 다시
 * 렌더하면 파라미터화 테스트 수만큼 같은 계산을 반복하게 된다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvidenceRendererGoldenTest {

    private lateinit var files: Map<String, String>

    @BeforeAll
    fun renderOnce() {
        val json = javaClass.getResourceAsStream("/contentmap/wv-editor-latest.json")!!.bufferedReader().readText()
        files = renderEvidenceDocument(json)
    }

    private fun goldenText(name: String): String =
        javaClass.getResourceAsStream("/contentmap/golden/$name")!!.bufferedReader().readText()

    @Test
    fun `타입 21 unplaced 14 + scene graph + notes 로 37개 파일이 나온다`() {
        assertThat(files).hasSize(37)
        assertThat(files.keys.count { it.startsWith("_unplaced/") }).isEqualTo(14)
        assertThat(files.keys.count { !it.startsWith("_unplaced/") && it !in setOf("_SceneGraph.cs", "_Notes.cs") })
            .isEqualTo(21)
        assertThat(files).containsKeys("_SceneGraph.cs", "_Notes.cs")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenFileNames")
    fun `렌더 결과가 골든과 바이트 동일하다`(fileName: String) {
        assertThat(files[fileName]).isEqualTo(goldenText(fileName))
    }

    @Test
    fun `같은 입력을 두 번 렌더하면 바이트가 완전히 같다`() {
        val json = javaClass.getResourceAsStream("/contentmap/wv-editor-latest.json")!!.bufferedReader().readText()

        val first = renderEvidenceDocument(json)
        val second = renderEvidenceDocument(json)

        assertThat(first).isEqualTo(second)
        assertThat(first).isEqualTo(files)
    }

    companion object {
        @JvmStatic
        fun goldenFileNames(): List<String> = listOf(
            "Battle.Turns.TurnBattleSystem.cs",
            "Cards.BackButton.cs",
            "Cards.CardManager.cs",
            "Combat.Enemies.BattleWaveController.cs",
            "Combat.Enemies.EnemyPoolController.cs",
            "Combat.Enemies.EnemyTestManager.cs",
            "Combat.Enemies.Player.cs",
            "Combat.Enemies.SelectableObject.cs",
            "Combat.Stage.StageDataSingleton.cs",
            "Combat.Stage.StageManager.cs",
            "Combat.UI.CombineButton.cs",
            "Combat.UI.CombineZone.cs",
            "Combat.UI.DropZone.cs",
            "Core.SaveLoadController.cs",
            "Map.MapMove.cs",
            "Scenes.GameClearController.cs",
            "Scenes.TitleSceneManager.cs",
            "Story.ChatWindowController.cs",
            "Story.StoryController.cs",
            "Tutorial.TutorialChatWindow.cs",
            "Tutorial.TutorialController.cs",
            "_Notes.cs",
            "_SceneGraph.cs",
            "_unplaced/Cards.Card.cs",
            "_unplaced/Cards.Order.cs",
            "_unplaced/Cards.Util.cs",
            "_unplaced/Combat.Enemies.BossEnemy.cs",
            "_unplaced/Combat.Enemies.Enemy.cs",
            "_unplaced/Combat.Enemies.EnemyProjectile.cs",
            "_unplaced/Combat.Enemies.MagicEnemy.cs",
            "_unplaced/Combat.Enemies.ProjectileAnimator.cs",
            "_unplaced/Combat.Enemies.SlimeAnimator.cs",
            "_unplaced/Combat.Enemies.SwordEnemy.cs",
            "_unplaced/Combat.Spells.SpellObj.cs",
            "_unplaced/Combat.UI.DraggableCard.cs",
            "_unplaced/Scenes.Test.RemoteControlPoCController.cs",
            "_unplaced/Scenes.Test.TrackingTest.cs",
        )
    }
}
