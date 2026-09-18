package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.service.ScenarioQuestionBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 되묻는 것을 고른다(ARTEL-487).
 *
 * **묻는 것은 Gap 하나뿐이다**(ARTEL-903). 앞서는 셋을 물었고(구간·갈래·담은 범위) 그중 하나를
 * 고르는 것이 이 테스트의 절반이었는데, 뒤의 둘을 걷어냈다. 기준은 **답이 시나리오를 바꾸는가** —
 * Gap 은 답이 곧 스텝이 되고, 나머지 둘은 답해도 이 시나리오가 달라지지 않는다. 씬별 범위는
 * 씬이 저작의 단위가 아니게 된 뒤로 쓸 수 없는 수였고, 빠진 갈래는 알림으로 남는다
 * ([kr.artel.orchestration.testscenario.service.ScenarioReconcileService] 의 `siblingNotices`).
 */
class ScenarioQuestionBuilderTest {

    @Test
    fun `물을 것이 없으면 묻지 않는다`() {
        assertThat(ScenarioQuestionBuilder.from(emptyList())).isNull()
    }

    @Test
    fun `메우지 못한 구간을 묻는다`() {
        // 답이 곧 스텝이 되는 물음이다. 답하지 않으면 실행하는 사람이 거기서 멎는다.
        val question = ScenarioQuestionBuilder.from(listOf("StoryScene→Map_scene"))

        assertThat(question?.id).isEqualTo("gap:StoryScene→Map_scene")
        assertThat(question?.text).contains("StoryScene→Map_scene")
        // 어떻게 가는지는 보기로 다 담기지 않는다 — 자유 서술이 본체다.
        assertThat(question?.allowFreeText).isTrue()
        assertThat(question?.options?.map { it.id }).containsExactly("auto", "leave")
    }

    /**
     * **모르는 자리를 전부 낸다**(ARTEL-630).
     *
     * 하나만 내면 나머지는 아무 말 없이 미상으로 남고, 사용자는 시나리오가 완성된 줄 안다 —
     * 실측(런 178)에서 못 간다고 적은 자리가 일곱인데 물은 것은 하나였다.
     *
     * 하나만 묻던 것은 같은 질문이 매 턴 다시 나가는 것을 막으려던 것이었는데(런 152), 그건
     * **답한 질문을 다시 안 묻는 것**으로 풀 일이지 모르는 것을 감춰서 풀 일이 아니다.
     */
    @Test
    fun `막힌 자리가 여럿이면 여럿을 낸다`() {
        val questions = ScenarioQuestionBuilder.all(
            listOf("stagePosition", "activeSelf", "Map_scene→TurnBattleScene"),
        )

        assertThat(questions).hasSize(3)
        assertThat(questions.map { it.id })
            .containsExactly("gap:stagePosition", "gap:activeSelf", "gap:Map_scene→TurnBattleScene")
        // 옛 화면이 읽는 한 개짜리 칸은 그중 첫 것이다.
        assertThat(ScenarioQuestionBuilder.from(listOf("stagePosition", "activeSelf"))?.id)
            .isEqualTo("gap:stagePosition")
    }

    /** 같은 자리를 두 번 묻지 않는다 — 질문지가 길어질수록 중복이 눈에 띈다. */
    @Test
    fun `같은 자리는 한 번만 묻는다`() {
        assertThat(ScenarioQuestionBuilder.all(listOf("stagePosition", "stagePosition"))).hasSize(1)
    }

    /**
     * 씬별 범위와 빠진 갈래는 **어떤 입력으로도 질문이 되지 않는다.**
     *
     * 걷어낸 것을 "안 부르면 안 나온다" 로 두면 부르는 자리가 하나 생기는 날 조용히 돌아온다.
     * 만드는 함수 자체가 없다는 것을 계약으로 적어 둔다 — 이 검사가 깨지는 방법은 그 함수를
     * 다시 만드는 것뿐이다.
     */
    @Test
    fun `묻는 갈래는 Gap 하나뿐이다`() {
        val kinds = ScenarioQuestionBuilder.all(listOf("stagePosition", "A→B"))
            .map { it.id.substringBefore(":") }
            .distinct()

        assertThat(kinds).containsExactly("gap")
        assertThat(ScenarioQuestionBuilder::class.java.declaredMethods.map { it.name })
            .noneMatch { it.contains("scope", ignoreCase = true) || it.contains("arm", ignoreCase = true) }
    }
}
