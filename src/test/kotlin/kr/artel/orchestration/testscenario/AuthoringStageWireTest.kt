package kr.artel.orchestration.testscenario

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.testscenario.dto.AuthoringStage
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 진행 단계의 전선 값과 수(ARTEL-952).
 *
 * 이 계약이 깨지면 화면이 조용히 틀린다 — 단계 이름이 안 맞으면 Agent 가 보낸 줄이 버려지고,
 * 수가 안 실리면 묶음 여럿을 쓰는 동안 화면이 한 줄로 멈춰 있는다. 둘 다 터지지 않고 그냥
 * 안 보이는 종류라, 검사로 박아 두지 않으면 아무도 모른다.
 *
 * 왜 노드가 필요했나. 런 87 trace(2026-10-06)에서 턴 하나에 진행 줄이 둘뿐이었고 그 사이가
 * **25.7초·50.3초**(묶기)와 **29.4초·54.2초**(문장 쓰기)였다. 사용자는 멈춘 것으로 읽었다.
 */
class AuthoringStageWireTest {

    private val mapper = ObjectMapper()

    @Test
    fun `Agent 가 보내는 노드 단계를 전선 값으로 찾을 수 있다`() {
        // Agent `app/agents/scenario/progress.py` 가 내보내는 값들. 한쪽만 고치면 그 줄이
        // 버려지므로(모르는 단계는 흘려보낸다) 양쪽이 같은 글자여야 한다.
        val fromAgent = listOf("grouping", "grouped", "bridging", "writing", "saving", "modifying", "thinking")
        for (wire in fromAgent) {
            assertThat(AuthoringStage.entries.firstOrNull { it.wire == wire })
                .withFailMessage("Agent 가 보내는 '%s' 를 오케가 모른다 — 그 줄은 화면에 안 나온다", wire)
                .isNotNull()
        }
    }

    @Test
    fun `셀 것이 없는 단계는 수가 null 로 온다`() {
        val json = mapper.writeValueAsString(
            ScenarioStreamEvent(type = "progress", stage = AuthoringStage.GROUPING)
        )
        // 이 DTO 는 null 을 그대로 싣는다(다른 칸들도 그렇다). 중요한 것은 **0 이 아니라는 것** —
        // `0` 으로 실리면 화면이 "0개 중 0번째" 를 그린다.
        assertThat(json).contains("\"done\":null", "\"total\":null")
    }

    @Test
    fun `문장 쓰기는 분자와 분모를 함께 싣는다`() {
        val json = mapper.writeValueAsString(
            ScenarioStreamEvent(type = "progress", stage = AuthoringStage.WRITING, done = 3, total = 7)
        )
        assertThat(json).contains("\"stage\":\"writing\"", "\"done\":3", "\"total\":7")
    }

    @Test
    fun `묶기가 끝나면 분모만 수로 온다`() {
        val json = mapper.writeValueAsString(
            ScenarioStreamEvent(type = "progress", stage = AuthoringStage.GROUPED, total = 4)
        )
        // 분자는 아직 없다 — 쓰기를 시작하지도 않았다.
        assertThat(json).contains("\"stage\":\"grouped\"", "\"total\":4", "\"done\":null")
    }
}
