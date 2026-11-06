package kr.artel.orchestration.contentmap.render

import com.fasterxml.jackson.databind.JsonNode

/** 캡처된 씬 오브젝트 계층 -> `_SceneGraph.cs` 의사 코드 뷰. */
fun renderSceneGraph(objects: List<JsonNode>, scenes: List<String>): String {
    val out = mutableListOf("// scene object graph -- pseudo-code view of the captured hierarchy", "")

    val byScene = LinkedHashMap<String, MutableList<JsonNode>>()
    scenes.forEach { byScene.getOrPut(it) { mutableListOf() } }
    for (o in objects) {
        val scene = o.path("scene").textOrNull() ?: ""
        byScene.getOrPut(scene) { mutableListOf() }.add(o)
    }

    for ((scene, objs) in byScene) {
        out += "scene $scene"
        out += "{"
        for (o in objs) {
            val active = if (o.path("active").isMissingNode) true else o.path("active").asBoolean(true)
            out += "    GameObject \"${o.path("path").textOrNull()}\"   // ${o.path("selector").textOrNull()}" +
                if (active) "" else "  [inactive]"
            out += "    {"
            o.path("visuals").arrayOrEmpty().forEach { v ->
                out += "        ${shortType(v.path("type").textOrNull())}.${v.path("role").textOrNull()} = ${v.path("value").textOrNull()};"
            }
            o.path("components").arrayOrEmpty().forEach { component -> out += renderComponent(component) }
            out += "    }"
        }
        out += "}"
        out += ""
    }
    return out.joinToString("\n")
}

private fun renderComponent(component: JsonNode): List<String> {
    val out = mutableListOf<String>()
    val ctype = shortType(component.path("type").textOrNull())
    val calls = component.path("calls").arrayOrEmpty()
    val refs = component.path("refs").arrayOrEmpty()

    calls.forEach { call ->
        val eventName = stripLeadingUnderscoreM(call.path("event").textOrNull() ?: "")
        val ev = if (eventName.isEmpty()) "onEvent" else eventName.replaceFirstChar { it.lowercase() }
        out += "        $ctype.$ev += ${shortType(call.path("targetType").textOrNull())}.${call.path("method").textOrNull()};   " +
            "// target ${call.path("targetPath").textOrNull()}"
    }
    refs.forEach { ref ->
        val note = if (ref.path("asset").asBoolean(false)) "asset" else (ref.path("path").textOrNull() ?: "?")
        val carries = ref.path("carries").arrayOrEmpty()
        val fullNote = if (carries.isNotEmpty()) {
            note + " carries " + carries.joinToString(", ") { shortType(it.asText()) }
        } else {
            note
        }
        out += "        ${shortType(ref.path("type").textOrNull())} $ctype.${ref.path("field").textOrNull() ?: "?"} = " +
            "\"${ref.path("name").textOrNull()}\";   // $fullNote"
    }
    if (calls.isEmpty() && refs.isEmpty()) out += "        $ctype;"
    return out
}

/** 파이썬 `str.lstrip("m_")` 포팅 — 접두 문자열이 아니라 앞에서부터 'm'/'_' 문자를 계속 벗긴다. */
private fun stripLeadingUnderscoreM(input: String): String = input.dropWhile { it == 'm' || it == '_' }
