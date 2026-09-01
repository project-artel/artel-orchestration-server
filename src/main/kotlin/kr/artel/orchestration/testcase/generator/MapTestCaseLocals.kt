package kr.artel.orchestration.testcase.generator

import kr.artel.orchestration.contentmap.evidence.ConditionNode

/**
 * **실행하는 사람이 만들 수 있는 조건만 남긴다**(ARTEL-602).
 *
 * 사전조건이 메서드 안에서만 존재하는 이름을 부르는 일이 있다. 실측(word-venture)에서 12건이 그랬고,
 * 두 가지 모양이다:
 *
 * ```
 * stagePosition == 1                 ← ShowBattle(int) 의 매개변수 이름
 * i == id 그리고 i < backgorunds.Count  ← 루프 인덱스와 매개변수
 * ```
 *
 * QA 담당자가 게임에서 `stagePosition` 을 찾으면 없다. 있는 것은 `MapMove.StagePosition` 이고 둘은
 * 같은 값인데 이름이 다르다 — 대소문자까지 달라서, 같은 값을 두고 저작이 두 갈래로 본다.
 *
 * ## 두 가지를 다르게 다룬다
 *
 * **매개변수는 호출자가 넘긴 값으로 바꾼다.** 문서의 `calls[].args` 가 답을 들고 있다. 이름만
 * 바뀌므로 조건이 좁아지지도 넓어지지도 않는다.
 *
 * **루프 변수는 뺀다.** `i` 는 그 메서드 안에서만 존재하고 호출자를 찾아도 안 풀린다 — 인자로 `"i"`
 * 가 넘어가는 자리도 있어 바꿔도 여전히 `i` 다. 실행자에게 `i == id` 를 요구하는 것은 만들 수 없는
 * 것을 요구하는 것이고, 그것이 이 시스템이 없애려는 거짓 명세다.
 *
 * **빼되 입을 다물지 않는다.** 뺀 자리는 [UNSETTABLE] 로 남아 등급이 그 사실을 말한다. 조건이 없는
 * 것과 조건을 못 적은 것은 다르고, 그 둘을 같게 보이면 "아무 때나 된다"는 거짓이 된다.
 */
object MapTestCaseLocals {

    /** 사전조건에 못 적은 조건이 있다는 표시. 화면과 등급이 이 코드를 읽는다. */
    const val UNSETTABLE = "unsettable-precondition"

    /**
     * @param settled `메서드 → 자리 → 호출자가 넘기는 값`. 값이 하나로 정해지는 것만 든다.
     * @param capabilityId 이 조건이 달린 기능 행. `arg:0` 이 누구의 첫 인자인지 그것이 정한다.
     * @return 고친 조건과, 못 적은 것이 있었는지.
     */
    fun settle(
        node: ConditionNode?,
        capabilityId: Long,
        settled: Map<Long, Map<Int, String>>,
    ): Result {
        if (node == null) return Result(null, false)
        val args = settled[capabilityId].orEmpty()
        val dropped = Dropped()
        return Result(rewrite(node, args, dropped), dropped.any)
    }

    data class Result(val condition: ConditionNode?, val unsettable: Boolean)

    private class Dropped {
        var any = false
    }

    private fun rewrite(node: ConditionNode, args: Map<Int, String>, dropped: Dropped): ConditionNode? =
        when (node) {
            is ConditionNode.Test -> test(node, args, dropped)
            is ConditionNode.Group -> {
                val parts = node.parts.mapNotNull { rewrite(it, args, dropped) }
                when {
                    parts.isEmpty() -> null
                    parts.size == 1 -> parts.single()
                    else -> ConditionNode.Group(node.kind, parts)
                }
            }
            else -> node
        }

    /**
     * 비교 하나를 고친다.
     *
     * **양쪽을 다 본다.** `i == id` 는 왼쪽이 루프 변수이고 오른쪽이 매개변수다. 오른쪽만 바꿔도
     * 왼쪽이 안 풀리면 그 비교는 여전히 만들 수 없다.
     */
    private fun test(node: ConditionNode.Test, args: Map<Int, String>, dropped: Dropped): ConditionNode? {
        val left = resolve(node.left, node.context, args)
        val right = resolve(node.right, node.context, args)
        if (left == null || right == null) {
            dropped.any = true
            return null
        }
        return node.copy(left = left, right = right)
    }

    /**
     * 이름 하나를 실행자가 아는 이름으로.
     *
     * `context` 가 `arg:N` 이면 그 자리의 값으로 바꾼다. 바깥에 대응이 없으면 `null` 이고, 그러면
     * 그 비교는 통째로 빠진다.
     *
     * `this` · `static` 은 그대로 둔다 — 게임에 그 이름으로 있는 값이다. `context` 를 안 든 조건도
     * 그대로 둔다: 모른다고 지우면 진짜 사전조건까지 함께 사라진다.
     *
     * **오른쪽이 리터럴이면 손대지 않는다.** `stagePosition == 1` 의 `1` 은 매개변수가 아니라 값이다.
     */
    private fun resolve(text: String, context: String?, args: Map<Int, String>): String? {
        val name = text.trim()
        if (name.isEmpty() || LITERAL.matches(name)) return name
        // **오브젝트를 거치는 이름은 그대로 둔다.** `MapMove.position` · `Player.PlayerInt().Hp` 는
        // 게임에 그 이름으로 있는 값이다. 여기서 손대면 진짜 사전조건이 사라진다.
        if (!LOCAL.matches(name)) return name

        // 여기부터는 홑이름 — 그 메서드 안에서만 사는 것이다.
        //
        // **`context` 만 믿을 수 없다.** 그것은 비교의 **수신자**를 말하지 좌변이 무엇인지 말하지
        // 않는다 — 실측에서 `i < StoryController.scriptContainer.GetScriptNum()` 의 `context` 가
        // `this` 다(오른쪽이 `this` 를 거치기 때문). 그래서 이름의 모양도 함께 본다.
        val position = context?.takeIf { it.startsWith(ARG) }?.removePrefix(ARG)?.toIntOrNull()
        // 매개변수면 호출자가 넘긴 값으로 되돌린다. 못 찾으면 바깥에 대응이 없는 것이다.
        return position?.let { args[it] }
    }

    private const val ARG = "arg:"

    /** 숫자·문자열·`null` 처럼 값 그 자체. 이름이 아니다. */
    private val LITERAL = Regex("""^(-?\d+(\.\d+)?|".*"|'.*'|null|true|false)$""")

    /**
     * 오브젝트를 거치지 않는 홑이름. 지도가 부르는 값은 `Owner.field` 나 `Type.Method()` 꼴이라,
     * 마디도 괄호도 없는 이름은 그 메서드 안에서만 사는 것이다.
     */
    private val LOCAL = Regex("""^[A-Za-z_]\w*$""")
}
