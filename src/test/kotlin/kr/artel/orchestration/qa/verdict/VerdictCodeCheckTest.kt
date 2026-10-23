package kr.artel.orchestration.qa.verdict

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VerdictCodeCheckTest {
    private val mapper = ObjectMapper()

    private fun state(vararg pulses: String) = PulseState().apply { pulses.forEach { fold(mapper.readTree(it)) } }

    private fun key(vararg effects: List<String>) = (listOf("feature") + effects.flatMap { listOf("h") + it }).joinToString("\u001f")

    @Test
    fun `case_key 의 효과를 다섯 칸씩 읽고 화면 케이스는 따로 읽는다`() {
        assertThat(CaseKeyEffects.parse(key(listOf("active-state", "Zone.panel", "true", ""), listOf("scene", "Map", "", ""))))
            .containsExactly(CaseEffect("active-state", "Zone.panel", "true", ""), CaseEffect("scene", "Map", "", ""))
        assertThat(CaseKeyEffects.parse("screen\u001fTitle\u001fCanvas/Start"))
            .containsExactly(CaseEffect("screen", "Title", "Canvas/Start", ""))
    }

    private val panelOff = """{"scene":"S","active":[{"selector":"Zone[1]","path":"Zone","by":[{"on":"Game.Zone","m":[{"member":"panel","value":{"active":false}}]}]}]}"""
    private val panelOn = """{"active":[{"selector":"Zone[1]","path":"Zone","by":[{"on":"Game.Zone","m":[{"member":"panel","value":{"active":true}}]}]}]}"""

    @Test
    fun `표시 상태는 기록한 값으로 확정하고, 기록에 없으면 확인하지 못한다`() {
        val effects = CaseKeyEffects.parse(key(listOf("active-state", "Zone.panel", "true", "")))
        assertThat(VerdictCodeChecker.check(effects, state(panelOn), PulseState()).result).isEqualTo(CodeVerdict.PASS)
        assertThat(VerdictCodeChecker.check(effects, state(panelOff), PulseState()).result).isEqualTo(CodeVerdict.FAIL)
        assertThat(VerdictCodeChecker.check(effects, state("""{"scene":"S"}"""), PulseState()).result).isEqualTo(CodeVerdict.UNKNOWN)
    }

    @Test
    fun `입력 계기 효과는 앞 판정 때 이미 그 상태였으면 확정하지 않는다`() {
        val effects = CaseKeyEffects.parse(key(listOf("active-state", "Zone.panel", "true", "gesture|click")))
        assertThat(VerdictCodeChecker.check(effects, state(panelOff, panelOn), state(panelOff)).result).isEqualTo(CodeVerdict.PASS)
        assertThat(VerdictCodeChecker.check(effects, state(panelOn), state(panelOn)).result).isEqualTo(CodeVerdict.UNKNOWN)
    }

    @Test
    fun `글자 기대가 식이면 바뀌었는지만 본다`() {
        val label = { text: String -> """{"active":[{"selector":"Hp[2]","path":"Hp","by":[{"on":"Game.Player","m":[{"member":"HpText","value":{"label":"$text"}}]}]}]}""" }
        val effects = CaseKeyEffects.parse(key(listOf("ui-value", "Player.HpText.text", "hp.ToString()", "")))
        assertThat(VerdictCodeChecker.check(effects, state(label("90")), state(label("100"))).result).isEqualTo(CodeVerdict.WEAK_PASS)
        assertThat(VerdictCodeChecker.check(effects, state(label("100")), state(label("100"))).result).isEqualTo(CodeVerdict.WEAK_FAIL)
        val exact = CaseKeyEffects.parse(key(listOf("ui-value", "Player.HpText.text", "\"100\"", "")))
        assertThat(VerdictCodeChecker.check(exact, state(label("100")), PulseState()).result).isEqualTo(CodeVerdict.PASS)
    }

    @Test
    fun `코드가 확인한 통과만 믿고, 코드가 통과로 확정한 실패를 다시 본다`() {
        val pass = CodeCheck(CodeVerdict.PASS, emptyList())
        val weak = CodeCheck(CodeVerdict.WEAK_PASS, emptyList())
        val unknown = CodeCheck(CodeVerdict.UNKNOWN, emptyList())
        assertThat(needsReview(isVerification = true, passed = true, check = pass)).isFalse()
        assertThat(needsReview(isVerification = true, passed = true, check = weak)).isTrue()
        assertThat(needsReview(isVerification = true, passed = true, check = unknown)).isTrue()
        assertThat(needsReview(isVerification = true, passed = false, check = pass)).isTrue()
        assertThat(needsReview(isVerification = true, passed = false, check = unknown)).isFalse()
        assertThat(needsReview(isVerification = false, passed = true, check = null)).isFalse()
    }
}
