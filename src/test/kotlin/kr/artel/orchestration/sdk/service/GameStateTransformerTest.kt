package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kr.artel.orchestration.sdk.dto.SdkAction
import kr.artel.orchestration.sdk.dto.SdkBlock
import kr.artel.orchestration.sdk.dto.SdkComponent
import kr.artel.orchestration.sdk.dto.SdkError
import kr.artel.orchestration.sdk.dto.SdkGameState
import kr.artel.orchestration.sdk.dto.SdkState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GameStateTransformerTest {

    private fun sceneOf(vararg children: SdkBlock) = SdkGameState(
        type = "GAME_STATE",
        id = 1,
        scene = SdkBlock(id = 0, type = "scene", name = "Lobby", children = children.toList())
    )

    private fun action(sequence: Int, name: String, success: Boolean = true, error: SdkError? = null) =
        SdkAction(
            sequence = sequence,
            tag = "tag",
            name = name,
            success = success,
            returnValue = null,
            error = error,
            timeStamp = "2026-07-25T10:00:0$sequence"
        )

    @Test
    fun `버튼이 실행한 액션도 기록으로 싣는다`() {
        // 조작 후보 추출은 커스텀 컴포넌트의 actions만 보지만, 실행 기록은 종류를 가리지
        // 않아야 한다. 버튼이 눌렸는지가 QA가 확인하려는 것이다.
        val state = sceneOf(
            SdkBlock(
                id = 1,
                type = "block",
                name = "StartButton",
                components = listOf(
                    SdkComponent(type = "button", name = "Button", actions = listOf(action(1, "onClick")))
                )
            )
        )

        val agent = GameStateTransformer.toAgentGameState(state)

        assertThat(agent.recentActions).hasSize(1)
        assertThat(agent.recentActions[0].target).isEqualTo("StartButton")
        assertThat(agent.recentActions[0].name).isEqualTo("onClick")
        assertThat(agent.recentActions[0].success).isTrue()
    }

    @Test
    fun `실패한 액션은 사유를 함께 남긴다`() {
        val state = sceneOf(
            SdkBlock(
                id = 1,
                type = "block",
                name = "Shop",
                components = listOf(
                    SdkComponent(
                        type = "ShopController",
                        name = "Shop",
                        actions = listOf(
                            action(1, "buy", success = false, error = SdkError("InsufficientGold", "골드가 부족합니다"))
                        )
                    )
                )
            )
        )

        val record = GameStateTransformer.toAgentGameState(state).recentActions.single()

        assertThat(record.success).isFalse()
        assertThat(record.error).isEqualTo("골드가 부족합니다")
    }

    @Test
    fun `실행 기록은 상한을 넘지 않고 최신 것이 남으며 시간순으로 나온다`() {
        val many = (1..30).map { action(it, "step$it") }
        val state = sceneOf(
            SdkBlock(
                id = 1,
                type = "block",
                name = "Runner",
                components = listOf(SdkComponent(type = "Runner", name = "Runner", actions = many))
            )
        )

        val records = GameStateTransformer.toAgentGameState(state).recentActions

        assertThat(records).hasSize(20)
        // 최신 20개(11..30)가 남고, 읽기 좋게 다시 시간순으로 정렬된다.
        assertThat(records.first().name).isEqualTo("step11")
        assertThat(records.last().name).isEqualTo("step30")
    }

    @Test
    fun `상태값은 관찰값으로 평탄화되고 조작 후보와 함께 나온다`() {
        val state = sceneOf(
            SdkBlock(
                id = 7,
                type = "block",
                name = "Score",
                components = listOf(
                    SdkComponent(
                        type = "text",
                        name = "Score",
                        content = "100",
                        states = listOf(SdkState(tag = "t", name = "value", type = "int", value = 100))
                    )
                )
            ),
            SdkBlock(
                id = 8,
                type = "block",
                name = "Input",
                components = listOf(
                    SdkComponent(type = "editText", name = "Input", placeholder = "이름")
                )
            )
        )

        val agent = GameStateTransformer.toAgentGameState(state)

        assertThat(agent.scene).isEqualTo("Lobby")
        assertThat(agent.observables["Score.text.value"]?.value).isEqualTo(100)
        assertThat(agent.observables["Score.content"]?.value).isEqualTo("100")
        assertThat(agent.interactables.single().type).isEqualTo("editText")
        assertThat(agent.recentActions).isEmpty()
    }

    @Test
    fun `잠긴 버튼과 입력 필드는 조작 후보에서 뺀다`() {
        val state = sceneOf(
            block(1, "StartButton", SdkComponent(type = "button", name = "Button")),
            block(2, "LockedButton", SdkComponent(type = "button", name = "Button", interactable = false)),
            block(3, "LockedInput", SdkComponent(type = "editText", name = "Input", interactable = false))
        )

        val agent = GameStateTransformer.toAgentGameState(state)

        assertThat(agent.interactables.map { it.name }).containsExactly("StartButton")
    }

    @Test
    fun `잠긴 버튼의 라벨은 관찰값으로 남긴다`() {
        // 후보에서 빠진 버튼의 라벨까지 관찰값에서 빼면 무엇이 잠겼는지가 어디에도 남지 않는다.
        val state = sceneOf(
            block(
                1,
                "LockedButton",
                SdkComponent(type = "button", name = "Button", interactable = false),
                SdkComponent(type = "text", name = "Label", content = "구매")
            ),
            block(
                2,
                "LiveButton",
                SdkComponent(type = "button", name = "Button"),
                SdkComponent(type = "text", name = "Label", content = "시작")
            )
        )

        val agent = GameStateTransformer.toAgentGameState(state)

        assertThat(agent.observables["LockedButton.content"]?.value).isEqualTo("구매")
        // 후보로 나가는 버튼의 라벨은 종전대로 label에 실리므로 관찰값에서는 뺀다.
        assertThat(agent.observables).doesNotContainKey("LiveButton.content")
        assertThat(agent.interactables.single().label).isEqualTo("시작")
    }

    @Test
    fun `잠긴 버튼이 실행한 액션은 기록에 남는다`() {
        val state = sceneOf(
            block(
                1,
                "LockedButton",
                SdkComponent(
                    type = "button",
                    name = "Button",
                    interactable = false,
                    actions = listOf(action(1, "onClick"))
                )
            )
        )

        val agent = GameStateTransformer.toAgentGameState(state)

        assertThat(agent.interactables).isEmpty()
        assertThat(agent.recentActions.single().name).isEqualTo("onClick")
    }

    @Test
    fun `interactable을 싣지 않는 구버전 페이로드는 후보에 그대로 오른다`() {
        // 배포 순서를 맞추지 않아도 되게 하는 것이 기본값의 목적이므로, DTO를 직접 만드는 대신
        // 실제 역직렬화 경로로 확인한다.
        val payload = """
            {
              "type": "GAME_STATE",
              "id": 1,
              "scene": {
                "id": 0, "type": "scene", "name": "Lobby",
                "children": [
                  {
                    "id": 1, "type": "block", "name": "StartButton",
                    "components": [{ "type": "button", "name": "Button" }]
                  }
                ]
              }
            }
        """.trimIndent()

        val state = jacksonObjectMapper().readValue(payload, SdkGameState::class.java)
        val agent = GameStateTransformer.toAgentGameState(state)

        assertThat(agent.interactables.single().name).isEqualTo("StartButton")
    }

    private fun block(id: Int, name: String, vararg components: SdkComponent) =
        SdkBlock(id = id, type = "block", name = name, components = components.toList())
}
