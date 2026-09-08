package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.GroupKind

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
     *
     * **씬 접두가 없는 행도 읽는다**(ARTEL-519). 구분자가 없으면 빈 문자열을 읽고 있었고, 그러면
     * 그 케이스의 사전조건이 코드에 통째로 안 보인다. word-venture 의 `1277` 이 그렇다 —
     * `StageDataSingleton.StagePosition == 4` 뿐이고 씬은 `scene` 컬럼에만 있다. 그 케이스가
     * 말하는 것은 "GameClearScene 은 그 값이면 입력 없이 EndingScene 으로 빠진다"인데, 가드가
     * 안 읽히니 충돌 판정도 경로 계산도 그것을 모른다.
     *
     * 앞부분을 함께 읽어도 안전하다. 씬 접두(`<씬> 화면인 상태`)에는 비교 연산자가 없어 [COMPARISON]
     * 이 아무것도 집지 않는다.
     */
    fun guardsOf(precondition: String?): List<Guard> =
        comparisonsIn(precondition?.let { it.substringAfter(" / ", it) })

    /**
     * **구조에서 곧바로 읽는다**(ARTEL-627).
     *
     * [guardsOf] 와 같은 답을 내되 문장을 거치지 않는다. 문장은 사람에게 보여주려고 다듬은 것이라
     * 되읽을 때 잃는 것이 있다 — 실측에서 셋이 사라졌다:
     *
     * ```
     * CombineButton.combineZone.activeSelf  →  activeSelf      주인을 잃는다
     * (x == 5 또는 x == 4)                  →  (아무것도 없음)  갈래를 못 말한다
     * (MapMove.StagePosition - 1)           →  (식으로만 남음)  되읽을 수 없다
     * ```
     *
     * **갈래 규칙은 같다.** `either` 아래에서는 모든 갈래에 함께 있는 비교만 남긴다 — 그것이 이
     * 사전조건이 실제로 보장하는 것이고, 한 갈래에만 있는 것은 성립할 수도 있는 것이지 요구가
     * 아니다. 문장 쪽 [comparisonsIn] 이 같은 이유로 같은 일을 한다.
     */
    fun guardsIn(node: ConditionNode?): List<Guard> = when (node) {
        null, is ConditionNode.Always, is ConditionNode.Gesture, is ConditionNode.Unknown -> emptyList()
        is ConditionNode.Test -> listOf(
            Guard(
                variable = normalize(node.left),
                operator = node.operator,
                value = node.right.trim().trim('`'),
                // **전체 이름을 남긴다.** 이것이 문장에서 잃던 바로 그것이다.
                path = node.left.trim().trim('`'),
            )
        )
        is ConditionNode.Group -> when (node.kind) {
            GroupKind.EVERY -> node.parts.flatMap { guardsIn(it) }.distinct()
            GroupKind.EITHER -> node.parts
                .map { guardsIn(it).toSet() }
                .reduceOrNull { common, next -> common intersect next }
                .orEmpty()
                .toList()
        }
    }

    /**
     * 구조가 **확정하는** 값(`==` 만). [knownValuesOf] 의 트리 판이다.
     *
     * `>=` 는 성립 조건이지 값이 아니다 — 여기서 1이라고 읽으면 다음 케이스와의 비교가 거짓말이 된다.
     */
    fun knownValuesIn(node: ConditionNode?): Map<String, String> =
        guardsIn(node)
            .filter { it.operator == "==" && !it.symbolic }
            .associate { it.variable to it.value }

    /**
     * 식에서 비교를 뽑는다. 백틱은 벗긴다.
     *
     * **`또는` 은 `그리고` 가 아니다.** 정규식으로 식 전체를 훑어 비교를 긁어모으면 갈래가 둘인
     * 사전조건이 자기 자신과 모순되는 목록이 된다 — `(damage > 0 그리고 hp > 0) 또는
     * (damage <= 0 그리고 hp > 0)` 에서 `damage > 0` 과 `damage <= 0` 이 나란히 나온다. 그러면
     * 이 케이스는 `damage > 0` 을 요구하는 **모든** 케이스와 함께 담을 수 없다고 판정된다
     * (런 152: 19쌍 중 19쌍이 이렇게 만들어진 거짓 충돌이었다).
     *
     * 그래서 갈래로 나뉜 자리에서는 **모든 갈래에 함께 있는 비교만** 남긴다. 그것이 이 사전조건이
     * 실제로 보장하는 것이고, 한 갈래에만 있는 것은 성립할 수도 있는 것이지 요구가 아니다.
     * 좁게 답하는 쪽으로 틀린다 — 이 저장소 전체의 규칙대로, 모르는 것은 충돌이라 부르지 않는다.
     */
    fun comparisonsIn(expr: String?): List<Guard> {
        val body = expr?.let(::unwrap).orEmpty()
        if (body.isBlank()) return emptyList()

        val alternatives = splitTopLevel(body, OR)
        if (alternatives.size > 1) {
            return alternatives
                .map { comparisonsIn(it).toSet() }
                .reduce { common, next -> common intersect next }
                .toList()
        }
        val conjuncts = splitTopLevel(body, AND)
        if (conjuncts.size > 1) return conjuncts.flatMap { comparisonsIn(it) }.distinct()

        return COMPARISON.findAll(body).map {
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
        guardsOf(precondition)
            .filter { it.operator == "==" && !it.symbolic }
            .associate { it.variable to it.value }

    /** 이 케이스를 실행한 뒤 확정되는 값. `metadata.source.state_after` 가 `Var=value` 형식이다. */
    fun stateAfter(case: TestCaseEntity, objectMapper: ObjectMapper): Map<String, String> = runCatching {
        val after = objectMapper.readTree(case.metadata.asString())
            .path("source").path("state_after").asText(null) ?: return emptyMap()
        val (name, value) = after.split("=", limit = 2).let { it[0] to it.getOrNull(1) }
        if (value.isNullOrBlank()) emptyMap() else mapOf(normalize(name) to value.trim())
    }.getOrElse { emptyMap() }

    /**
     * 이 케이스를 실행하면 **어느 화면에 서 있게 되나**(ARTEL-654).
     *
     * 화면을 넘기는 케이스는 자기가 어디에 도착하는지 적어 둔다(`arrives_at`). 그런데 경로 계산이
     * 그것을 안 읽고 [sceneOf] 만 보아, 출발 화면을 **케이스를 실행하기 전**으로 잡았다.
     *
     * 대가가 짝 행렬에 그대로 나왔다. 엔딩·스토리 화면은 지도의 씬 간선이 전부 "저절로 넘어간다"
     * 라서 나가는 조작이 없는 것으로 읽히고, 그 화면에서 출발하는 칸이 통째로 막혔다 — 642칸이다.
     * 그런데 그 화면을 나가는 케이스가 여덟 건 있고, 그 케이스를 실행한 뒤에는 **이미 지도에 서
     * 있다.** 나갈 길을 찾을 일이 아니라 이미 나와 있는 것이다.
     *
     * 없으면 null 이다 — 대부분의 케이스는 화면을 안 넘긴다.
     */
    fun arrivesAt(case: TestCaseEntity, objectMapper: ObjectMapper): String? = runCatching {
        objectMapper.readTree(case.metadata.asString())
            .path("arrives_at").asText(null)?.trim()?.ifBlank { null }
    }.getOrNull()

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
     * 이 조건이 [state] 와 어긋나는지 판단한다. 어긋나는 첫 `Guard` 를 돌려주고, 없으면 `null`.
     *
     * 값을 모르는 변수는 어긋난다고 보지 않는다 — 경로 계산 전체를 관통하는 규칙이다. 대부분의
     * `capability` 가 `InteractionLock.IsLocked == 0` 을 요구하는데 그 값을 아는 경우는 드물고,
     * 모르는 것을 위반으로 세면 거의 모든 길이 막힌다.
     *
     * `capability.given_text` 를 받던 문자열 판이 여기 있었는데 지웠다(ARTEL-447). 그 칸은 419 행
     * 전부 `null` 이라 비교를 하나도 못 찾았고, 그래서 **늘 "위반 없음" 을 돌려줬다** — 길찾기가
     * *"이 조작은 지금 상태에서 못 한다"* 를 한 번도 못 짚었다. 채우기만 하고 그 판을 남겨 두면
     * 이번에는 사람이 읽으라고 다듬은 문장을 되읽게 되므로, 부르는 곳이 사라진 김에 함께 지운다.
     */
    fun violated(condition: ConditionNode?, state: Map<String, String>): Guard? =
        violated(guardsIn(condition), state)

    private fun violated(guards: List<Guard>, state: Map<String, String>): Guard? =
        guards.firstOrNull { guard ->
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

    /**
     * 괄호 하나가 식 전체를 감싸고 있으면 벗긴다.
     *
     * 벗기지 않으면 갈래를 못 본다 — `((a) 또는 (b))` 의 `또는` 은 괄호 안(깊이 1)에 있어
     * 최상위 분리에 걸리지 않는다.
     */
    private fun unwrap(expr: String): String {
        var s = expr.trim()
        while (s.length > 1 && s.first() == '(' && s.last() == ')' && wrapsWhole(s)) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s
    }

    /** 첫 `(` 의 짝이 마지막 글자인가. 아니면 `(a) 또는 (b)` 를 잘못 벗긴다. */
    private fun wrapsWhole(s: String): Boolean {
        var depth = 0
        s.forEachIndexed { index, c ->
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth == 0 && index != s.lastIndex) return false
            }
        }
        return depth == 0
    }

    /** [separator] 로 나눈다. **괄호 안은 건너뛴다** — 안쪽 갈래는 그 자리에서 따로 읽힌다. */
    private fun splitTopLevel(expr: String, separator: Regex): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < expr.length) {
            if (expr[i] == '(') depth++
            if (expr[i] == ')') depth--
            val hit = if (depth == 0) separator.matchAt(expr, i) else null
            if (hit != null) {
                parts += expr.substring(start, i)
                i += hit.value.length
                start = i
            } else {
                i++
            }
        }
        parts += expr.substring(start)
        return parts.map(String::trim).filter { it.isNotBlank() }
    }

    /**
     * 이음말은 **두 방언으로 온다.** 케이스 사전조건은 `그리고`·`또는` 를 쓰고, 기능의 사전조건
     * (`capability.given_text`)은 `and` 를 쓴다(실측: 90건이 `and`, `그리고` 는 0건).
     *
     * 라틴 낱말은 **경계를 본다.** `Coordinate` 안에도 `or` 가 들어 있어, 경계 없이 자르면 변수
     * 이름이 두 동으로 난다.
     */
    private val AND = Regex("""그리고|\band\b""")
    private val OR = Regex("""또는|\bor\b""")

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
    /**
     * 오른쪽이 **리터럴이 아니라 다른 변수**인가.
     *
     * `collision.tag == SpellObj.target.gameObject.tag` 는 두 값이 같아야 한다는 말이지 `tag` 가
     * 무엇이라는 말이 아니다. 그런데 문자열로 비교하면 `tag == "Me"` 와 어긋난다고 나오고, 실제로
     * 그것 하나 때문에 케이스 두 건이 "함께 담을 수 없다"로 판정됐다(런 152). 값을 모르는 것이지
     * 어긋나는 것이 아니다.
     */
    val symbolic: Boolean
        get() = value.toDoubleOrNull() == null &&
            !value.startsWith("\"") && !value.startsWith("'") &&
            value.contains('.')

    fun holds(have: String): Boolean {
        // 비교할 수 없는 것은 위반이라 말하지 않는다 — 이 클래스 전체를 관통하는 규칙이다.
        if (symbolic) return true
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
