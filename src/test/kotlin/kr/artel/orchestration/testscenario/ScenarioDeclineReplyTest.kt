package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.dto.ScenarioQuestion
import kr.artel.orchestration.testscenario.service.ScenarioDeclineReply
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * "그대로 두기"를 코드가 받는다(ARTEL-487).
 *
 * 실제로 나온 증상이 기준이다 — 거절을 모델에게 넘겼더니 **방금 거절한 질문이 다시 나갔다.**
 * 그래서 여기서 보는 것은 둘이다: 무엇이 거절인가, 거절했을 때 사용자에게 다음 수가 남는가.
 */
class ScenarioDeclineReplyTest {

    private fun question(id: String) = ScenarioQuestion(id = id, text = "물음")

    @Test
    fun `그대로 두기류 보기만 거절이다`() {
        val gap = question("gap:StoryScene→Map_scene")

        assertThat(ScenarioDeclineReply.isDecline(gap, listOf("leave"), said = false)).isTrue()
        assertThat(ScenarioDeclineReply.isDecline(question("scope:Map_scene"), listOf("keep"), said = false)).isTrue()
        assertThat(ScenarioDeclineReply.isDecline(question("arm:1:2"), listOf("skip"), said = false)).isTrue()
        // 하겠다는 답은 모델이 할 일이 있다.
        assertThat(ScenarioDeclineReply.isDecline(gap, listOf("auto"), said = false)).isFalse()
        assertThat(ScenarioDeclineReply.isDecline(question("conflict:1-2"), listOf("split"), said = false)).isFalse()
    }

    @Test
    fun `한 마디라도 덧붙였으면 거절이 아니다`() {
        // "그대로 둘게요, 대신 전투는 빼 줘"는 요청이다. 코드가 삼키면 사용자가 한 말이 사라진다.
        assertThat(
            ScenarioDeclineReply.isDecline(question("scope:Map_scene"), listOf("keep"), said = true)
        ).isFalse()
    }

    @Test
    fun `고른 것이 없으면 거절이 아니다`() {
        // 자유 서술만 보낸 답이다. 무엇을 뜻하는지는 모델이 읽는다.
        assertThat(ScenarioDeclineReply.isDecline(question("gap:A→B"), emptyList(), said = false)).isFalse()
    }

    @Test
    fun `질문이 남아 있지 않으면 거절로 다루지 않는다`() {
        assertThat(ScenarioDeclineReply.isDecline(null, listOf("keep"), said = false)).isFalse()
    }

    @Test
    fun `거절 문구는 무엇을 그대로 뒀는지와 다음 수를 함께 말한다`() {
        // 막다른 길이 되면 사용자는 마음이 바뀌었을 때 무엇을 말해야 하는지 스스로 지어내야 한다.
        assertThat(ScenarioDeclineReply.advice(question("gap:StoryScene→Map_scene")))
            .contains("StoryScene→Map_scene")
            .contains("스텝 추가")

        assertThat(ScenarioDeclineReply.advice(question("conflict:1284-1293,1285-1297")))
            .contains("2쌍")
            .contains("나눠 줘")

        assertThat(ScenarioDeclineReply.advice(question("arm:133:134"))).contains("갈래")
        assertThat(ScenarioDeclineReply.advice(question("scope:TitleScene,Map_scene"))).contains("씬 이름")
    }

    @Test
    fun `모델이 물은 것은 일반적인 말로만 답한다`() {
        // 무엇을 물었는지 코드가 모른다. 아는 척하는 다음 수를 지어내면 그게 더 나쁘다.
        assertThat(ScenarioDeclineReply.advice(question("agent:1f2e3d")))
            .isEqualTo("그대로 두었습니다. 필요해지면 다시 말씀해 주세요.")
    }
}
