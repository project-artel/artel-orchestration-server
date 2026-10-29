package kr.artel.orchestration.testcase.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class QaObservationWalkTest {
    private val json = ObjectMapper()
    private fun row(type: String, payload: String): Pair<String, JsonNode> = type to json.readTree(payload)
    private fun pulse(scene: String) = row("PULSE", """{"scene":"$scene"}""")
    private fun key(k: String) = row("ACTION", """{"step":1,"actions":[{"method":"key_click","params":["$k",0.1]}]}""")
    private fun press() = row("ACTION", """{"step":1,"actions":[{"method":"mouse_down","params":[0]}]}""")
    private fun click(path: String) = row("TOOL_RESULT", """{"step":null,"content":"  mouse_down: $path 에 보냄 — 반응했는지는 화면으로 확인할 것"}""")
    private fun cheat() = row("ACTION", """{"step":1,"actions":[{"method":"invoke_cheat","params":[{"name":"go_to_scene"}]}]}""")
    private fun done(step: Int, caseId: Long?, status: String = "COMPLETED") =
        row("STATUS", """{"step":$step,"case_id":${caseId ?: "null"},"status":"$status","is_verification":true}""")

    @Test
    fun `입력으로 바뀐 씬은 출구와 도착이 되고 치트로 바뀐 씬은 출구가 아니다`() {
        val (observed, exits) = QaObservationService.walk(
            listOf(
                pulse("TitleScene"), press(), pulse("SquareScene"), click("Canvas/StartButton"), done(1, 10),
                key("Return"), done(2, 11),
                cheat(), pulse("ForestScene"), done(3, 12),
                key("Escape"), pulse("SquareScene"), done(4, 13, "FAILED"),
            ),
        )
        assertThat(exits).containsExactly(
            QaObservationService.Exit("TitleScene", "SquareScene", "click:Canvas/StartButton"),
            QaObservationService.Exit("ForestScene", "SquareScene", "key:Escape"),
        )
        assertThat(observed).containsExactly(
            QaObservationService.Observed(10, "TitleScene", "SquareScene", "click:Canvas/StartButton"),
            QaObservationService.Observed(11, "SquareScene", null, "key:Return"),
            // 치트로 넘어간 씬도 도착은 도착이다 — 다만 입력은 없다.
            QaObservationService.Observed(12, "SquareScene", "ForestScene", null),
        )
    }

    @Test
    fun `전해지지 않은 클릭 뒤에 바뀐 씬은 출구가 아니다`() {
        val (_, exits) = QaObservationService.walk(
            listOf(
                pulse("A"), press(), pulse("B"),
                row("TOOL_RESULT", """{"content":"  mouse_down: 전해지지 않음 — 포인터를 사람이 쥐고 있다"}"""),
                done(1, 1),
            ),
        )
        assertThat(exits).isEmpty()
    }

    @Test
    fun `입력 뒤 씬이 한 번 바뀌면 다음 저절로 바뀐 씬은 그 입력 탓이 아니다`() {
        val (_, exits) = QaObservationService.walk(
            listOf(pulse("A"), key("Return"), pulse("B"), pulse("C"), done(1, 1)),
        )
        assertThat(exits).containsExactly(QaObservationService.Exit("A", "B", "key:Return"))
    }

    /** 로컬 실측용. `-Dqa.log=<tsv>` 일 때만 돈다. */
    @Test
    fun `실제 로그`() {
        val path = System.getProperty("qa.log") ?: return
        val rows = File(path).readLines().map { it.substringBefore('\t') to json.readTree(it.substringAfter('\t').replace("\\\\", "\\")) }
        val (observed, exits) = QaObservationService.walk(rows)
        observed.forEach { println("OBS $it") }
        exits.forEach { println("EXIT $it") }
    }
}
