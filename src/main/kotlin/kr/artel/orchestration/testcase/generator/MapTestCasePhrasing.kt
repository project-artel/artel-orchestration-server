package kr.artel.orchestration.testcase.generator

import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.entity.EffectCategory
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.GroupKind

/**
 * 지도 한 줄을 **사람이 읽는 세 문장**으로 옮긴다(ARTEL-554). DB 도 Spring 도 모른다.
 *
 * ## 식별자를 말로 바꾸지 않는다
 *
 * 이 파일 전체를 떠받치는 규칙이다. `MapMove.position` 을 "캐릭터가 옆으로 이동"으로 옮기는 것이
 * **이 시스템에서 가장 비싼 거짓 명세**다(`CapabilityEntity.summary` 가 같은 자리에 적어 두었다).
 * 경로·타입·메서드·필드는 원문 그대로 쓰고 **사이만 말로 잇는다.** 백틱으로 감싸는 것은 어디까지가
 * 원문인지 읽는 사람이 알게 하려는 것이다.
 *
 * 뜻을 모르는 값은 지어내지 않고 그대로 보여 준다. QA 담당자가 코드 이름을 보는 것이, 코드가
 * 상상한 한국어를 보는 것보다 낫다.
 *
 * ## 입력은 사전조건이 아니라 스텝이다
 *
 * 조건 트리에는 `gesture` 노드가 섞여 있다(`key:Return (down)`). 그것은 **사람이 하는 일**이지
 * 화면이 이미 그런 상태여야 한다는 말이 아니다. 사전조건에 남기면 "Return 키가 눌린 상태에서
 * Return 키를 누른다"가 된다. 그래서 [precondition] 은 `gesture` 를 빼고, [step] 이 그것을 든다.
 */
object MapTestCasePhrasing {

    /**
     * **화면 + 조건.** 기존 케이스와 같은 모양이라 저작의 사전조건 파서가 그대로 읽는다 —
     * `Map_scene 화면인 상태 / MapMove.position == 0`.
     *
     * 조건이 없으면 화면만 남는다. 그것도 사전조건이다 — "그 화면이기만 하면 된다".
     */
    fun precondition(scene: String, condition: ConditionNode?): String {
        val state = "$scene 화면인 상태"
        val text = stateText(condition) ?: return state
        return "$state / $text"
    }

    /**
     * **무엇을 하나.** 조준 대상이 있으면 그것을, 없으면 입력 키를 부른다.
     *
     * `any` 키는 특별히 다룬다 — 그대로 쓰면 "`any` 키를 누른다"가 되어 무엇을 누르라는 것인지
     * 읽는 사람이 알 수 없다. 명세가 아무 키나 된다고 말한 것이므로 그렇게 적는다.
     */
    fun step(
        interaction: String,
        inputKey: String?,
        controlLabel: String?,
        controlPath: String?,
    ): String = when {
        controlLabel != null -> "`$controlLabel` 을(를) 클릭한다"
        controlPath != null -> "`$controlPath` 을(를) 클릭한다"
        inputKey == ANY_KEY -> "아무 키나 누른다"
        inputKey != null -> "`$inputKey` 키를 ${verb(interaction)}"
        else -> "조작 미상($interaction)"
    }

    /**
     * **무엇이 일어나나.** 확인할 수 있는 효과 **하나마다 한 줄**을 낸다.
     *
     * ## 왜 합치지 않나
     *
     * 처음에는 쉼표로 이어 한 줄로 냈다. 실측(적재기 지도의 `Map.MapMove.CharacterMove`)에서 그
     * 결과가 이렇게 나온다:
     *
     * ```
     * 행동     : `RightArrow` 키를 누른다
     * 기대결과 : …battle1 로 바뀐다, …battle2 로 바뀐다, …boss 로 바뀐다
     * ```
     *
     * **실행하는 사람이 무엇을 볼지 알 수 없다.** 셋 다 확인하라는 것인지 하나만인지 문장이 말하지
     * 않는다. 구버전 생성기는 같은 기능에서 케이스 **9개**를 냈고 각각이 하나씩 확인한다 — 같은 키를
     * 눌러도 지금 위치에 따라 결과가 다르니 각각 확인해야 하는 것이 맞다.
     *
     * ## `state` 는 뺀다
     *
     * 값이 바뀌는 것은 사실이지만 **화면에서 확인할 수 없다.** `EffectCategory.assertable` 이 그
     * 판정을 이미 들고 있고 `v_spec_gap` 의 `then-missing` 도 같은 규칙으로 센다 — 여기서 따로
     * 정하면 두 곳이 갈린다.
     *
     * 쓸 수 있는 효과가 하나도 없으면 빈 목록이다. **그 기능은 케이스가 되지 못한다** — 확인할 것이
     * 없는 케이스는 실행해도 통과·실패를 말할 수 없다.
     */
    fun expectedEach(effects: List<CapabilityEffectEntity>): List<String> =
        effects.filter { EffectCategory.from(it.category)?.assertable == true }
            .mapNotNull(::outcome)
            .distinct()

    // --- 조각 ------------------------------------------------------------------------

    /**
     * 효과 하나를 한 마디로.
     *
     * `kind` 는 문자열이다 — 어휘를 enum 으로 못 박은 자리가 아직 없고, 적재기가 문서의 값을 그대로
     * 싣는다. 실측(적재기 지도 486건)에서 나온 열 가지를 다룬다.
     */
    private fun outcome(effect: CapabilityEffectEntity): String? {
        val target = effect.target?.takeIf { it.isNotBlank() } ?: return null
        val detail = effect.detail?.takeIf { it.isNotBlank() }
        return when (effect.kind) {
            "scene" -> "`$target` 화면으로 전환된다"
            "active-state" -> "`$target` 의 표시 상태가 ${detail?.let { "`$it`" } ?: "바뀐다"}"
            "ui-value" -> "`$target` 표시가 ${detail?.let { "`$it` 로 " } ?: ""}갱신된다"
            "instantiate" -> "`$target` 이(가) 생성된다"
            "destroy" -> "`$target` 이(가) 사라진다"
            "animation" -> "`$target` 애니메이션이 실행된다"
            "audio" -> "`$target` 소리가 난다"
            "transform" -> "`$target` 의 위치/형태가 ${detail?.let { "`$it` 로 " } ?: ""}바뀐다"
            "saved" -> "`$target` 이(가) 저장된다"
            // 어휘를 모르면 원문을 그대로 붙여 낸다. 새 `kind` 가 생겼을 때 그 효과가 조용히
            // 사라지는 것보다, 어색해도 보이는 편이 낫다.
            else -> detail?.let { "`$target` 이(가) `$it` 이 된다" } ?: "`$target` 이(가) 바뀐다"
        }
    }

    /**
     * 조건 트리에서 **상태만** 한 줄로. `gesture` 는 뺀다([step] 이 든다).
     *
     * `either` 는 `또는` 으로 잇는다. 여기서 교집합을 취하지 않는 이유는 이 문장이 **사람이 읽는
     * 것**이기 때문이다 — 코드가 근거로 쓸 때는 좁혀야 하지만(`ScenarioConditionTree.guards`),
     * 사람에게는 명세가 말한 대로 보여 주는 것이 맞다.
     */
    private fun stateText(node: ConditionNode?): String? = when (node) {
        null, is ConditionNode.Always, is ConditionNode.Gesture -> null
        // 못 읽은 조건을 사전조건에 적으면 실행하는 사람이 만들 수 없는 상태를 요구받는다.
        is ConditionNode.Unknown -> null
        is ConditionNode.Test -> "${node.left} ${node.operator} ${node.right}".trim().ifBlank { null }
        is ConditionNode.Group -> {
            val joiner = if (node.kind == GroupKind.EVERY) " 그리고 " else " 또는 "
            node.parts.mapNotNull { stateText(it) }.distinct().ifEmpty { null }?.joinToString(joiner)
        }
    }

    private fun verb(interaction: String): String =
        if (interaction == Interaction.PRESS.wire) "누른다" else "입력한다"

    /** 명세가 "아무 키나"라고 말한 자리. 키 이름이 아니다. */
    private const val ANY_KEY = "any"
}
