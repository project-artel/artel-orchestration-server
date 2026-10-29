package kr.artel.orchestration.qa.verdict

import com.fasterxml.jackson.databind.JsonNode

/**
 * AI 판정을 코드로 다시 확인한 결과(ARTEL-1252). 판정을 덮어쓰지 않는다 — 사람이 볼 판정을 고르는 등급이다.
 *
 * - [PASS] · [FAIL]: 기대 값이 정해져 있고 `PULSE` 기록에서 그 값을 읽었다. POC 에서 43/44 가 정답과 같았다.
 * - [WEAK_PASS] · [WEAK_FAIL]: 기대가 식("갱신된다")이라 바뀌었는지만 보았다. 확정으로 치지 않는다.
 * - [UNKNOWN]: 대상이 기록에 없거나(`transform` 등) 이 효과에 검사가 없다.
 */
enum class CodeVerdict { PASS, FAIL, WEAK_PASS, WEAK_FAIL, UNKNOWN }

data class CodeCheck(val result: CodeVerdict, val items: List<CodeCheckItem>)

/**
 * 효과 하나를 확인한 내용. 화면이 표로 그린다: 무엇을(`target`) 무엇으로 기대했고(`expected`) 기록에는 무엇이
 * 있었나(`observed`). 확인하지 못했으면 그 까닭이 [note] 다.
 */
data class CodeCheckItem(
    val kind: String,
    val target: String,
    val expected: String?,
    val observed: String?,
    val result: CodeVerdict,
    val note: String? = null,
)

/** TC `case_key` 의 효과 하나: 무엇이(`target`) 어떻게 되어야 하나(`kind` · `value`), 어떤 계기에서(`cond`). */
data class CaseEffect(val kind: String, val target: String, val value: String, val cond: String)

object CaseKeyEffects {
    private const val SEP = "\u001f"

    /**
     * `case_key` 를 효과 목록으로 읽는다. 화면 케이스는 `screen␟씬␟경로`, 기능 케이스는 앞 칸(기능 키) 뒤에
     * `(머리 · kind · target · value · cond)` 다섯 칸이 효과마다 이어진다.
     */
    fun parse(caseKey: String): List<CaseEffect> {
        val parts = caseKey.split(SEP)
        if (parts.firstOrNull() == "screen") {
            return listOf(CaseEffect("screen", parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" }, ""))
        }
        return parts.drop(1).chunked(5).map { chunk ->
            CaseEffect(
                kind = chunk.getOrElse(1) { "" },
                target = chunk.getOrElse(2) { "" },
                value = chunk.getOrElse(3) { "" },
                cond = chunk.getOrElse(4) { "" },
            )
        }
    }
}

/** 판정 시점까지 `PULSE` 를 접은 게임 상태. `PULSE` 는 바뀐 것만 보내므로 앞에서부터 차례로 접는다. */
class PulseState private constructor(
    var scene: String?,
    private val objects: MutableMap<String, SeenObject>,
    private val members: MutableMap<MemberKey, JsonNode>,
    private val statics: MutableMap<Pair<String?, String?>, JsonNode>,
) {
    constructor() : this(null, linkedMapOf(), linkedMapOf(), linkedMapOf())

    data class SeenObject(val active: Boolean, val path: String?)

    data class MemberKey(val selector: String, val on: String?, val member: String?)

    fun copy() = PulseState(scene, LinkedHashMap(objects), LinkedHashMap(members), LinkedHashMap(statics))

    fun fold(pulse: JsonNode) {
        pulse.path("scene").asText("").takeIf { it.isNotBlank() }?.let { scene = it }
        pulse.path("statics").forEach { item ->
            statics[item.path("declaring").textOrNull() to item.path("member").textOrNull()] = item.path("value")
        }
        for ((key, active) in listOf("active" to true, "deactive" to false)) {
            pulse.path(key).forEach { item ->
                val selector = item.path("selector").textOrNull() ?: item.path("path").textOrNull() ?: return@forEach
                objects[selector] = SeenObject(active, item.path("path").textOrNull())
                item.path("by").forEach { group ->
                    val on = group.path("on").textOrNull()
                    group.path("m").forEach { member ->
                        val value = when {
                            member.has("value") -> member.path("value")
                            member.has("count") -> member
                            else -> null
                        }
                        if (value != null) members[MemberKey(selector, on, member.path("member").textOrNull())] = value
                    }
                }
            }
        }
        pulse.path("gone").forEach { objects.remove(it.asText()) }
    }

    internal fun objectsAt(path: String) = objects.values.filter { plain(it.path) == path }

    internal fun newObjectsSince(before: PulseState) = objects.filterKeys { it !in before.objects }.values

    /**
     * `Type.member[.rest]` 의 값들과, 그 값 안에서 더 따라갈 칸. 판독은 멤버 경로를 한 칸에 적기도 하고
     * (`combineZone.activeSelf`), 기반 타입(`Enemy`)을 파생 타입(`SwordEnemy`)으로 찍으므로 그 타입으로 못 찾으면
     * 이름이 그 타입으로 끝나는 타입 전부를 본다.
     */
    internal fun membersOf(target: String): List<Pair<JsonNode, List<String>>> {
        val type = target.substringBefore('.')
        val segments = target.substringAfter('.', "").split('.')
        fun scan(typeMatches: (String) -> Boolean): List<Pair<JsonNode, List<String>>> {
            val hits = mutableListOf<Pair<JsonNode, List<String>>>()
            for ((key, value) in members) {
                val on = key.on ?: continue
                if (!typeMatches(on.substringAfterLast('.'))) continue
                val memberParts = key.member.orEmpty().split('.')
                when {
                    memberParts == segments.take(memberParts.size) -> hits += value to segments.drop(memberParts.size)
                    memberParts == segments + "activeSelf" -> hits += value to emptyList()
                }
            }
            for ((key, value) in statics) {
                val declaring = key.first ?: continue
                if (typeMatches(declaring.substringAfterLast('.')) && key.second == segments.first()) {
                    hits += value to segments.drop(1)
                }
            }
            return hits
        }
        return scan { it == type }.ifEmpty { scan { it.endsWith(type) } }
    }
}

/**
 * TC 의 효과를 판정 시점의 상태로 확인한다(ARTEL-1252). 사전조건은 평가하지 않는다 — 조건 원자의 상당수가
 * 메서드 호출이라 기록에 값이 없다.
 */
object VerdictCodeChecker {

    fun check(effects: List<CaseEffect>, now: PulseState, before: PulseState): CodeCheck {
        if (effects.isEmpty()) return CodeCheck(CodeVerdict.UNKNOWN, emptyList())
        val items = effects.map { checkChanged(it, now, before) }
        return CodeCheck(combine(items.map { it.result }), items)
    }

    /** 조건 갈래가 여럿이면 하나만 일어나도 된다. 확정 결과가 약한 결과보다 먼저다. */
    private fun combine(results: List<CodeVerdict>): CodeVerdict = when {
        CodeVerdict.PASS in results -> CodeVerdict.PASS
        CodeVerdict.FAIL in results -> CodeVerdict.FAIL
        CodeVerdict.WEAK_PASS in results -> CodeVerdict.WEAK_PASS
        CodeVerdict.WEAK_FAIL in results -> CodeVerdict.WEAK_FAIL
        else -> CodeVerdict.UNKNOWN
    }

    /**
     * 입력 계기(`gesture|`)가 있는 효과는 "그렇게 됐다" 는 뜻이다. 앞 판정 시점에 이미 그 상태였으면 전환을 본 것이
     * 아니라서 확정하지 않는다 — 끝 상태만 보면 POC 에서 8건이 모두 틀렸다.
     */
    private fun checkChanged(effect: CaseEffect, now: PulseState, before: PulseState): CodeCheckItem {
        val item = check(effect, now, before)
        val triggered = effect.kind != "screen" && "gesture|" in effect.cond
        if (item.result == CodeVerdict.PASS && triggered && check(effect, before, before).result == CodeVerdict.PASS) {
            return item.copy(result = CodeVerdict.UNKNOWN, note = "행동 전에도 이미 그 상태였다")
        }
        return item
    }

    private fun check(effect: CaseEffect, state: PulseState, before: PulseState): CodeCheckItem = when (effect.kind) {
        "scene" -> item(effect, effect.target, state.scene, if (state.scene == effect.target) CodeVerdict.PASS else CodeVerdict.FAIL)
        "screen" -> screen(effect, state)
        "active-state" -> activeState(effect, state)
        "ui-value" -> uiValue(effect, state, before)
        "instantiate" -> instantiate(effect, state, before)
        else -> unknown(effect, null, "이 효과는 코드로 확인하지 않는다")
    }

    private fun item(effect: CaseEffect, expected: String?, observed: String?, result: CodeVerdict, target: String = effect.target) =
        CodeCheckItem(effect.kind, target, expected, observed, result)

    private fun unknown(effect: CaseEffect, expected: String?, note: String, target: String = effect.target) =
        CodeCheckItem(effect.kind, target, expected, null, CodeVerdict.UNKNOWN, note)

    private fun screen(effect: CaseEffect, state: PulseState): CodeCheckItem {
        val want = plain(effect.value)
        if (state.scene != effect.target) {
            return CodeCheckItem("scene", effect.target, effect.target, state.scene, CodeVerdict.FAIL)
        }
        val seen = state.objectsAt(want)
        if (seen.isEmpty()) return unknown(effect, "true", "기록에 없다", target = want)
        val shown = seen.any { it.active }
        return item(effect, "true", shown.toString(), if (shown) CodeVerdict.PASS else CodeVerdict.FAIL, target = want)
    }

    private fun activeState(effect: CaseEffect, state: PulseState): CodeCheckItem {
        if (effect.target.startsWith("Component.") || effect.target.startsWith("this.")) {
            return unknown(effect, effect.value, "대상 오브젝트를 특정하지 못했다")
        }
        val want = literal(effect.value) as? Boolean ?: return unknown(effect, effect.value, "기대 값이 식이다")
        val actives = state.membersOf(effect.target).mapNotNull { (value, rest) -> activeOf(value, rest) }.distinct()
        if (actives.isEmpty()) return unknown(effect, want.toString(), "기록에 없다")
        return item(effect, want.toString(), actives.joinToString(", "), if (want in actives) CodeVerdict.PASS else CodeVerdict.FAIL)
    }

    private fun uiValue(effect: CaseEffect, state: PulseState, before: PulseState): CodeCheckItem {
        val shown = shown(state, effect.target)
        if (shown.isEmpty()) return unknown(effect, effect.value, "기록에 없다")
        val want = literal(effect.value.substringBefore(','))
        if (want is String) {
            return item(effect, want, shown.first(), if (want in shown) CodeVerdict.PASS else CodeVerdict.FAIL)
        }
        // 기대가 식이면 갱신됐는지만 본다.
        val previous = shown(before, effect.target)
        val changed = shown != previous
        return CodeCheckItem(
            kind = effect.kind,
            target = effect.target,
            expected = "갱신",
            observed = "${previous.firstOrNull() ?: "없음"} → ${shown.first()}",
            result = if (changed) CodeVerdict.WEAK_PASS else CodeVerdict.WEAK_FAIL,
        )
    }

    private fun shown(state: PulseState, target: String): List<String> =
        state.membersOf(target).mapNotNull { (value, rest) ->
            when {
                value.isObject && rest in listOf(listOf("text"), emptyList()) && value.has("label") -> value.path("label").asText()
                value.isObject && rest == listOf("sprite") && value.has("sprite") -> value.path("sprite").asText()
                else -> null
            }
        }

    private fun instantiate(effect: CaseEffect, state: PulseState, before: PulseState): CodeCheckItem {
        val names = state.membersOf(effect.target)
            .mapNotNull { (value, _) -> value.takeIf { it.isObject } }
            .mapNotNull { it.path("name").textOrNull() ?: plain(it.path("path").textOrNull()).takeIf(String::isNotBlank) }
            .map { it.substringAfterLast('/') }
        if (names.isEmpty()) return unknown(effect, "생성", "만드는 오브젝트 이름을 모른다")
        val created = state.newObjectsSince(before).count { seen ->
            val last = seen.path.orEmpty().substringAfterLast('/')
            names.any { last.startsWith(it) }
        }
        return item(effect, "${names.first()} 생성", "${created}개 생성", if (created > 0) CodeVerdict.PASS else CodeVerdict.FAIL)
    }

    private fun activeOf(value: JsonNode, rest: List<String>): Boolean? = when {
        rest in listOf(emptyList(), listOf("gameObject")) && value.isObject && value.has("active") -> value.path("active").asBoolean()
        rest.isEmpty() && value.isBoolean -> value.asBoolean()
        else -> null
    }

    /** 기대 값 칸의 리터럴. 식이면 null 이다. */
    private fun literal(raw: String): Any? {
        val value = raw.trim()
        return when {
            value == "true" || value == "false" -> value == "true"
            value.length >= 2 && value.startsWith('"') && value.endsWith('"') -> value.substring(1, value.length - 1)
            NUMBER.matches(value) -> value.toDouble()
            else -> null
        }
    }

    private val NUMBER = Regex("-?\\d+(\\.\\d+)?")
}

/** 판독 경로의 `[번호]` 를 지운다. 같은 이름의 형제를 가르는 표시라 TC 의 경로에는 없다. */
internal fun plain(path: String?): String = path.orEmpty().replace(Regex("\\[\\d+]"), "").trim('/')

private fun JsonNode.textOrNull(): String? = if (isMissingNode || isNull) null else asText()
