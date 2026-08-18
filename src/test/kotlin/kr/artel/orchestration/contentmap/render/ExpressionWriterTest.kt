package kr.artel.orchestration.contentmap.render

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExpressionWriterTest {

    private val mapper = ObjectMapper()
    private fun node(json: String) = mapper.readTree(json)

    // ---- fix 1: 증분이 대입이 아니라 += 로 나온다 (양수 쪽만 — 아래 주석 참고) ----

    @Test
    fun `detail 이 플러스로 시작하면 플러스이퀄로 낸다`() {
        val effect = node(
            """{"kind":"write","target":"BattleWaveController.wave","detail":"+1"}""",
        )

        assertThat(effectStmt(effect)).isEqualTo("BattleWaveController.wave += 1;")
    }

    @Test
    fun `detail 이 마이너스로 시작해도 증분으로 지어내지 않고 대입으로 낸다`() {
        // 실측(_unplaced/Cards.Util.cs, Vector3 MousePos getter)에 `Vector3.z` write 의
        // detail 이 "-10" 인 진짜 사례가 있다 — 이건 z를 고정 깊이로 대입하는 코드지
        // 증분이 아니다. "+" 와 달리 "-" 는 증분(`-= N`)과 음수 리터럴 대입(`= -N`)을
        // detail 문자열만으로 구분할 수 없으므로, 확신 없는 연산자를 지어내지 않는다.
        val effect = node("""{"kind":"write","target":"Vector3.z","detail":"-10"}""")

        assertThat(effectStmt(effect)).isEqualTo("Vector3.z = -10;")
    }

    @Test
    fun `보통 write 는 그대로 대입문으로 낸다`() {
        val effect = node("""{"kind":"write","target":"MapMove.StagePosition","detail":"0"}""")

        assertThat(effectStmt(effect)).isEqualTo("MapMove.StagePosition = 0;")
    }

    // ---- fix 4: 지역변수는 필드와 구분되게 낸다 ----

    @Test
    fun `write 대상에 점이 없으면 지역변수로 보고 local 접두를 붙인다`() {
        val effect = node("""{"kind":"write","target":"waveEnd","detail":"1"}""")

        assertThat(effectStmt(effect)).isEqualTo("local waveEnd = 1;")
    }

    @Test
    fun `write 대상에 점이 있으면 필드로 보고 local 을 붙이지 않는다`() {
        val effect = node("""{"kind":"write","target":"BattleWaveController.ememyPool","detail":"this.gameObject.GetComponent()"}""")

        assertThat(effectStmt(effect)).isEqualTo("BattleWaveController.ememyPool = this.gameObject.GetComponent();")
    }

    @Test
    fun `saved 나 scene 같은 write 아닌 kind 는 점이 없어도 local 을 붙이지 않는다`() {
        val effect = node("""{"kind":"saved","target":"StagePosition","detail":"-1"}""")

        assertThat(effectStmt(effect)).isEqualTo("Save(\"StagePosition\", -1);")
    }

    // ---- fix 3: subjectLost 조건은 코드가 아니라 주석으로 강등한다 ----

    @Test
    fun `단독 조건이 subjectLost 면 code 는 null 이고 주석만 남는다`() {
        val condition = node(
            """{"kind":"test","left":"i","operator":"<","right":"objCount","subjectLost":"left:ldloc.s"}""",
        )

        val result = condExpr(condition)

        assertThat(result.code).isNull()
        assertThat(result.comments).containsExactly("unresolved condition (subject lost): i < objCount")
    }

    @Test
    fun `subjectLost 가 없는 test 조건은 평범한 code 로 낸다`() {
        val condition = node("""{"kind":"test","left":"Player.Hp","operator":"<=","right":"0"}""")

        val result = condExpr(condition)

        assertThat(result.code).isEqualTo("Player.Hp <= 0")
        assertThat(result.comments).isEmpty()
    }

    @Test
    fun `every 의 한 갈래만 subjectLost 면 그 갈래만 빠지고 주석은 살아남는다`() {
        val condition = node(
            """
            {"kind":"every","parts":[
                {"kind":"test","left":"damage","operator":">","right":"0"},
                {"kind":"test","left":"i","operator":"<","right":"CardManager.myCards.Count","subjectLost":"left:ldloc.1"}
            ]}
            """.trimIndent(),
        )

        val result = condExpr(condition)

        // 살아남은 갈래가 하나뿐이어도, 원본과 동일하게 공백이 있는 원자는 괄호로 감싼다
        // (매칭 쌍이 없어져도 표기 규칙 자체는 바뀌지 않는다 — 항상 원자 단위로 판단한다).
        assertThat(result.code).isEqualTo("(damage > 0)")
        assertThat(result.comments).containsExactly("unresolved condition (subject lost): i < CardManager.myCards.Count")
    }

    @Test
    fun `every 의 모든 갈래가 subjectLost 면 code 는 null 이고 주석이 순서대로 다 남는다`() {
        val condition = node(
            """
            {"kind":"every","parts":[
                {"kind":"test","left":"a","operator":"==","right":"1","subjectLost":"x"},
                {"kind":"test","left":"b","operator":"==","right":"2","subjectLost":"y"}
            ]}
            """.trimIndent(),
        )

        val result = condExpr(condition)

        assertThat(result.code).isNull()
        assertThat(result.comments).containsExactly(
            "unresolved condition (subject lost): a == 1",
            "unresolved condition (subject lost): b == 2",
        )
    }

    @Test
    fun `always 조건은 code 도 comments 도 없다`() {
        val result = condExpr(node("""{"kind":"always"}"""))

        assertThat(result.code).isNull()
        assertThat(result.comments).isEmpty()
    }

    // ---- call/input: 정상 경로 회귀 방지 ----

    @Test
    fun `반환형이 IEnumerator 인 호출은 StartCoroutine 으로 감싼다`() {
        val call = node(
            """{"target":"System.Collections.IEnumerator Combat.Enemies.BattleWaveController::WaveEndSensor()","receiverWhere":"this"}""",
        )

        assertThat(callExpr(call)).isEqualTo("StartCoroutine(WaveEndSensor())")
    }

    @Test
    fun `키 입력은 InputGetKeyDown 형태로 낸다`() {
        val input = node("""{"kind":"key","control":"RightArrow","phase":"down"}""")

        assertThat(inputExpr(input)).isEqualTo("Input.GetKeyDown(KeyCode.RightArrow)")
    }

    // ---- 파이썬의 falsy(빈 문자열) 처리를 그대로 옮긴 경계값 ----

    @Test
    fun `animation detail 이 빈 문자열이면 Play 로 낸다`() {
        val effect = node("""{"kind":"animation","target":"Player.animator","detail":""}""")

        assertThat(effectStmt(effect)).isEqualTo("Player.animator.Play();")
    }

    @Test
    fun `audio detail 이 없으면 Play 로 낸다`() {
        val effect = node("""{"kind":"audio","target":"Sfx.source"}""")

        assertThat(effectStmt(effect)).isEqualTo("Sfx.source.Play();")
    }

    @Test
    fun `gesture 조건은 파이썬 json dumps 와 같은 간격으로 낸다`() {
        val condition = node("""{"kind":"gesture","input":"key:Return (down)","offset":704}""")

        assertThat(condExpr(condition).code).isEqualTo("""/* gesture: {"input": "key:Return (down)", "offset": 704} */""")
    }
}
