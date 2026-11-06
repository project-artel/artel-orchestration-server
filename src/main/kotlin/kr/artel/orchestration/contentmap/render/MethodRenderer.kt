package kr.artel.orchestration.contentmap.render

import com.fasterxml.jackson.databind.JsonNode
import java.util.Locale

/**
 * evidence record 묶음(한 메서드에 대해 여러 개일 수 있다) -> 메서드 본문 하나.
 * IL offset 순 정렬, 변형(variant) dedupe, `<M>d__N::MoveNext` -> `M` 되접기(옛
 * `StateMachineFolder` — 유일한 소비자가 이 파일이라 흡수했다)까지 여기서 처리한다.
 */

private val STATE_MACHINE_REGEX = Regex("""^(?<owner>[^/]+)/<(?<outer>[^>]+)>d__\d+$""")

/** `<M>d__N::MoveNext` 를 원래 메서드 `M` 으로 되접는다. 상태 머신이 아니면 그대로. */
fun logicalSig(record: JsonNode, sigByName: Map<Pair<String, String>, String>): String? {
    val sig = record.path("source").textOrNull() ?: record.path("entry").textOrNull()
    val p = parseSignature(sig)
    val match = STATE_MACHINE_REGEX.matchEntire(p.declaringType) ?: return sig
    val key = match.groups["owner"]!!.value to match.groups["outer"]!!.value
    return sigByName[key] ?: "System.Collections.IEnumerator ${key.first}::${key.second}()"
}

/** 상태 머신이 아닌 레코드들의 (declaringType, name) -> 원본 시그니처. [logicalSig] 되접기용 조회 테이블. */
fun buildSigByName(records: List<JsonNode>): Map<Pair<String, String>, String> {
    val map = LinkedHashMap<Pair<String, String>, String>()
    for (r in records) {
        val sig = r.path("source").textOrNull() ?: r.path("entry").textOrNull()
        val p = parseSignature(sig)
        if (!STATE_MACHINE_REGEX.matches(p.declaringType) && sig != null) {
            map.putIfAbsent(p.declaringType to p.name, sig)
        }
    }
    return map
}

/** 되접힌 논리 시그니처 -> 그 시그니처로 모인 레코드 목록. 문서 순서를 보존한다(결정론). */
fun groupByLogicalSig(records: List<JsonNode>): Map<String?, List<JsonNode>> {
    val sigByName = buildSigByName(records)
    val map = LinkedHashMap<String?, MutableList<JsonNode>>()
    for (r in records) {
        map.getOrPut(logicalSig(r, sigByName)) { mutableListOf() }.add(r)
    }
    return map
}

/** record 하나의 관찰된 문장들 — inputs/calls/effects/handles 를 IL offset 순으로 합친다. */
fun buildBody(record: JsonNode): List<String> {
    data class Item(val offset: Int, val order: Int, val text: String)

    val items = mutableListOf<Item>()
    record.path("inputs").arrayOrEmpty().forEach { i ->
        items += Item(i.offsetOrZero(), 0, "if (${inputExpr(i)}) { ... }")
    }
    record.path("calls").arrayOrEmpty().forEach { c ->
        val target = c.path("target").textOrNull()
        if (!isGeneratedSignature(target)) {
            items += Item(c.offsetOrZero(), 1, "${callExpr(c)};")
        }
    }
    record.path("effects").arrayOrEmpty().forEach { e ->
        items += Item(e.offsetOrZero(), 2, effectStmt(e))
    }
    record.path("handles").arrayOrEmpty().forEach { h ->
        items += Item(h.offsetOrZero(), 3, handleStmt(h))
    }
    items.sortWith(compareBy({ it.offset }, { it.order }))
    val lines = items.map { "%-58s // IL_%04X".format(Locale.ROOT, it.text, it.offset) }.toMutableList()

    val loopsBackTo = record.path("loopsBackTo")
    if (!loopsBackTo.isMissingNode && !loopsBackTo.isNull) {
        lines += "goto IL_%04X;                                              // loop".format(Locale.ROOT, loopsBackTo.asInt())
    }
    val handedOverTo = record.path("handedOverTo").textOrNull()
    if (!handedOverTo.isNullOrEmpty()) {
        val handedOverAt = record.path("handedOverAt").let { if (it.isMissingNode || it.isNull) 0 else it.asInt(0) }
        val declType = parseSignature("$handedOverTo()").declaringType
        val label = shortType(declType).ifEmpty { handedOverTo }
        lines += "// control handed to $label @ IL_%04X".format(Locale.ROOT, handedOverAt)
    }
    return lines
}

/** record 안의 offset 이 붙은 항목들 중 가장 작은 값 — 변형 정렬 기준. 없으면 맨 뒤로 민다. */
fun firstOffset(record: JsonNode): Int {
    var min = Int.MAX_VALUE
    for (key in listOf("inputs", "calls", "effects", "handles")) {
        record.path(key).arrayOrEmpty().forEach { item ->
            val offset = item.path("offset")
            if (!offset.isMissingNode && !offset.isNull) min = minOf(min, offset.asInt())
        }
    }
    return min
}

/** `record.callPath` -> `"A.B -> C.D"` 짧은 표기. 같은 변형에 묶인 레코드들의 콜패스를 보여줄 때 쓴다. */
private fun renderedCallPath(record: JsonNode): String =
    record.path("callPath").arrayOrEmpty().joinToString(" -> ") { declShort(it.asText()) }

private fun pathsOf(records: List<PreparedRecord>): List<String> =
    records.map { renderedCallPath(it.record) }.toSortedSet().toList()

/**
 * 레코드 하나를 렌더링에 필요한 세 가지 — 원본, 조건 결과, 본문 — 로 한 번만 미리 계산해
 * 묶어 둔다. `condExpr`/`buildBody` 는 순수 함수라 다시 불러도 값은 같지만, 변형 키 계산·빈
 * 변형 걸러내기·주석 모으기가 각자 다시 불렀다가는(레코드 수만큼 최대 3배) "code와 comments는
 * 같은 판단에서 나온 한 쌍"이라는 fix 3 의 불변식이 여러 곳에 흩어지고, 그중 한 곳만 고치면
 * comments 가 다시 조용히 사라지는 사고가 재현된다. 그래서 여기서 딱 한 번 계산해 쓴다.
 */
private data class PreparedRecord(val record: JsonNode, val condition: CondResult, val bodyLines: List<String>)

private fun prepare(records: List<JsonNode>): List<PreparedRecord> =
    records.map { PreparedRecord(it, condExpr(it.path("condition")), buildBody(it)) }

/**
 * (조건 코드, 본문 라인) 쌍 그 자체가 변형의 키다 — 파이썬 `variant_key`/`OrderedDict` 와 동일하게
 * "같게 렌더되는 레코드는 하나로 합친다"는 뜻이지 해시가 아니다. fix 3 적용 후 subjectLost로
 * 조건이 통째로 접힌 레코드는 code=null 이 되어, 원래도 조건 없는(`always`) 동일 본문
 * 레코드와 자연스럽게 하나로 합쳐진다(둘 다 결국 같은 코드를 낸다) — 대신 comments 는
 * 대표 레코드 하나가 아니라 그 버킷에 모인 **모든** 레코드에서 합집합으로 모은다. 그렇게
 * 안 하면 comments 가 조용히 사라진다.
 */
private data class VariantKey(val code: String?, val bodyLines: List<String>)

private fun isNonEmptyVariant(key: VariantKey, records: List<PreparedRecord>): Boolean {
    if (key.code != null || key.bodyLines.isNotEmpty()) return true
    return records.any { it.condition.comments.isNotEmpty() }
}

/** 메서드 하나(논리 시그니처 + 그 레코드들) -> 렌더된 줄 목록. */
fun renderMethod(sig: String?, records: List<JsonNode>, indent: String, wiringIndex: WiringIndex): List<String> {
    val out = mutableListOf<String>()
    val p = parseSignature(sig)

    val trig = records.mapNotNull { it.path("triggerKind").textOrNull() }.toSortedSet()
    val confidence = records.mapNotNull { it.path("confidence").textOrNull() }.toSortedSet()
    val gaps = records.flatMap { it.path("gaps").arrayOrEmpty().map { g -> g.asText() } }.toSortedSet()

    val entries = mutableListOf<String>()
    fun addEntry(entrySig: String?) {
        if (entrySig == null) return
        val short = declShort(entrySig)
        if (short !in entries) entries += short
    }
    for (r in records) {
        addEntry(r.path("entry").textOrNull())
        r.path("alsoReachedBy").arrayOrEmpty().forEach { addEntry(it.path("entry").textOrNull()) }
    }

    val callers = records.flatMap { it.path("calledBy").arrayOrEmpty().map { c -> c.asText() } }
        .filter { it.count { ch -> ch == '|' } >= 2 }
        .map { it.split("|").let { parts -> "${parts[1]}.${parts[2]}" } }
        .toSortedSet()

    for (t in trig) {
        val label = when (t) {
            "lifecycle" -> "UnityLifecycle"
            "unity-event" -> unityEventAnnotation(p, wiringIndex)
            else -> t
        }
        out += "$indent[$label]"
    }
    if (entries.isNotEmpty() && entries != listOf(declShort(sig))) {
        out += "$indent// reached from: " + entries.joinToString(", ")
    }
    if (callers.isNotEmpty()) {
        out += "$indent// called by: " + callers.joinToString(", ")
    }
    val meta = mutableListOf<String>()
    if (confidence.isNotEmpty()) meta += "confidence " + confidence.joinToString("/")
    if (gaps.isNotEmpty()) meta += "gaps: " + gaps.joinToString(", ")
    if (meta.isNotEmpty()) out += "$indent// " + meta.joinToString("; ")

    out += indent + methodDecl(sig)
    out += "$indent{"

    val variants = LinkedHashMap<VariantKey, MutableList<PreparedRecord>>()
    for (pr in prepare(records)) {
        val key = VariantKey(pr.condition.code, pr.bodyLines)
        variants.getOrPut(key) { mutableListOf() }.add(pr)
    }
    var effective = variants as Map<VariantKey, List<PreparedRecord>>
    if (variants.size > 1) {
        val nonEmpty = variants.filter { (key, recs) -> isNonEmptyVariant(key, recs) }
        if (nonEmpty.isNotEmpty()) effective = nonEmpty
    }

    val ordered = effective.entries.sortedBy { (_, recs) -> recs.minOf { firstOffset(it.record) } }
    val multi = ordered.size > 1
    val distinctPathSets = ordered.map { (_, recs) -> pathsOf(recs) }.toSet()
    val showPath = multi && distinctPathSets.size > 1

    val body = mutableListOf<String>()
    for ((key, recs) in ordered) {
        val comments = recs.flatMap { it.condition.comments }.distinct()
        val blk = mutableListOf<String>()
        if (showPath) blk += "// path: " + pathsOf(recs).joinToString(" | ")
        comments.forEach { blk += "// $it" }
        if (key.code != null) {
            blk += "if (${key.code})"
            blk += "{"
            blk += (key.bodyLines.ifEmpty { listOf("// no observed statements") }).map { "    $it" }
            blk += "}"
        } else {
            blk += key.bodyLines
        }
        if (blk.isEmpty()) blk += "// no observed statements"
        body += blk
        if (multi) body += ""
    }
    while (body.isNotEmpty() && body.last().isEmpty()) body.removeAt(body.size - 1)

    out += body.map { if (it.isEmpty()) "" else "$indent    $it" }
    out += "$indent}"
    return out
}

private fun unityEventAnnotation(sig: ParsedSignature, wiringIndex: WiringIndex): String {
    val wirings = wiringIndex.wiringsFor(sig.declaringType, sig.name)
    return if (wirings.isNotEmpty()) {
        "UnityEvent(wired: " + wirings.sorted().joinToString(", ") { "\"$it\"" } + ")"
    } else {
        "InspectorCallable"
    }
}
