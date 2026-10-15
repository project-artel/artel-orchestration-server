package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.CurrentScenario
import kr.artel.orchestration.testscenario.dto.DraftScenarioEdit
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
}
