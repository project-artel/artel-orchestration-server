package kr.artel.orchestration.testcase.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.common.error.BadRequestException

/**
 * 이 케이스를 돌릴 때 agent 에게 허용할 cheat · behavior (계약 4절 `Test case permission`).
 *
 * 두 키 각각 `"all"`, `"none"`, 이름 목록 중 하나이고, 빠진 키는 `"all"` 이다. run 이 연 목록을 **좁히기만 하고 넓히지 않는다**.
 * 그 교집합은 agent-server 가 `QaCaseRef.functions` 로 받아 계산하므로 orchestration 은 값을 해석하지 않고 검증해 저장하고
 * 건넬 뿐이다.
 *
 * 타입을 `JsonNode` 로 둔 것은 한 키가 문자열이거나 배열이기 때문이다. 검증은 [validated] 가 한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TestCaseFunctions(
    val cheats: JsonNode? = null,
    val behaviors: JsonNode? = null,
) {
    /**
     * 모양을 검사하고 저장할 JSON 으로 만든다. 모르는 값은 400 이다 — 조용히 `all` 로 읽으면 cheat 를 막으려던 케이스가 열린다.
     */
    fun validated(): Json {
        val out = JsonNodeFactory.instance.objectNode()
        cheats?.let { out.set<JsonNode>("cheats", check("functions.cheats", it)) }
        behaviors?.let { out.set<JsonNode>("behaviors", check("functions.behaviors", it)) }
        return Json.of(out.toString())
    }

    private fun check(field: String, node: JsonNode): JsonNode {
        if (node.isNull) throw BadRequestException("$field must be \"all\", \"none\" or a list of names")
        if (node.isTextual) {
            if (node.asText() !in KEYWORDS) throw BadRequestException("$field must be \"all\", \"none\" or a list of names")
            return node
        }
        if (!node.isArray) throw BadRequestException("$field must be \"all\", \"none\" or a list of names")
        val names = JsonNodeFactory.instance.arrayNode()
        val seen = LinkedHashSet<String>()
        for (item in node) {
            val name = item.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw BadRequestException("$field must not contain a blank name")
            if (seen.add(name)) names.add(name)
        }
        return names
    }

    private companion object {
        val KEYWORDS = setOf("all", "none")
    }
}
