package kr.artel.orchestration.contentmap.join

import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceRecord
import kr.artel.orchestration.contentmap.evidence.GroupKind
import kr.artel.orchestration.contentmap.evidence.flatten

/**
 * 레코드 하나의 조건 트리에서 **따로 후보가 되어야 할 `branch`**를 뽑아낸다.
 *
 * 레코드는 기능이 아니라 "이 메서드의 이 지점에서 이런 일이 일어난다"이고, 한 지점이 서로 다른 키
 * 두 개에 응답할 수 있다. `capability.input_key` 는 단일 컬럼이라 그 레코드를 한 행으로 적으면
 * 키 하나가 조용히 사라진다 — 그래서 여기서 가른다.
 *
 * 씬도 같은 이유로 여기서 본다. `Scenes.GameClearController` 는 `GameClearScene` 과
 * `GameOverScene` 두 곳에 붙어 있고(실측), 조건 트리가 자기 안에서 그 둘을 가른다. 씬을 모르는 채로
 * 적으면 두 화면에 서로의 기능이 섞인다.
 *
 * 계산은 순수하다 — 레코드 하나와 씬 이름 하나가 전부다. 배치·`wiring`·스폰은 다른 단계가 붙인다.
 */
object ConditionBranches {

    /**
     * [record] 의 조건 트리를 [scene] 에서 성립할 수 있는 `branch`들로 가른다.
     *
     * `branch`가 하나도 없으면 **그 레코드는 이 씬의 기능이 아니다.** 실측: `Scenes.GameClearController`
     * 14건 중 `GameClearScene` 에서는 13건, `GameOverScene` 에서는 3건만 남는다.
     */
    fun from(record: EvidenceRecord, scene: String): List<ConditionBranch> =
        splitOnInput(record.condition)
            .filter { it.possibleIn(scene) }
            .map { it.toBranch() }

    /**
     * `either` 중 **입력을 가르는 것만** 펼친다.
     *
     * 실측 `either` 68건 중 `branch`마다 다른 gesture 를 든 것은 4건뿐이고(전부
     * `Map.MapMove::CharacterMove`), 나머지 64건은 순수 상태 조건의 논리합이다. 그 64건까지 펼치면
     * `input_key` 도 `branchOffset` 도 똑같은 행이 `branch` 수만큼 복제된다 — `Cards.CardManager` 처럼
     * `either` 를 9개 중첩한 레코드(실측 2건)는 곱해져서 수십 행이 된다. 순수 상태 논리합은
     * `condition_tree` 컬럼이 `either` 모양 그대로 담을 수 있으므로 가를 이유가 없다.
     *
     * `either` 를 `every` 로 접지 않는다 — 접으면 "둘 중 하나"가 "둘 다"가 되어 영영 성립하지 않는
     * 조건이 된다. 펼치는 쪽은 `branch`를 **따로** 내보내는 것이라 그 사고가 일어나지 않는다.
     */
    private fun splitOnInput(node: ConditionNode): List<ConditionNode> = when {
        node !is ConditionNode.Group -> listOf(node)

        node.kind == GroupKind.EITHER ->
            if (node.splitsInput()) node.parts.flatMap { splitOnInput(it) } else listOf(node)

        // every 아래에서 한 자식이 갈리면 나머지 가드는 `branch`마다 그대로 따라가야 한다. 실측에서 한
        // every 아래 갈리는 자식은 최대 하나라 이 곱은 커지지 않는다(446 레코드 → 450 `branch`).
        else -> node.parts
            .fold(listOf(emptyList<ConditionNode>())) { rows, part ->
                val alternatives = splitOnInput(part)
                rows.flatMap { row -> alternatives.map { row + it } }
            }
            .map { ConditionNode.Group(GroupKind.EVERY, it) }
    }

    /**
     * 이 `either` 의 `branch`들이 서로 다른 입력을 뜻하는가.
     *
     * gesture 가 아니라 **gesture 의 렌더 문자열 집합**을 비교한다. 같은 키를 두 번 적은 논리합은
     * 가르지 않아야 하고(행이 둘로 늘 뿐 `input_key` 는 같다), 실측 4건은 `branch`마다 정확히 하나의
     * 서로 다른 키를 든다.
     */
    private fun ConditionNode.Group.splitsInput(): Boolean =
        parts.map { part -> part.gestures().map { it.input }.toSet() }.distinct().size > 1

    /**
     * 이 `branch`가 [scene] 에서 성립할 수 있는가.
     *
     * **`every` 사슬로만 내려간다.** `either` 안의 씬 조건은 반대쪽 `branch`로 만족될 수 있어 `branch` 전체를
     * 죽이지 못한다. 실측 12건은 모두 `every`/루트 위치라 지금은 차이가 없지만, 이 구분이 없으면
     * 문서가 조금만 달라져도 살아 있는 기능이 통째로 사라진다.
     */
    private fun ConditionNode.possibleIn(scene: String): Boolean =
        conjunctiveTests().none { it.contradictsScene(scene) }

    /**
     * 이 test 가 [scene] 에서 **절대 성립하지 않는가.**
     *
     * 판정할 수 없으면 false 를 낸다 — 모르는 비교 연산자나 따옴표가 없는 오른쪽 항(변수 비교)을
     * "성립하지 않는다"로 읽으면 근거가 말하지 않은 이유로 기능을 지우게 된다. 거르지 못한 `branch`는
     * 남을 뿐이고, 지운 `branch`는 되돌아오지 않는다.
     */
    private fun ConditionNode.Test.contradictsScene(scene: String): Boolean {
        if (left != ACTIVE_SCENE_NAME) return false
        val named = right.unquotedOrNull() ?: return false
        return when (operator) {
            "==" -> named != scene
            "!=" -> named == scene
            else -> false
        }
    }

    private fun ConditionNode.gestures(): List<ConditionNode.Gesture> =
        flatten().filterIsInstance<ConditionNode.Gesture>()

    private fun ConditionNode.toBranch(): ConditionBranch {
        val gesture = gestures().minByOrNull { it.offset }
        return ConditionBranch(
            condition = this,
            gesture = gesture,
            branchOffset = gesture?.offset ?: flatten().mapNotNull { it.offsetOrNull() }.minOrNull(),
        )
    }

    private fun ConditionNode.offsetOrNull(): Int? = when (this) {
        is ConditionNode.Test -> offset
        is ConditionNode.Gesture -> offset
        else -> null
    }
}

/**
 * 한 레코드에서 갈라져 나온 조건 `branch` 하나. [CapabilityCandidate] 한 줄의 재료다.
 */
data class ConditionBranch(
    /**
     * 이 `branch`만 남긴 조건 트리.
     *
     * 성립 불가능한 `branch`를 덜어낸 것 외에는 원본 모양 그대로다 — `every` 중첩도 펴지 않고 `either`
     * 도 뒤집지 않는다. 되짚을 때 사람이 읽는 것은 원본 모양이고, 원본 전체는
     * [kr.artel.orchestration.contentmap.evidence.EvidenceRecord.conditionJson] 이 계속 들고 있다.
     */
    val condition: ConditionNode,
    /** 이 `branch`의 입력 조건. 없으면 null — 조작 없이 일어나는 `branch`다. */
    val gesture: ConditionNode.Gesture?,
    /**
     * 한 메서드 안의 서로 다른 지점을 가르는 IL 위치.
     *
     * gesture 가 있으면 그 offset 을, 없으면 `branch` 안의 가장 작은 offset 을 쓴다. gesture 를 먼저
     * 보는 이유는 그것이 이 `branch`를 **다른 `branch`와 다르게 만든 바로 그 자리**이기 때문이다 — 가드
     * test 는 `branch`끼리 공유되므로(실측: `MapMove.position`·`InteractionLock.IsLocked` 가 양쪽에
     * 같이 붙는다) 최솟값을 쓰면 두 `branch`가 같은 offset 을 갖는다.
     *
     * 입력을 가르는 `either` 만 펼치므로 `branch`마다 gesture 가 다르고, 실측 446 레코드 전부에서
     * 한 레코드 안 `branch`들의 offset 이 겹치지 않는다.
     *
     * 조건이 `always` 이면 IL 위치가 없어 null 이다.
     */
    val branchOffset: Int?,
)
