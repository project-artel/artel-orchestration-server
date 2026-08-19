package kr.artel.orchestration.contentmap.ingest

import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.join.CapabilityCandidate
import java.security.MessageDigest

/**
 * 재적재를 넘어 살아남는 기능 참조 키. V42 가 산식을 적재기에 위임했다.
 *
 * `capability.id` 는 `BIGSERIAL` 이라 적재기가 evidence 기능을 지웠다 넣으면 새로 발급되고, `branch` 를
 * 펼치면 번호가 통째로 밀린다. `scene_edge.capability_id` 와 `screen_transition.capability_id` 는 런타임에
 * 알아낸 지식을 든 참조라, 번호가 밀리면 **그 지식이 엉뚱한 기능에 붙는다.**
 *
 * ## 무엇을 넣고 왜 넣나
 *
 * | 칸 | 이것이 없으면 |
 * |---|---|
 * | `scene` | 같은 타입이 두 씬에 놓이면 같은 키가 된다(`GameClearController` 가 두 씬에 붙어 있다) |
 * | `owner` | `owner` 와 `entryId` 의 타입이 어긋난 레코드끼리 겹친다(실측 318 중 71건) |
 * | `entryId` | 서로 다른 진입점이 한 줄로 눌린다 |
 * | `methodId` | **실측 529 후보가 키 371개로 접혔다.** `entryId` 는 "어디로 들어왔나"이고 실제로 도는
 *   메서드는 이것이다 — 한 진입점 아래 코루틴 본체와 그것이 부르는 `set_IsLocked` 가 나란히 매달린다 |
 * | `branchOffset` | 한 메서드 안의 서로 다른 지점이 눌린다 |
 * | 조건 | **실측 `ShowBattle` 의 다섯 branch 는 offset 이 전부 `@3`** 이다. 조건만이 그 다섯을 가른다 |
 * | `inputKey` | `either` 를 쪼갠 두 후보는 가드를 공유해 조건까지 같을 수 있다 |
 * | `control_path` | 조인은 컨트롤마다 후보를 낸다. 한 씬의 두 버튼이 같은 메서드를 부르면 겹친다 |
 * | `spawned_by_field` | 한 씬에 `SpawnOrigin` 이 둘이면 겹친다 |
 *
 * 요구는 하나다 — **한 문서의 후보 목록 위에서 단사(injective)여야 한다.** 겹치면 `uk_capability_map_key`
 * 가 적재를 거절하거나, 더 나쁘게는 서로 다른 스텝 둘이 한 줄로 접힌다.
 */
object CapabilityKey {

    fun of(candidate: CapabilityCandidate): String = sha256Hex(
        listOf(
            candidate.scene,
            candidate.record.owner,
            candidate.record.entryId,
            candidate.record.methodId,
            candidate.branchOffset?.toString().orEmpty(),
            canonical(candidate.condition),
            candidate.inputKey.orEmpty(),
            candidate.binding?.placement?.path.orEmpty(),
            candidate.spawn?.field.orEmpty(),
        ).joinToString(FIELD_SEPARATOR)
    )

    /**
     * 조건 트리를 재현 가능한 한 줄로 적는다.
     *
     * **타입 트리를 쓰고 `conditionJson` 은 쓰지 않는다.** 저쪽은 컬럼에 그대로 들어갈 원문이라 공백과
     * 키 순서가 SDK 실행마다 흔들릴 수 있고, 그러면 같은 기능이 재적재마다 다른 키를 갖는다.
     *
     * 필드 이름을 고정 순서로 적고, 배열 순서는 보존한다 — `either` 의 순서는 뜻을 갖지 않지만 문서가
     * 주는 순서가 안정적이라 정렬로 얻을 것이 없고, 정렬하면 "둘 중 하나"의 원래 모양이 사라진다.
     * null 은 생략하지 않고 그대로 적는다. 없는 것과 빈 문자열이 같은 키를 갖지 않게 한다.
     */
    fun canonical(node: ConditionNode): String = when (node) {
        is ConditionNode.Always -> "always"
        is ConditionNode.Test -> "test(left=${node.left},operator=${node.operator},right=${node.right}," +
            "context=${node.context},offset=${node.offset},subjectLost=${node.subjectLost})"
        is ConditionNode.Gesture -> "gesture(input=${node.input},offset=${node.offset})"
        is ConditionNode.Group -> node.kind.wire + "[" + node.parts.joinToString(",") { canonical(it) } + "]"
        is ConditionNode.Unknown -> "unknown(reason=${node.reason},unread=${node.unread})"
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * 칸 사이 구분자. 값에 나타날 수 없는 문자여야 한다 — 경로에도 조건에도 NUL 은 오지 않는다.
     * 빈 문자열로 이으면 `("a", "bc")` 와 `("ab", "c")` 가 같은 키가 된다.
     */
    private const val FIELD_SEPARATOR = "\u0000"
}
