package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.service.ScenarioOpeningNote
import kr.artel.orchestration.testscenario.service.ScenarioOpeningNote.Requirement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **여기까지 와야 시작한다**(ARTEL-636).
 *
 * 시나리오는 하나가 끝날 때마다 게임을 초기화하는데 검증하는 순간은 게임 곳곳에 흩어져 있다.
 * 엔딩을 보는 시나리오는 매번 엔딩까지 다시 가야 하고, 중반을 보는 것은 중반까지 다시 가야 한다.
 *
 * 첫 스텝이 `StagePosition >= 4` 를 요구하는데 초기화 직후 그 값이 0이면, 아무 말 없이
 * 시작하는 것은 "알아서 네 번 이겨라"와 같다.
 */
class ScenarioOpeningNoteTest {

    /** 값과 자리, 그리고 **어디서 오르는지**까지 적는다. 그것이 찾아갈 실마리다. */
    @Test
    fun `무엇이 참이어야 시작하는지와 어디서 오르는지를 적는다`() {
        val note = ScenarioOpeningNote.of(
            listOf(Requirement("StagePosition", ">= 4", listOf("TurnBattleScene")))
        )

        assertThat(note).contains("StagePosition >= 4")
        assertThat(note).contains("TurnBattleScene 에서 오른다")
    }

    /**
     * **모르면 아는 척하지 않는다.** 어디서 오르는지 지도가 말하지 않으면 화면을 대지 않는다 —
     * 없는 화면을 대면 실행하는 쪽이 없는 곳을 찾아 헤맨다.
     *
     * 부르는 쪽은 그런 요구를 애초에 안 넘긴다(`ScenarioReconcileService.openingNeeds`). 찾아갈
     * 실마리가 없는 안내는 "알아서 하라"와 같고, 그런 줄이 매 시나리오에 붙으면 소음이다.
     */
    @Test
    fun `오르는 화면을 모르면 요구만 적는다`() {
        val note = ScenarioOpeningNote.of(listOf(Requirement("flag", "!= 0", emptyList())))

        assertThat(note).contains("flag != 0")
        assertThat(note).doesNotContain("에서 오른다")
    }

    /**
     * **적을 것이 없으면 붙이지 않는다.** 매번 한 줄이 붙으면 그것이 소음이고, 정작 먼 자리에서
     * 시작하는 시나리오가 그 속에 묻힌다.
     */
    @Test
    fun `시작 상태로 되는 시나리오에는 붙이지 않는다`() {
        assertThat(ScenarioOpeningNote.of(emptyList())).isNull()
    }

    /** 여럿이면 한 줄에 잇는다. 줄이 늘면 읽는 사람이 스텝과 헷갈린다. */
    @Test
    fun `요구가 여럿이면 한 줄로 잇는다`() {
        val note = ScenarioOpeningNote.of(
            listOf(
                Requirement("StagePosition", ">= 2", listOf("TurnBattleScene")),
                Requirement("flag", "!= 0", listOf("GameClearScene")),
            )
        )

        assertThat(note!!.lines()).hasSize(1)
        assertThat(note).contains("StagePosition >= 2")
        assertThat(note).contains("flag != 0")
    }
}
