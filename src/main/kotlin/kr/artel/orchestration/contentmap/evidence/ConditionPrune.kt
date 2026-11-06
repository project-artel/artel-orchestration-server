package kr.artel.orchestration.contentmap.evidence

/**
 * **이미 못 박은 것을 갈래마다 되뇌지 않는다**(ARTEL-624).
 *
 * 한 기능의 갈래들을 한 케이스로 묶으면 조건이 `A 그리고 (X 또는 (Y 그리고 A) 또는 (Z 그리고 A))`
 * 꼴이 된다. 갈래마다 같은 잠금 검사를 다시 들어서다. 실측(word-venture)에서 그렇게 263자짜리
 * 전제가 나왔다:
 *
 * ```
 * IsLocked == 0 그리고 (StagePosition == 1 또는 (StagePosition >= 1 그리고 position == 0)
 *                     또는 ((position == 4 그리고 IsLocked == 0) 또는 (position == 5 그리고 IsLocked == 0)))
 * ```
 *
 * 바깥 AND 가 `IsLocked == 0` 을 이미 참으로 만들었으므로 안쪽의 그것들은 아무것도 더 말하지 않는다.
 * 지우면 읽을 것이 절반으로 준다. **뜻은 그대로다** — 참인 것을 참인 것과 AND 해도 같다.
 *
 * ## 지우는 것과 안 지우는 것
 *
 * 같은 비교가 **글자 그대로** 겹칠 때만 지운다. `x > 3` 이 `x > 5` 를 함의하는지 따지지 않는다 —
 * 그 추론은 값의 종류를 알아야 하고, 모르면서 지우면 전제가 조용히 거짓이 된다. 여기서 버는 것은
 * 읽기 편함뿐이므로, 확실하지 않으면 남기는 편이 언제나 옳다.
 *
 * 지우고 나서 갈래가 하나만 남으면 그 하나가 곧 그 자리다. 다 지워지면 그 갈래는 바깥 조건만으로
 * 이미 참이고, 그러면 **그 OR 전체가 참**이라 통째로 사라진다.
 */
object ConditionPrune {

    /** [node] 에서 군더더기를 걷는다. 뜻을 바꾸지 않는다. */
    fun of(node: ConditionNode?): ConditionNode? = node?.let { prune(it, emptySet()) }

    /**
     * **코드 위치를 뺀 조건의 서명.**
     *
     * [ConditionNode.Test] 는 `offset` 을 값에 넣어, 같은 비교라도 코드의 다른 줄에서 나오면 다른
     * 것이 된다. 여기서 필요한 것은 "무엇을 견주는가"뿐이다 — 위치까지 보면 되풀이를 못 알아보고,
     * 정체에 쓰면 게임 코드가 한 줄 밀릴 때마다 케이스가 통째로 갈린다.
     */
    fun signature(node: ConditionNode?): String = when (node) {
        null -> ""
        is ConditionNode.Test -> "${node.context.orEmpty()}|${node.left}|${node.operator}|${node.right}"
        is ConditionNode.Gesture -> "gesture|${node.input}"
        is ConditionNode.Group -> node.parts.joinToString(",", "${node.kind.wire}(", ")", transform = ::signature)
        is ConditionNode.Unknown -> "unknown|${node.reason.orEmpty()}|${node.unread.orEmpty()}"
        ConditionNode.Always -> "always"
    }

    /**
     * @param known 여기 닿기까지 바깥 AND 들이 이미 참으로 만든 비교들.
     */
    private fun prune(node: ConditionNode, known: Set<String>): ConditionNode? = when (node) {
        is ConditionNode.Group -> when (node.kind) {
            GroupKind.EVERY -> every(node, known)
            GroupKind.EITHER -> either(node, known)
        }
        // 바깥이 이미 못 박았으면 여기서 다시 말할 것이 없다.
        else -> node.takeIf { signature(it) !in known }
    }

    /**
     * AND 는 **왼쪽부터 오른쪽으로** 아는 것을 불려 가며 훑는다. 앞선 조각이 참으로 만든 것을 뒤엣것이
     * 되뇌면 그때 걸린다.
     */
    private fun every(group: ConditionNode.Group, known: Set<String>): ConditionNode? {
        val kept = mutableListOf<ConditionNode>()
        val gathered = known.toMutableSet()
        for (part in group.parts) {
            val pruned = prune(part, gathered) ?: continue
            kept += pruned
            gathered += leaves(pruned)
        }
        return join(kept, group)
    }

    /**
     * OR 는 갈래끼리 아는 것을 나누지 않는다 — 한 갈래가 참이라고 옆 갈래가 참인 것은 아니다.
     * 바깥에서 받은 것만 물려준다.
     *
     * 갈래 하나가 통째로 지워지면 그것은 **바깥 조건만으로 이미 참**이라는 뜻이고, OR 은 그 갈래
     * 하나로 참이 된다. 그러면 이 OR 전체가 더 말하는 것이 없어 사라진다.
     */
    private fun either(group: ConditionNode.Group, known: Set<String>): ConditionNode? {
        val kept = mutableListOf<ConditionNode>()
        for (part in group.parts) {
            val pruned = prune(part, known) ?: return null
            kept += pruned
        }
        return join(kept, group)
    }

    private fun join(kept: List<ConditionNode>, group: ConditionNode.Group): ConditionNode? = when {
        kept.isEmpty() -> null
        // 하나만 남은 무리는 무리가 아니다. 괄호를 남기면 읽는 사람이 갈래를 찾는다.
        kept.size == 1 -> kept.single()
        else -> group.copy(parts = kept)
    }

    /** AND 로 이어져 **함께 참**인 비교들. OR 밑은 어느 갈래인지 모르므로 아무것도 못 약속한다. */
    private fun leaves(node: ConditionNode): Set<String> = when {
        node is ConditionNode.Group && node.kind == GroupKind.EVERY ->
            node.parts.flatMap(::leaves).toSet()
        node is ConditionNode.Group -> emptySet()
        else -> setOf(signature(node))
    }
}
