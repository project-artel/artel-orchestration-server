package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.dto.ConditionNodeResponse
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.GroupKind
import kr.artel.orchestration.testcase.generator.MapTestCasePhrasing
import kr.artel.orchestration.testcase.generator.MapTestCaseReadings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 사전조건의 비교가 SDK `pulse` 로 확인되는지 가른다. 비교는 지우지 않고 표시만 한다.
 *
 * 입력은 지도 2(word-venture)의 사전조건에서 실제로 나온 모양이다.
 */
class MapTestCaseReadingsTest {

    private val gameTypes = setOf(
        "Map.MapMove", "Combat.Enemies.SlimeAnimator", "Combat.Enemies.ProjectileAnimator", "Core.InteractionLock", "Combat.UI.CombineButton",
        "Scenes.TitleSceneManager", "Combat.Enemies.Enemy/<DeathCounter>d__25",
    )

    private fun test(left: String, operator: String = "==", right: String = "0") =
        ConditionNode.Test(left = left, operator = operator, right = right, context = "this", offset = 0)

    private fun readable(left: String, right: String = "0") =
        (MapTestCaseReadings.mark(test(left, right = right), gameTypes) as ConditionNode.Test).readable

    @Test
    fun `게임 타입의 멤버와 그 산술은 읽힌다`() {
        assertThat(readable("MapMove.StagePosition")).isTrue()
        assertThat(readable("InteractionLock.IsLocked")).isTrue()
        assertThat(readable("CombineButton.combineZone.activeSelf")).isTrue()
        assertThat(readable("(MapMove.StagePosition - 1)")).isTrue()
        // 컴파일러가 지은 중첩 이름은 떼고 본다.
        assertThat(readable("Enemy.Hp", right = "-10")).isTrue()
    }

    @Test
    fun `지금 씬은 판독이 싣는다`() {
        assertThat(readable("SceneManager.GetActiveScene().name", right = "\"GameClearScene\"")).isTrue()
    }

    @Test
    fun `호출 · 지역 변수 · 엔진 타입은 안 읽힌다`() {
        assertThat(readable("TitleSceneManager.saveLoadController.LoadPlayData()")).isFalse()
        assertThat(readable("collision.CompareTag(\"Enemy\")")).isFalse()
        assertThat(readable("bigSide")).isFalse()
        assertThat(readable("Vector3.x")).isFalse()
        // 오른쪽만 지역 변수여도 그 비교는 확인할 수 없다.
        assertThat(readable("MapMove.StagePosition", right = "damage")).isFalse()
    }

    @Test
    fun `모양은 그대로 두고 표시만 한다`() {
        val tree = ConditionNode.Group(
            GroupKind.EVERY,
            listOf(test("InteractionLock.IsLocked"), test("TitleSceneManager.saveLoadController.LoadPlayData()", "!=")),
        )

        val marked = MapTestCaseReadings.mark(tree, gameTypes) as ConditionNode.Group

        assertThat(marked.parts.map { (it as ConditionNode.Test).readable }).containsExactly(true, false)
        assertThat(marked.parts.map { (it as ConditionNode.Test).left })
            .containsExactly("InteractionLock.IsLocked", "TitleSceneManager.saveLoadController.LoadPlayData()")
    }

    @Test
    fun `사람이 읽는 문장에 확인 불가를 적는다`() {
        val tree = MapTestCaseReadings.mark(
            ConditionNode.Group(
                GroupKind.EVERY,
                listOf(test("InteractionLock.IsLocked"), test("TitleSceneManager.saveLoadController.LoadPlayData()", "!=")),
            ),
            gameTypes,
        )

        assertThat(MapTestCasePhrasing.precondition("TitleScene", tree)).isEqualTo(
            "TitleScene 화면인 상태 / InteractionLock.IsLocked == 0 그리고 " +
                "TitleSceneManager.saveLoadController.LoadPlayData() != 0 (확인 불가)",
        )
    }

    @Test
    fun `응답에는 확인 못 하는 비교에만 칸이 선다`() {
        val mapper = ObjectMapper()
        val read = mapper.writeValueAsString(ConditionNodeResponse.of(test("MapMove.StagePosition")))
        val unread = mapper.writeValueAsString(
            ConditionNodeResponse.of(MapTestCaseReadings.mark(test("bigSide"), gameTypes)!!),
        )

        assertThat(read).doesNotContain("readable")
        assertThat(unread).contains("\"readable\":false")
    }

    // --- 그림 기대결과 ---------------------------------------------------------------

    private fun sprite(target: String, detail: String) = CapabilityEffectEntity(
        capabilityId = 1, category = "observable", kind = "ui-value", target = target, detail = detail,
    )

    /** 지도 2 의 `SlimeAnimator` 셋. 주인마다 목록이 다르고, 여섯째부터는 둘만 있다. */
    private val positions = mapOf(
        ("SlimeAnimator" to "sprites") to mapOf(
            4 to setOf("Flower_05", "Rock_05", "Cat_05"),
            6 to setOf("Flower_07", "Cat_07"),
        ),
    )

    private fun expected(effect: CapabilityEffectEntity) =
        MapTestCasePhrasing.expectedWithSource(
            listOf(effect),
            refs = mapOf(("MapMove" to "stage2") to setOf("Stage2BG")),
            sceneNames = setOf("Background"),
            positions = positions,
            gameTypes = gameTypes,
        ).single().expected

    @Test
    fun `이름으로 찾은 오브젝트의 그림은 확인 불가로 남는다`() {
        assertThat(expected(sprite("GameObject.Find(\"Background\").GetComponent().sprite", "MapMove.stage2")))
            .isEqualTo("`Background` 표시가 `Stage2BG` 로 갱신된다 (확인 불가)")
    }

    @Test
    fun `목록 번호는 주인마다의 그림 이름으로 적는다`() {
        assertThat(expected(sprite("SlimeAnimator.spriteRenderer.sprite", "SlimeAnimator.sprites.Item[4]")))
            .isEqualTo("`SlimeAnimator.spriteRenderer.sprite` 표시가 `Cat_05` · `Flower_05` · `Rock_05` 중 하나 로 갱신된다")
        assertThat(expected(sprite("SlimeAnimator.spriteRenderer.sprite", "SlimeAnimator.sprites.Item[6]")))
            .isEqualTo("`SlimeAnimator.spriteRenderer.sprite` 표시가 `Cat_07` · `Flower_07` 중 하나 로 갱신된다")
    }

    @Test
    fun `이름을 모르는 그림 번호는 값을 빼고 갱신된다고만 적는다`() {
        assertThat(expected(sprite("ProjectileAnimator.spriteRenderer.sprite", "ProjectileAnimator.sprites.Item[0]")))
            .isEqualTo("`ProjectileAnimator.spriteRenderer.sprite` 표시가 갱신된다")
    }
}
