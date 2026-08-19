package kr.artel.orchestration.contentmap.render

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MethodRendererTest {

    private val mapper = ObjectMapper()
    private fun node(json: String) = mapper.readTree(json)
    private val emptyWiring = WiringIndex.build(EvidenceDocument.parse("""{"types":{},"unplaced":{},"objects":[],"gaps":[],"scenes":[],"build":{}}"""))

    @Test
    fun `logicalSig 는 상태머신 MoveNext 를 원래 코루틴 메서드로 되접는다`() {
        val sigByName = mapOf(
            ("Combat.Enemies.BattleWaveController" to "WaveEndSensor") to
                "System.Collections.IEnumerator Combat.Enemies.BattleWaveController::WaveEndSensor()",
        )
        val record = node(
            """{"source":"System.Boolean Combat.Enemies.BattleWaveController/<WaveEndSensor>d__6::MoveNext()"}""",
        )

        assertThat(logicalSig(record, sigByName))
            .isEqualTo("System.Collections.IEnumerator Combat.Enemies.BattleWaveController::WaveEndSensor()")
    }

    @Test
    fun `상태머신이 아닌 서명은 그대로 되접지 않는다`() {
        val record = node("""{"source":"System.Void Combat.Enemies.BattleWaveController::StartWave(System.Int32)"}""")

        assertThat(logicalSig(record, emptyMap())).isEqualTo("System.Void Combat.Enemies.BattleWaveController::StartWave(System.Int32)")
    }

    @Test
    fun `조회 테이블에 없는 상태머신은 IEnumerator 시그니처를 합성한다`() {
        val record = node("""{"source":"System.Boolean NS.T/<Coro>d__1::MoveNext()"}""")

        assertThat(logicalSig(record, emptyMap())).isEqualTo("System.Collections.IEnumerator NS.T::Coro()")
    }

    /**
     * 실측 `Combat.Enemies.Player::TakeHit(int)` 모양 — 같은 효과문(`X = 1;`)을 내는 두
     * 레코드 중 하나는 `always`, 하나는 조건이 subjectLost. fix 3 적용 후 둘 다 code=null 이
     * 되어 같은 변형으로 합쳐져야 하고(중복 if 블록 두 개가 아니라 무조건 실행문 하나),
     * subjectLost 쪽이 낸 주석은 합쳐진 뒤에도 살아남아야 한다(2차 plan-review 가 실측으로
     * 잡은 버그 시나리오).
     */
    @Test
    fun `같은 몸통을 내는 두 레코드 중 하나만 subjectLost 면 하나로 합쳐지고 주석은 남는다`() {
        val always = node(
            """
            {
              "entry": "System.Void NS.T::M()",
              "condition": {"kind": "always"},
              "effects": [{"kind": "write", "target": "T.X", "detail": "1", "offset": 9}],
              "callPath": ["System.Void NS.T::M()"]
            }
            """.trimIndent(),
        )
        val subjectLost = node(
            """
            {
              "entry": "System.Void NS.Other::N()",
              "condition": {"kind": "test", "left": "distanceToPlayer", "operator": "<", "right": "T.attackRange", "subjectLost": "disagree:arg:0/this"},
              "effects": [{"kind": "write", "target": "T.X", "detail": "1", "offset": 9}],
              "callPath": ["System.Void NS.Other::N()", "System.Void NS.T::M()"]
            }
            """.trimIndent(),
        )

        val lines = renderMethod("System.Void NS.T::M()", listOf(always, subjectLost), "    ", emptyWiring)
        val body = lines.joinToString("\n")

        // 몸통 문장은 한 번만 나온다(두 개의 별도 if 블록으로 쪼개지지 않는다).
        assertThat(body.split("T.X = 1;")).hasSize(2)
        // subjectLost 로 접힌 조건의 주석은 살아남는다.
        assertThat(body).contains("unresolved condition (subject lost): distanceToPlayer < T.attackRange")
        // code 가 없으므로 fake if 로 나오지 않는다.
        assertThat(body).doesNotContain("if (distanceToPlayer")
    }

    @Test
    fun `조건도 몸통도 완전히 같은 레코드는 하나로 합쳐진다`() {
        fun record() = node(
            """
            {
              "entry": "System.Void NS.T::M()",
              "condition": {"kind": "test", "left": "x", "operator": "==", "right": "1"},
              "effects": [{"kind": "write", "target": "T.Y", "detail": "2", "offset": 3}],
              "callPath": ["System.Void NS.T::M()"]
            }
            """.trimIndent(),
        )

        val lines = renderMethod("System.Void NS.T::M()", listOf(record(), record()), "    ", emptyWiring)
        val body = lines.joinToString("\n")

        assertThat(body.split("if (x == 1)")).hasSize(2)
    }
}
