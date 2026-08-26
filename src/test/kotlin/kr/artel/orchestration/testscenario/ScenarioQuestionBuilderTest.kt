package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.service.ScenarioQuestionBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 되묻는 한 가지를 고른다(ARTEL-487).
 *
 * **하나만 묻는다**는 것과 **그 하나를 무엇으로 고르는가**가 이 테스트의 전부다. 여러 개를 쌓으면
 * 사용자는 어느 것에 답한 것인지 말해 줄 방법이 없다.
 */
class ScenarioQuestionBuilderTest {

    @Test
    fun `물을 것이 없으면 묻지 않는다`() {
        assertThat(ScenarioQuestionBuilder.from(emptyList(), emptyList(), emptyList())).isNull()
    }

    @Test
    fun `메우지 못한 구간을 가장 먼저 묻는다`() {
        // 실행하면 거기서 멎는다. 나머지 둘은 결과가 좁을 뿐 돌아는 간다.
        val question = ScenarioQuestionBuilder.from(
            blockedGaps = listOf("StoryScene→Map_scene"),
            untestedArms = listOf(133L to 134L),
            scope = listOf(Triple("TitleScene", 2, 5)),
        )

        assertThat(question?.id).isEqualTo("gap:StoryScene→Map_scene")
        assertThat(question?.text).contains("StoryScene→Map_scene")
        // 어떻게 가는지는 보기로 다 담기지 않는다 — 자유 서술이 본체다.
        assertThat(question?.allowFreeText).isTrue()
    }

    @Test
    fun `구간이 여럿이면 하나만 묻는다`() {
        val question = ScenarioQuestionBuilder.from(
            blockedGaps = listOf("A→B", "StagePosition"),
            untestedArms = emptyList(),
            scope = emptyList(),
        )

        assertThat(question?.id).isEqualTo("gap:A→B")
    }

    @Test
    fun `구간이 없으면 빠진 갈래를 묻는다`() {
        val question = ScenarioQuestionBuilder.from(
            blockedGaps = emptyList(),
            untestedArms = listOf(133L to 134L),
            scope = listOf(Triple("TitleScene", 2, 5)),
            describe = { id -> "StoryScene · 진입해 관찰한다 (StagePosition ${if (id == 134L) "==" else "!="} 5)" },
        )

        // **번호로 부르지 않는다.** 내부 case_id 는 사용자가 읽는 글에 넣지 않는다 — 화면은
        // 등장 순번만 쓰고 에이전트 프롬프트에도 같은 금지가 있다. id 는 `id` 필드로만 오간다.
        assertThat(question?.id).isEqualTo("arm:133:134")
        assertThat(question?.text).contains("StagePosition == 5").doesNotContain("134번")
        assertThat(question?.why).contains("동시에 성립할 수 없어")
        assertThat(question?.options?.map { it.id }).containsExactly("add", "skip")
    }

    @Test
    fun `마지막으로 담은 범위를 묻는다`() {
        val question = ScenarioQuestionBuilder.from(
            blockedGaps = emptyList(),
            untestedArms = emptyList(),
            scope = listOf(Triple("TitleScene", 2, 5), Triple("Map_scene", 4, 21)),
        )

        assertThat(question?.id).isEqualTo("scope:TitleScene,Map_scene")
        assertThat(question?.why).contains("TitleScene 2/5").contains("Map_scene 4/21")
        // 씬마다 보기를 준다 — "나머지 전부"만 주면 스물몇 건짜리 씬까지 딸려 온다.
        assertThat(question?.options?.map { it.id })
            .containsExactly("scene:TitleScene", "scene:Map_scene", "keep")
        assertThat(question?.options?.first()?.label).isEqualTo("TitleScene 마저 담기")
    }

    @Test
    fun `보기 문구는 사용자가 할 말 그대로다`() {
        // 고른 답은 이 문장으로 모델에게 되돌아간다. 모델이 따로 해석할 것이 없어야 오케에
        // 새 실행 경로를 만들지 않아도 된다.
        val question = ScenarioQuestionBuilder.from(
            blockedGaps = emptyList(),
            untestedArms = listOf(133L to 134L),
            scope = emptyList(),
            describe = { "Map_scene · 관찰한다 (StagePosition == 5)" },
        )

        // 보기는 짧게. 무엇에 대한 답인지는 오케가 질문 문장을 붙여 보낸다 — 버튼에 질문을
        // 통째로 옮겨 적으면 화면에서 세 줄로 접힌다.
        assertThat(question?.options?.first()?.label).isEqualTo("네, 만들어 주세요")
        assertThat(question?.options?.first()?.detail).contains("StagePosition == 5")
    }
}
