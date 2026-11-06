package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.CurrentScenario
import kr.artel.orchestration.testscenario.dto.DraftScenarioEdit
import kr.artel.orchestration.testscenario.dto.ScenarioExecutionMode
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 자동저장을 끈 턴의 draft(ARTEL-1074). 넣고 · 다시 고치고 · 겹치고 · 꺼내는 규칙만 본다.
 */
class AuthoringDraftTest {

    private fun result(title: String, vararg caseIds: Long, id: Long? = null) =
        ScenarioResult(id, title, "", caseIds.map { ChatScenarioStep(action = "do $it", caseId = it) })

    private fun current(id: Long, title: String) =
        CurrentScenario(id, title, "", listOf(ChatScenarioStep(action = "saved", caseId = id)))

    @Test
    fun `새것은 new 로, 기존 것을 고친 것은 edit 로 들어간다`() {
        val draft = AuthoringDraft()

        draft.accept(null, listOf(result("새 흐름", 1)), emptyList())
        draft.accept(7, listOf(result("고친 흐름", 2, id = 7)), emptyList())

        assertThat(draft.list().map { it.draftId to it.scenarioId })
            .containsExactly("new-1" to null, "edit-7" to 7L)
    }

    @Test
    fun `Agent 가 음수 번호를 돌려주면 같은 draft 를 고친다`() {
        val draft = AuthoringDraft()
        draft.accept(null, listOf(result("처음", 1)), emptyList())

        draft.accept(-1, listOf(result("다시 쓴 것", 1, 2)), emptyList())

        assertThat(draft.list()).hasSize(1)
        assertThat(draft.list().single().draftId).isEqualTo("new-1")
        assertThat(draft.list().single().title).isEqualTo("다시 쓴 것")
        assertThat(draft.list().single().scenarioId).isNull()
    }

    @Test
    fun `나누기가 여럿을 내면 첫 것만 지목한 자리에 들고 나머지는 새것이다`() {
        val draft = AuthoringDraft()

        draft.accept(7, listOf(result("앞", 1, id = 7), result("뒤", 2)), emptyList())

        assertThat(draft.list().map { it.draftId }).containsExactly("edit-7", "new-1")
    }

    @Test
    fun `겹치면 고친 것은 본문이 바뀌고 합쳐질 것은 빠지고 새것은 음수 번호로 붙는다`() {
        val draft = AuthoringDraft()
        draft.accept(7, listOf(result("7 을 고친 것", 1, 2, id = 7)), listOf(8))
        draft.accept(null, listOf(result("새 흐름", 3)), emptyList())

        val seen = draft.overlay(listOf(current(7, "7 원본"), current(8, "8 원본"), current(9, "9 원본")))

        assertThat(seen.map { it.scenarioId to it.title }).containsExactly(
            7L to "7 을 고친 것",
            9L to "9 원본",
            -1L to "새 흐름",
        )
    }

    @Test
    fun `draft 의 새것을 흡수하면 그 draft 는 사라지고 런에서 걷어낼 번호로 남지 않는다`() {
        val draft = AuthoringDraft()
        draft.accept(null, listOf(result("흡수될 것", 1)), emptyList())

        draft.accept(7, listOf(result("합친 것", 1, 2, id = 7)), listOf(-1, 7))

        assertThat(draft.list().map { it.draftId }).containsExactly("edit-7")
        assertThat(draft.list().single().absorbedScenarioIds).isEmpty()
    }

    @Test
    fun `고친 본문은 대상을 그대로 두고, 꺼낸 것은 사라지고, 되돌리면 다시 생긴다`() {
        val draft = AuthoringDraft()
        draft.accept(7, listOf(result("처음", 1, id = 7)), listOf(8))

        val edited = draft.update("edit-7", DraftScenarioEdit(title = "사람이 고침", steps = emptyList()))
        assertThat(edited!!.scenarioId).isEqualTo(7)
        assertThat(edited.absorbedScenarioIds).containsExactly(8L)
        assertThat(draft.update("new-99", DraftScenarioEdit())).isNull()

        val taken = draft.take(listOf("edit-7", "없는-것"))
        assertThat(taken.map { it.title }).containsExactly("사람이 고침")
        assertThat(draft.isEmpty()).isTrue()

        draft.restore(taken.single())
        assertThat(draft.list().map { it.draftId }).containsExactly("edit-7")
    }

    @Test
    fun `draft 가 없으면 겹쳐도 그대로다`() {
        val saved = listOf(current(7, "7"))
        assertThat(AuthoringDraft().overlay(saved)).isSameAs(saved)
    }

    @Test
    fun `draft 만 고치는 턴은 draft 만 보이고 draft 번호로만 고친다`() {
        val draft = AuthoringDraft()
        draft.accept(null, listOf(result("새 흐름", 1)), emptyList())

        assertThat(draft.draftsOnly().map { it.scenarioId to it.title }).containsExactly(-1L to "새 흐름")
        // 번호 없이 오면 draft 가 하나일 때만 그 draft 다.
        assertThat(draft.draftSlot(null)).isEqualTo(DraftSlot.Existing(-1))
        assertThat(draft.draftSlot(-1)).isEqualTo(DraftSlot.Existing(-1))
        // 저장된 시나리오 번호나 없는 번호는 받지 않는다.
        assertThat(draft.draftSlot(7)).isEqualTo(DraftSlot.Refused)
        assertThat(draft.draftSlot(-9)).isEqualTo(DraftSlot.Refused)
        // 이번 턴에 이미 고친 draft 는 번호 없는 다음 시나리오가 덮지 않는다 — 새 draft 다.
        assertThat(draft.draftSlot(null, targeted = setOf(-1L))).isEqualTo(DraftSlot.New)

        draft.accept(7, listOf(result("고친 흐름", 2, id = 7)), emptyList())
        assertThat(draft.draftsOnly().map { it.scenarioId }).containsExactly(-1L, 7L)
        assertThat(draft.draftSlot(7)).isEqualTo(DraftSlot.Existing(7))
        // draft 가 여럿이면 번호 없이 온 것은 새 draft 다.
        assertThat(draft.draftSlot(null)).isEqualTo(DraftSlot.New)
    }

    @Test
    fun `바뀐 자리를 칠할 기준은 직전 draft 이고, 기존 시나리오를 처음 고치면 그 시나리오다`() {
        val draft = AuthoringDraft()
        val saved = listOf(ChatScenarioStep(action = "saved", caseId = 7))

        draft.accept(null, listOf(result("새 흐름", 1)), emptyList())
        draft.accept(7, listOf(result("고친 흐름", 2, id = 7)), emptyList(), saved)
        assertThat(draft.list().map { it.draftId to it.baseSteps?.map { s -> s.action } })
            .containsExactly("new-1" to null, "edit-7" to listOf("saved"))

        draft.accept(-1, listOf(result("다시 쓴 새 흐름", 1, 3)), emptyList())
        draft.accept(7, listOf(result("또 고친 흐름", 4, id = 7)), emptyList(), saved)
        assertThat(draft.list().map { it.draftId to it.baseSteps?.map { s -> s.action } })
            .containsExactly("new-1" to listOf("do 1"), "edit-7" to listOf("do 2"))
    }

    private fun freeResult(title: String, id: Long? = null) =
        result(title, 1, id = id).copy(executionMode = ScenarioExecutionMode.FREE)

    @Test
    fun `결과가 FREE 면 draft 도 FREE 이고, 방식이 없는 새 draft 는 ORDERED 다`() {
        val draft = AuthoringDraft()

        draft.accept(null, listOf(freeResult("자유 흐름"), result("보통 흐름", 2)), emptyList())

        assertThat(draft.list().map { it.executionMode })
            .containsExactly(ScenarioExecutionMode.FREE, ScenarioExecutionMode.ORDERED)
    }

    @Test
    fun `방식이 없는 결과는 고치는 시나리오의 저장된 방식을 따르고, 이미 있는 draft 는 그 방식을 지킨다`() {
        val draft = AuthoringDraft()

        draft.accept(7, listOf(result("고친 흐름", 1, id = 7)), emptyList(), savedMode = ScenarioExecutionMode.FREE)
        assertThat(draft.list().single().executionMode).isEqualTo(ScenarioExecutionMode.FREE)

        // 저장된 방식을 다시 주지 않아도 이미 있는 draft 의 방식이 남는다.
        draft.accept(7, listOf(result("또 고친 흐름", 2, id = 7)), emptyList())
        assertThat(draft.list().single().executionMode).isEqualTo(ScenarioExecutionMode.FREE)

        // 새 draft 는 저장된 방식을 물려받지 않는다.
        draft.accept(null, listOf(result("새 흐름", 3)), emptyList(), savedMode = ScenarioExecutionMode.FREE)
        assertThat(draft.list().last().executionMode).isEqualTo(ScenarioExecutionMode.ORDERED)
    }

    @Test
    fun `사람이 본문을 고쳐도 방식은 그대로이고, 겹친 모습과 승인할 결과에도 실린다`() {
        val draft = AuthoringDraft()
        draft.accept(7, listOf(freeResult("자유 흐름", id = 7)), emptyList())

        val edited = draft.update("edit-7", DraftScenarioEdit(title = "사람이 고침"))!!

        assertThat(edited.executionMode).isEqualTo(ScenarioExecutionMode.FREE)
        assertThat(edited.toResult().executionMode).isEqualTo(ScenarioExecutionMode.FREE)
        assertThat(draft.overlay(listOf(current(7, "7 원본"))).single().executionMode)
            .isEqualTo(ScenarioExecutionMode.FREE)
        assertThat(draft.draftsOnly().single().executionMode).isEqualTo(ScenarioExecutionMode.FREE)
    }
}
