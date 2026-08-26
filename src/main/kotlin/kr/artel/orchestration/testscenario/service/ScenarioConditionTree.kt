package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.GroupKind

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
 * ## 읽는 일은 파서가 한다
 *
 * JSON 을 [ConditionNode] 로 옮기는 것은 **`EvidenceParser.parseCondition` 이 한다.** 여기서 한 벌
 * 더 쓰지 않는다 — 두 벌이 되면 두 곳이 서로 다르게 관대해지고, 실제로 그렇게 될 뻔했다. 저장된
 * 트리에는 `kind` 가 대문자이거나 아예 없는 노드가 섞여 있는데(적재기가 갈래를 쪼갤 때 타입 트리를
 * 그대로 직렬화한다), 그 관대함은 파서가 이미 갖추었다.
 *
 * 이 파일에 남는 것은 **저작의 판단**뿐이다 — 무엇을 근거로 쓰고 무엇을 안 쓰나.
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
     * 반드시 성립해야 하는 비교들. 조건이 없거나 못 읽었으면 빈 목록이다.
     *
     * 빈 목록은 "조건 없음"과 "못 읽었음"을 구분하지 않는다. 경로 계산은 둘 다 **막지 않는 쪽**으로
     * 다루므로(모르는 값은 위반으로 세지 않는다) 여기서 갈라 봐야 쓰는 데가 없다. 갈라야 하는
     * 소비자는 [incomplete] 를 묻는다.
     */
    fun guards(node: ConditionNode?): List<Guard> = when (node) {
        null, is ConditionNode.Always, is ConditionNode.Gesture, is ConditionNode.Unknown -> emptyList()
        is ConditionNode.Test -> testOf(node)?.let(::listOf).orEmpty()
        is ConditionNode.Group -> when (node.kind) {
            GroupKind.EVERY -> node.parts.flatMap { guards(it) }.distinct()
            GroupKind.EITHER -> node.parts.map { guards(it).toSet() }
                .reduceOrNull { common, next -> common intersect next }.orEmpty().toList()
        }
    }

    /**
     * 사람에게 보여 줄 한 줄. 조건이 없으면 `null`.
     *
     * [guards] 가 버리는 것도 여기서는 싣는다 — 주어를 못 찾은 비교도, 입력 조건도 사용자가 읽으면
     * 뜻이 통한다. 걸러야 하는 것은 **코드가 그것을 근거로 판단할 때**지 사람이 읽을 때가 아니다.
     */
    fun text(node: ConditionNode?): String? = when (node) {
        null, is ConditionNode.Always, is ConditionNode.Unknown -> null
        is ConditionNode.Test -> "${node.left} ${node.operator} ${node.right}".trim().ifBlank { null }
        is ConditionNode.Gesture -> node.input.ifBlank { null }
        is ConditionNode.Group -> {
            val joiner = if (node.kind == GroupKind.EVERY) " 그리고 " else " 또는 "
            node.parts.mapNotNull { text(it) }.ifEmpty { null }?.joinToString(joiner)
        }
    }

    /** 어느 가지든 못 읽은 것이 섞였나. 이 조건으로는 무엇도 단정할 수 없다. */
    fun incomplete(node: ConditionNode?): Boolean = when (node) {
        is ConditionNode.Unknown -> true
        is ConditionNode.Group -> node.parts.any { incomplete(it) }
        else -> false
    }

    private fun testOf(node: ConditionNode.Test): Guard? {
        if (node.context == null) return null
        val left = node.left.ifBlank { return null }
        val operator = node.operator.ifBlank { return null }
        return Guard(ScenarioStateReader.normalize(left), operator, node.right)
    }
}
