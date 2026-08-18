package kr.artel.orchestration.contentmap.render

import com.fasterxml.jackson.databind.JsonNode

/** 타입 하나(record 목록) -> 의사 C# 파일 한 장. */
fun renderType(typeName: String, records: List<JsonNode>, extraHeader: List<String>, wiringIndex: WiringIndex): String {
    val (namespace, className) = splitNamespace(typeName)
    val byMethod = groupByLogicalSig(records)

    val lines = mutableListOf(
        "// generated from wv-editor capture -- pseudo-C#, not compilable",
        "// evidence-derived: bodies show only observed statements, in IL offset order",
    )
    lines += extraHeader
    lines += ""
    lines += "using UnityEngine;"
    lines += "using UnityEngine.SceneManagement;"
    lines += "using System.Collections;"
    lines += ""

    var indent = ""
    if (namespace.isNotEmpty()) {
        lines += "namespace $namespace"
        lines += "{"
        indent = "    "
    }
    lines += "${indent}class $className : MonoBehaviour"
    lines += "$indent{"

    var first = true
    for ((sig, recs) in byMethod) {
        if (!first) lines += ""
        first = false
        lines += renderMethod(sig, recs, "$indent    ", wiringIndex)
    }
    lines += "$indent}"
    if (namespace.isNotEmpty()) lines += "}"

    return lines.joinToString("\n") + "\n"
}

/** `"NS.Sub.Type"` -> `("NS.Sub", "Type")`. 점이 없으면 네임스페이스 없이 전체가 클래스 이름. */
private fun splitNamespace(typeName: String): Pair<String, String> {
    val idx = typeName.lastIndexOf('.')
    return if (idx == -1) "" to typeName else typeName.substring(0, idx) to typeName.substring(idx + 1)
}
