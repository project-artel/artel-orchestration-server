package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.service.TurnDeadline
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **재는 것은 "죽었나"이지 "느린가"가 아니다**(ARTEL-632).
 *
 * 실측(런 179)에서 저작이 도구를 47번 부르며 일하는 동안 정각 5분에 시한이 났다. 사용자가 본
 * 것은 "끝나지 않았습니다"였고, 결과는 그 뒤에 도착해 조용히 저장됐다 — 물어야 할 것 셋도 그
 * 턴과 함께 사라졌다.
 */
class TurnDeadlineTest {

    private val deadline = 300_000L

    /** 소식이 있으면 그만큼 더 기다린다. 이것이 이 규칙의 전부다. */
    @Test
    fun `방금 들었으면 시한만큼 더 기다린다`() {
        assertThat(TurnDeadline.remainingWait(lastHeard = 1_000, now = 1_000, deadline = deadline))
            .isEqualTo(deadline)
    }

    /** 절반쯤 조용했으면 남은 절반만 기다린다 — 매번 처음부터 다시 세지 않는다. */
    @Test
    fun `조용한 만큼을 빼고 기다린다`() {
        assertThat(TurnDeadline.remainingWait(lastHeard = 0, now = 120_000, deadline = deadline))
            .isEqualTo(180_000)
    }

    /** 시한만큼 아무 소식이 없으면 그때 멎은 것으로 본다. */
    @Test
    fun `아무 소식 없이 시한이 차면 멎은 것으로 본다`() {
        assertThat(TurnDeadline.remainingWait(lastHeard = 0, now = deadline, deadline = deadline)).isNull()
        assertThat(TurnDeadline.remainingWait(lastHeard = 0, now = deadline + 1, deadline = deadline)).isNull()
    }

    /**
     * **일하는 턴은 안 끊긴다.** 4분마다 무언가 들리면 40분이 지나도 살아 있는 것이다 —
     * 도구를 마흔 번 부르는 턴이 실측에서 그랬다.
     */
    @Test
    fun `계속 들리면 얼마가 지나도 안 끊는다`() {
        var lastHeard = 0L
        repeat(10) { turn ->
            val now = (turn + 1) * 240_000L
            assertThat(TurnDeadline.remainingWait(lastHeard, now, deadline)).isNotNull()
            lastHeard = now
        }
    }

    /** 시계가 뒤로 가면 한 바퀴를 다시 기다린다 — 음수만큼 자면 헛돈다. */
    @Test
    fun `미래에서 들은 것으로 적혀도 헛돌지 않는다`() {
        assertThat(TurnDeadline.remainingWait(lastHeard = 10_000, now = 0, deadline = deadline))
            .isEqualTo(deadline)
    }
}
