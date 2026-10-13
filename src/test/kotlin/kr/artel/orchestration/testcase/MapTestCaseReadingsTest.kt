package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.dto.ConditionNodeResponse
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
        "Map.MapMove", "Core.InteractionLock", "Combat.UI.CombineButton",
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
}
