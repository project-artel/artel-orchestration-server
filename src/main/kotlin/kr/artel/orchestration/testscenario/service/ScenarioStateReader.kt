package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.testcase.entity.TestCaseEntity

/**
 * 케이스와 기능이 **어떤 상태를 말하는가**를 읽는 한 자리(ARTEL-466).
 *
 * 사전조건은 사람이 읽는 한 줄이다 — `Map_scene 화면인 상태 / (MapMove.StagePosition >= 1 그리고
 * MapMove.position == 0)`. 여기서 비교를 뽑는 규칙이 [ScenarioPathService](경로 계산)와 에이전트에
 * 보내는 정규화 값 양쪽에 필요한데, 두 벌 두면 갈라진다. 갈라지면 **에이전트가 본 상태와 코드가
 * 계산한 상태가 달라지고**, 그 어긋남은 조용하다.
 *
 * 기능 자신의 사전조건(`capability.given_text`)도 같은 문법이다 — 씬 접두 없이 식만 오고 각 항이
 * 백틱에 싸여 있다. 그래서 같은 읽개를 쓴다.
 */
object ScenarioStateReader {

    /** 사전조건 앞부분이 씬 이름이다. 그 문구가 없는 행이 있어 `scene` 컬럼으로 받친다. */
    fun sceneOf(case: TestCaseEntity): String? {
        val pre = case.precondition ?: return case.scene
        val marker = " 화면인 상태"
        return if (pre.contains(marker)) pre.substringBefore(marker).trim().ifBlank { null } else case.scene
    }

    /**
     * 사전조건의 `<씬> 화면인 상태 / <식>` 뒷부분에서 비교를 뽑는다.
     *
     * **부등식까지 읽는다.** `==` 만 보던 판을 실측했더니 실제 사전조건의 비교 중 58%가
     * `>`·`!=`·`>=`·`<=`·`<` 였고, 그것을 전부 "충돌 없음"으로 통과시키고 있었다.
     */
    fun guardsOf(precondition: String?): List<Guard> =
        comparisonsIn(precondition?.substringAfter(" / ", ""))

    /** 식에서 비교를 뽑는다. 백틱은 벗긴다. */
    fun comparisonsIn(expr: String?): List<Guard> {
        if (expr.isNullOrBlank()) return emptyList()
        return COMPARISON.findAll(expr).map {
            val written = it.groupValues[1].trim().trim('`')
            Guard(
                variable = normalize(written),
                operator = it.groupValues[2],
                value = it.groupValues[3].trim().trim('`'),
                path = written,
            )
        }.toList()
    }

    /**
     * 사전조건이 **확정하는** 값만 뽑는다.
     *
     * `==` 만 값을 특정한다. `StagePosition >= 1` 은 그 케이스가 성립하는 조건이지 값이 아니라,
     * 여기서 1이라고 읽으면 다음 케이스와의 비교가 거짓말이 된다.
     */
    fun knownValuesOf(precondition: String?): Map<String, String> =
        guardsOf(precondition).filter { it.operator == "==" }.associate { it.variable to it.value }

    /** 이 케이스를 실행한 뒤 확정되는 값. `metadata.source.state_after` 가 `Var=value` 형식이다. */
    fun stateAfter(case: TestCaseEntity, objectMapper: ObjectMapper): Map<String, String> = runCatching {
        val after = objectMapper.readTree(case.metadata.asString())
            .path("source").path("state_after").asText(null) ?: return emptyMap()
        val (name, value) = after.split("=", limit = 2).let { it[0] to it.getOrNull(1) }
        if (value.isNullOrBlank()) emptyMap() else mapOf(normalize(name) to value.trim())
    }.getOrElse { emptyMap() }

    /**
     * 케이스가 가리키는 코드를 **지도가 쓰는 꼬리 패턴**으로 바꾼다.
     *
     * 케이스는 `Assembly-CSharp|WordVenture.Map.MapMove|CharacterMove|System.Void()@79` 처럼 적고
     * 여러 개를 ` / ` 로 잇는다. 지도는 같은 것을 `Assembly-CSharp|Map.MapMove|CharacterMove|...`
     * 로 부른다 — 네임스페이스 접두가 다르고 오프셋이 없다. 그래서 **타입의 마지막 두 마디부터**
     * 뒤를 맞춘다. `object:Canvas[2]/ExitButton[3]@?` 같은 UI 근거는 이 형식이 아니라 걸러진다.
     */
    fun evidenceTails(case: TestCaseEntity, objectMapper: ObjectMapper): List<String> = runCatching {
        val raw = objectMapper.readTree(case.metadata.asString())
            .path("source").path("evidence").asText(null) ?: return emptyList()
        raw.split(" / ").mapNotNull { entry ->
            val parts = entry.trim().substringBeforeLast('@').split("|")
            if (parts.size != 4) return@mapNotNull null
            val type = parts[1].substringBefore('/').split(".").takeLast(2).joinToString(".")
            "%$type|${parts[2]}|${parts[3]}"
        }.distinct()
    }.getOrElse { emptyList() }

    /**
     * 이 사전조건이 [state]와 어긋나는가. 어긋나는 **첫 가드**를 돌려주고, 없으면 null 이다.
     *
     * 값을 모르는 변수는 어긋난다고 보지 않는다 — 경로 계산 전체를 관통하는 규칙이다. 대부분의
     * 기능이 `InteractionLock.IsLocked == 0` 을 요구하는데 그 값을 아는 경우는 드물고, 모르는 것을
     * 위반으로 세면 거의 모든 길이 막힌다.
     */
    fun violated(givenText: String?, state: Map<String, String>): Guard? =
        comparisonsIn(givenText).firstOrNull { guard ->
            val have = state[guard.variable]
            have != null && !guard.holds(have)
        }

    /**
     * 마지막 마디로 맞춘다.
     *
     * 명세와 사전조건이 같은 값을 다른 경로로 부른다 — `StagePosition` · `MapMove.StagePosition` ·
     * `StageDataSingleton.stagePosition`. 마디가 겹치는 서로 다른 변수(`collision.tag` 와
     * `combineZone.tag`)를 뭉갤 수 있다는 것은 **알려진 한계**이고, 근본 해결은 명세가 별칭을
     * declare 하는 것이다.
     */
    fun normalize(name: String): String = name.trim().trim('`').substringAfterLast('.')

    private val COMPARISON = Regex("""([A-Za-z_][\w.]*)\s*(==|!=|>=|<=|>|<)\s*([^\s그리고또는()]+)""")
}

/**
 * 비교 하나. `holds` 는 비교할 수 없으면 **위반이라고 말하지 않는다**.
 *
 * @property variable 맞춰 보기 위해 마지막 마디만 남긴 이름(`Player.hp` → `hp`).
 * @property path 사전조건에 **적힌 그대로**의 이름. [variable] 만으로는 마디가 겹치는 서로 다른
 *   변수가 한 이름이 된다 — `magicTypeCards.Count` 와 `spellCards.Count` 가 둘 다 `Count` 다.
 *   같은 값을 다른 경로로 부르는 것(`Player.hp` · `Player.PlayerInt().hp`)과 그것을 가르려면
 *   적힌 이름이 남아 있어야 한다(ARTEL-497).
 */
data class Guard(
    val variable: String,
    val operator: String,
    val value: String,
    val path: String = variable,
) {
    fun holds(have: String): Boolean {
        if (operator == "==") return have == value
        if (operator == "!=") return have != value
        val a = have.toDoubleOrNull() ?: return true
        val b = value.toDoubleOrNull() ?: return true
        return when (operator) {
            ">" -> a > b
            ">=" -> a >= b
            "<" -> a < b
            "<=" -> a <= b
            else -> true
        }
    }
}
