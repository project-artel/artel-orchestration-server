package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.JsonNode

/**
 * 명세가 든 **조건 트리**를 저작이 쓰는 모양으로 읽는다(ARTEL-533).
 *
 * ## 왜 트리인가 — `given_text` 는 실제 지도에서 비어 있다
 *
 * 경로 계산은 오랫동안 기능의 사전조건을 `capability.given_text` 문자열 하나에서만 읽었다. 그런데
 * **적재기는 그 칸을 채우지 않는다**(`ContentMapIngestService` 가 `givenText = null` 로 쓴다).
 * 의도된 것이다 — `CapabilityEntity.givenText` 의 주석이 "트리 원본은
 * `CapabilityEvidenceEntity.conditionTree`" 라고 못박고 있고, 그 칸은 사람용 보조지 원본이 아니다.
 *
 * 실측(`wv-editor-latest.json` 을 실제 적재기로 적재): 기능 491건 중 `given_text` 가 있는 것 **0건**,
 * `condition_tree` 가 있는 것 **491건**. 손으로 넣은 골든 지도(기능 18)만 `given_text` 를 들고 있었고,
 * 저작 QA 가 지금까지 그 지도 위에서만 돌았다.
 *
 * ## 트리가 문자열보다 정확하다
 *
 * ```json
 * {"kind":"test","left":"BattleWaveController.wave","operator":">=",
 *  "right":"BattleWaveController.battleScript.GetBattleWaveDatas().Count","context":"this"}
 * ```
 *
 * 문자열을 정규식으로 쪼개던 [ScenarioStateReader.comparisonsIn] 은 이것을 못 담는다 — 비교의
 * 오른쪽이 괄호를 못 갖게 되어 있어서(`(a 또는 b)` 를 잘못 자르지 않으려는 조치다)
 * `GetBattleWaveDatas().Count` 가 `GetBattleWaveDatas` 로 잘린다. 트리에서는 그런 잘림이 없다.
 *
 * ## 읽는 규칙
 *
 * - `every` — 모두 성립해야 하므로 **합집합**
 * - `either` — 하나만 성립하면 되므로 **교집합.** 모든 가지에 공통인 것만 확실하다
 *   (문자열의 `또는` 에 적용한 규칙과 같다)
 * - `always` — 조건 없음
 * - `gesture` — 상태가 아니라 입력이다. 값 비교로 쓰지 않고 [text] 에만 싣는다
 * - `unknown` — 문서가 못 읽은 조건이다. **있으면 단정하지 않는다**
 *
 * ## `context` 가 없는 비교는 버린다
 *
 * `context` 가 null 인 `test` 는 주어를 못 찾은 것이고 `subjectLost` 가 그 사유를 든다(실측 47건).
 * `EvidenceModel` 이 그 자리에 적어 둔 대로 **여기서 주어를 상상하는 것이 가장 비싼 거짓 명세다** —
 * `i < objCount` 의 `i` 를 게임 상태로 읽으면 없는 사전조건이 생긴다.
 */
object ScenarioConditionTree {

    /**
     * 반드시 성립해야 하는 비교들. 트리가 없거나 조건이 없으면 빈 목록이다.
     *
     * 빈 목록은 "조건 없음"과 "못 읽었음"을 구분하지 않는다. 경로 계산은 둘 다 **막지 않는 쪽**으로
     * 다루므로(모르는 값은 위반으로 세지 않는다) 여기서 갈라 봐야 쓰는 데가 없다.
     */
    fun guards(tree: JsonNode?): List<Guard> {
        val node = tree?.takeIf { !it.isNull && !it.isMissingNode } ?: return emptyList()
        return when (kindOf(node)) {
            "test" -> testOf(node)?.let(::listOf).orEmpty()
            "every" -> partsOf(node).flatMap { guards(it) }.distinct()
            "either" -> partsOf(node).map { guards(it).toSet() }
                .reduceOrNull { common, next -> common intersect next }.orEmpty().toList()
            else -> emptyList()
        }
    }

    /**
     * 사람에게 보여 줄 한 줄. 조건이 없으면 `null`.
     *
     * [guards] 가 버리는 것도 여기서는 싣는다 — 주어를 못 찾은 비교도, 입력 조건도 사용자가 읽으면
     * 뜻이 통한다. 걸러야 하는 것은 **코드가 그것을 근거로 판단할 때**지 사람이 읽을 때가 아니다.
     */
    fun text(tree: JsonNode?): String? {
        val node = tree?.takeIf { !it.isNull && !it.isMissingNode } ?: return null
        return when (val kind = kindOf(node)) {
            "test" -> "${str(node, "left")} ${str(node, "operator")} ${str(node, "right")}".trim()
                .ifBlank { null }
            "gesture" -> str(node, "input").ifBlank { null }
            "every", "either" -> {
                val joiner = if (kind == "every") " 그리고 " else " 또는 "
                partsOf(node).mapNotNull { text(it) }.ifEmpty { null }?.joinToString(joiner)
            }
            else -> null
        }
    }

    /** 트리의 어느 가지든 `unknown` 이면 참. 이 조건으로는 무엇도 단정할 수 없다. */
    fun incomplete(tree: JsonNode?): Boolean {
        val node = tree?.takeIf { !it.isNull && !it.isMissingNode } ?: return false
        return kindOf(node) == "unknown" || partsOf(node).any { incomplete(it) }
    }

    /**
     * 트리는 `every` 를 대문자로 담기도 한다(실측 8건). 적재된 문서가 그렇게 앉아 있으므로
     * 읽는 쪽에서 맞춘다 — 지도를 다시 굽게 하는 것보다 싸고, 잘못 읽으면 조건이 통째로 사라진다.
     */
    private fun kindOf(node: JsonNode): String = node.path("kind").asText("").lowercase()

    private fun partsOf(node: JsonNode): List<JsonNode> =
        node.path("parts").takeIf { it.isArray }?.toList().orEmpty()

    private fun testOf(node: JsonNode): Guard? {
        if (node.path("context").isNull || node.path("context").isMissingNode) return null
        val left = str(node, "left").ifBlank { return null }
        val operator = str(node, "operator").ifBlank { return null }
        return Guard(ScenarioStateReader.normalize(left), operator, str(node, "right"))
    }

    private fun str(node: JsonNode, field: String): String = node.path(field).asText("").trim()
}
