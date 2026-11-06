package kr.artel.orchestration.contentmap.render

/**
 * `"System.Void NS.T::M(System.Int32)"` 형태의 IL 시그니처 문자열 파싱 + 타입 텍스트 축약.
 *
 * 둘 다 "C# 시그니처를 사람이 읽는 텍스트로 바꾼다"는 같은 성격이라 한 파일에 둔다 —
 * `shortType` 의 사실상 유일한 소비자가 이 파일이 만든 [ParsedSignature] 의 타입 문자열이기도
 * 하다. JSON 트리 접근(I/O 경계)은 [EvidenceDocument] 가 전담하므로 여기서는 하지 않는다.
 */

private val PRIMS = mapOf(
    "System.Void" to "void",
    "System.Int32" to "int",
    "System.Single" to "float",
    "System.Boolean" to "bool",
    "System.String" to "string",
    "System.Object" to "object",
    "System.Double" to "double",
    "System.Int64" to "long",
    "System.Byte" to "byte",
    "System.Collections.IEnumerator" to "IEnumerator",
)

private val SIGNATURE_REGEX = Regex("""^(?<ret>[^ ]+) (?<decl>[^:]+)::(?<name>[^(]+)\((?<params>.*)\)$""")
private val GENERATED_REGEX = Regex("""/<|>d__|<>c__|<>\d""")
private val GENERIC_ARITY_REGEX = Regex("""^(.*?)`\d+<(.*)>$""")

data class ParsedSignature(
    val returnType: String,
    val declaringType: String,
    val name: String,
    val params: List<String>,
    val raw: String?,
)

/** `"System.Void NS.T::M(System.Int32)"` -> [ParsedSignature]. 형태가 안 맞으면 이름만 원문으로 채운 껍데기. */
fun parseSignature(sig: String?): ParsedSignature {
    val match = sig?.let { SIGNATURE_REGEX.matchEntire(it) }
        ?: return ParsedSignature(returnType = "void", declaringType = "", name = sig ?: "?", params = emptyList(), raw = sig)
    return ParsedSignature(
        returnType = match.groups["ret"]!!.value,
        declaringType = match.groups["decl"]!!.value,
        name = match.groups["name"]!!.value,
        params = splitGenericArgs(match.groups["params"]!!.value),
        raw = sig,
    )
}

/** 컴파일러가 만든 심볼(람다, 상태 머신, 클로저)인지 — `<M>d__N`, `<>c__`, `/<` 패턴. */
fun isGeneratedSignature(sig: String?): Boolean = sig != null && GENERATED_REGEX.containsMatchIn(sig)

/** `"Decl.Method"` — 선언 타입의 마지막 세그먼트 + 메서드 이름. `// reached from:` 등 짧은 표기에 쓴다. */
fun declShort(sig: String?): String {
    val p = parseSignature(sig)
    return "${p.declaringType.substringAfterLast('.')}.${p.name}"
}

/**
 * 제네릭/배열 깊이를 세며 최상위(depth==0) 쉼표에서만 나눈다 — `Dictionary<string, List<int>>`
 * 같은 중첩도 깨지지 않는다.
 */
fun splitGenericArgs(s: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    val cur = StringBuilder()
    for (ch in s) {
        if (ch == ',' && depth == 0) {
            out += cur.toString()
            cur.clear()
            continue
        }
        when (ch) {
            '<', '[' -> depth++
            '>', ']' -> depth--
        }
        cur.append(ch)
    }
    if (cur.toString().isNotBlank()) out += cur.toString()
    return out.map { it.trim() }
}

/** IL 타입 문자열 -> C# 짧은 표기(`System.Int32` -> `int`, 제네릭 인자 재귀 축약). */
fun shortType(type: String?): String {
    if (type == null) return "var"
    val t = type.trim()
    PRIMS[t]?.let { return it }
    val arity = GENERIC_ARITY_REGEX.matchEntire(t)
    if (arity != null) {
        val base = arity.groupValues[1].substringAfterLast('.')
        val inner = splitGenericArgs(arity.groupValues[2]).joinToString(", ") { shortType(it) }
        return "$base<$inner>"
    }
    if (t.contains('<') && t.endsWith('>')) {
        val idx = t.indexOf('<')
        val base = t.substring(0, idx)
        val inner = t.substring(idx + 1, t.length - 1)
        return "${shortType(base)}<${splitGenericArgs(inner).joinToString(", ") { shortType(it) }}>"
    }
    return t.substringAfterLast('.')
}

/** 메서드 선언부 한 줄(`.ctor`/getter/setter/일반 메서드 형태 분기). */
fun methodDecl(sig: String?): String {
    val p = parseSignature(sig)
    val params = p.params.mapIndexed { i, t -> "${shortType(t)} p$i" }.joinToString(", ")
    val ret = shortType(p.returnType)
    return when {
        p.name == ".ctor" -> "${p.declaringType.substringAfterLast('.')}($params)"
        p.name.startsWith("get_") -> "$ret ${p.name.removePrefix("get_")} { get; }"
        p.name.startsWith("set_") -> {
            val setterType = p.params.firstOrNull()?.let { shortType(it) } ?: "var"
            "$setterType ${p.name.removePrefix("set_")} { set; }"
        }
        else -> "$ret ${p.name}($params)"
    }
}
