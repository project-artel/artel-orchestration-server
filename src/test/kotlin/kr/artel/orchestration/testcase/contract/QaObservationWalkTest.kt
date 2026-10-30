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
    fun `TC 를 실행하는 동안 바뀐 게임 값을 남긴다`() {
        fun values(lamp: Boolean, coins: Int) = row(
            "PULSE",
            """{"scene":"SquareScene","statics":[{"declaring":"Inventory","member":"hasLamp","type":"System.Boolean","value":$lamp},
               {"declaring":"Inventory","member":"coins","type":"System.Int32","value":$coins},
               {"declaring":"Account.AccountSession","member":"MemberName","type":"System.String","value":"x"},
               {"declaring":"Game","member":"Instance","type":"Game","value":{"is":"Game"}}]}""",
        )
        val (observed, _) = QaObservationService.walk(listOf(values(false, 0), key("E"), values(true, 20), done(1, 7)))
        assertThat(observed.single().changed).containsExactlyInAnyOrderEntriesOf(mapOf("Inventory.hasLamp" to "false→true", "Inventory.coins" to "0→20"))
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

    /** QA 103 의 실제 모양: 클릭이 move_mouse · mouse_down · mouse_up 묶음으로 가고, 누른 것의 경로에 띄어쓰기가 있다. */
    @Test
    fun `띄어쓰기가 든 UI 경로를 누른 클릭도 출구가 된다`() {
        val result = row(
            "TOOL_RESULT",
            """{"content":"  move_mouse: ok\n  mouse_down: Canvas/Travel Bar/이동: 숲 에 보냄 — 반응했는지는 화면으로 확인할 것\n  mouse_up: ok\n\n<<pulse>>"}""",
        )
        val (observed, exits) = QaObservationService.walk(
            listOf(pulse("SquareScene"), press(), pulse("ForestScene"), result, done(4, 20)),
        )
        assertThat(exits).containsExactly(QaObservationService.Exit("SquareScene", "ForestScene", "click:Canvas/Travel Bar/이동: 숲"))
        assertThat(observed.single().input).isEqualTo("click:Canvas/Travel Bar/이동: 숲")
    }

    /** 누른 것은 SDK 가 보낸 액션 결과로 읽는다. 도구 결과 문장이 바뀌거나 없어도 같다. */
    @Test
    fun `누른 UI 는 SDK 의 액션 결과에서 읽고 전해지지 않은 클릭은 출구가 아니다`() {
        fun sdk(reached: String, held: Boolean = false) = row(
            "ACTION_RESULT",
            """{"type":"ACTION_RESULT","results":[{"id":1,"success":true},{"id":2,"success":true,"returnValue":{"reached":"$reached","pointerHeldByPerson":$held}},{"id":3,"success":true}]}""",
        )
        val (_, exits) = QaObservationService.walk(
            listOf(
                pulse("SquareScene"), press(), sdk("Canvas/Travel Bar/이동: 숲"), pulse("ForestScene"), done(1, 1),
                press(), pulse("SquareScene"), sdk("Canvas/Travel Bar/이동: 광장"),
                // 같은 결과를 도구 결과 문장으로도 읽는다. 이미 정한 클릭은 바꾸지 않는다.
                row("TOOL_RESULT", """{"content":"  mouse_down: 전해지지 않음 — 포인터를 사람이 쥐고 있다"}"""), done(2, 2),
                press(), pulse("SmithyScene"), sdk("", held = true), done(3, 3),
            ),
        )
        assertThat(exits).containsExactly(
            QaObservationService.Exit("SquareScene", "ForestScene", "click:Canvas/Travel Bar/이동: 숲"),
            QaObservationService.Exit("ForestScene", "SquareScene", "click:Canvas/Travel Bar/이동: 광장"),
        )
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
