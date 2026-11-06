package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource
import kr.artel.orchestration.testscenario.service.ScenarioBridgeGrounding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 다리 근거 되찾기(계측 2026-09-08의 반려 41건이 뿌리).
 *
 * 계약은 다리에 capability id 를 요구하는데 모델은 그 id 를 알 길이 없다 — 지어낸 id 는
 * 검수에 걸려 같은 자리에서 9~10연속 반려됐다(런 31·40). 되찾기는 그 반려를 흡수한다:
 * 위치(양옆 CASE 스텝의 씬)로 후보를 좁히고, 조작 표기 포함 검사로 가르고, 못 가르면
 * 고르지 않고 UNKNOWN 으로 남겨 사용자에게 묻는다.
 */
class ScenarioBridgeGroundingTest {

    private val edges = listOf(
        ScenarioBridgeGrounding.Edge("Map_scene", "TurnBattleScene", "Return", 100L),
        ScenarioBridgeGrounding.Edge("TitleScene", "StoryScene", "Canvas/continue", 200L),
        ScenarioBridgeGrounding.Edge("TitleScene", "StoryScene", "Canvas/MapSceneButton", 201L),
        // 저절로 넘어가는 간선 — 시킬 수 없으므로 후보가 아니다.
        ScenarioBridgeGrounding.Edge("TurnBattleScene", "GameClearScene", null, 300L),
    )

    private val scenes = mapOf(1L to "Map_scene", 2L to "TurnBattleScene", 3L to "TitleScene", 4L to "StoryScene")

    private fun bridge(action: String, capabilityId: Long? = 999L) = ChatScenarioStep(
        action = action,
        stepSource = ScenarioStepSource.CAPABILITY,
        stepSourceCapabilityId = capabilityId,
    )

    private fun case(id: Long) = ChatScenarioStep(action = "케이스 $id", caseId = id, stepSource = ScenarioStepSource.CASE)

    private fun apply(vararg steps: ChatScenarioStep, live: Set<Long> = emptySet()) =
        ScenarioBridgeGrounding.apply(
            listOf(ScenarioResult(title = "판", steps = steps.toList())),
            sceneOf = { scenes[it] },
            arrivesAt = { scenes[it] },
            edges = edges,
            live = live,
        )

    @Test
    fun `간선이 하나면 문장을 안 읽고도 id 를 채운다`() {
        val out = apply(case(1), bridge("Return 키를 눌러 전투로 들어간다"), case(2))

        val step = out.scenarios.single().steps[1]
        assertThat(step.stepSource).isEqualTo(ScenarioStepSource.CAPABILITY)
        assertThat(step.stepSourceCapabilityId).isEqualTo(100L)
        assertThat(step.stepUnknownReason).isNull()
    }

    @Test
    fun `간선이 여럿이면 조작 표기 포함으로 가른다`() {
        val out = apply(case(3), bridge("Canvas/continue 를 눌러 스토리로"), case(4))

        assertThat(out.scenarios.single().steps[1].stepSourceCapabilityId).isEqualTo(200L)
    }

    @Test
    fun `못 가르면 고르지 않고 후보를 적어 UNKNOWN 으로 남긴다`() {
        // 고르는 순간 우선순위 잣대가 생기고, 잣대는 게임 하나에 맞춰진다.
        val out = apply(case(3), bridge("버튼을 눌러 스토리로"), case(4))

        val step = out.scenarios.single().steps[1]
        assertThat(step.stepSource).isEqualTo(ScenarioStepSource.UNKNOWN)
        assertThat(step.stepSourceCapabilityId).isNull()
        assertThat(step.stepUnknownReason).contains("Canvas/continue", "Canvas/MapSceneButton")
    }

    @Test
    fun `저절로 넘어가는 자리는 시킬 수 없다고 말한다`() {
        val out = apply(case(2), bridge("전투에서 이겨 클리어 화면으로"), case(1))

        // TurnBattleScene → Map_scene 간선 자체가 없어 "지도가 모른다"로도 남을 수 있는
        // 자리지만, 이 픽스처의 요점은 어느 쪽이든 지어내지 않는다는 것이다.
        assertThat(out.scenarios.single().steps[1].stepSource).isEqualTo(ScenarioStepSource.UNKNOWN)
    }

    @Test
    fun `실재하는 id 를 단 스텝과 CASE 스텝은 건드리지 않는다`() {
        val out = apply(case(1), bridge("Return", capabilityId = 100L), case(2), live = setOf(100L))

        assertThat(out.scenarios.single().steps[1].stepSourceCapabilityId).isEqualTo(100L)
        assertThat(out.notes).isEmpty()
    }

    @Test
    fun `구버전(source 없음)은 검사도 되찾기도 건너뛴다`() {
        val out = apply(case(1), ChatScenarioStep(action = "그냥 다리"), case(2))

        assertThat(out.scenarios.single().steps[1].stepSource).isNull()
        assertThat(out.notes).isEmpty()
    }
}
