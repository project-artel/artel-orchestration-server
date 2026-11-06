package kr.artel.orchestration.contentmap.evidence

/**
 * 두 조건이 **동시에 성립할 수 있나**(ARTEL-554).
 *
 * 갈래에서 결과를 빌려 올 때 쓴다. 빌려 온 케이스의 사전조건은 양쪽 조건을 함께 드는데, 그 둘이
 * 모순이면 **절대 실행할 수 없는 케이스**가 나온다. 실측에서 실제로 나왔다:
 *
 * ```
 * TutorialController.waitingForAcknowledge != 0
 * TutorialController.waitingForAcknowledge == 0     ← 같은 줄에 둘 다
 * ```
 *
 * QA 담당자는 그 전제를 만들 수 없고, 만들 수 없는 것을 만들라고 적는 것이 곧 거짓 명세다.
 *
 * ## 겹치는 쪽으로 틀린다
 *
 * 모르면 `true` 다. 값이 다른 변수를 가리키거나 조건을 못 읽었으면 배타라고 단정할 수 없다.
 * 잘못 이으면 없는 케이스가 생기고 잘못 끊으면 있는 기능이 사라지는데, **사라지는 쪽이 더
 * 나쁘다** — 없는 케이스는 실행하다 알아채지만 없는 것은 아무도 모른다.
 *
 * ## 이름을 보지 않는다
 *
 * `StagePosition` 이든 `wave` 든 뜻을 모른다. 같은 이름이 양쪽에 있을 때 **값의 범위가 겹치나**만
 * 본다. 게임에 붙는 규칙이 하나도 없다.
 */
object ConditionOverlap {

    /**
     * 두 조건이 같은 상황을 가리킬 수 있나.
     *
     * `every` 안의 비교는 전부 요구로 본다. `either` 는 어느 가지인지 모르므로 요구로 세지 않는다 —
     * 하나만 성립하면 되는 것을 반드시 성립해야 한다고 읽으면 없는 배타가 생긴다.
     */
    fun compatible(a: ConditionNode?, b: ConditionNode?): Boolean {
        val left = required(a)
        val right = required(b)
        return left.none { x -> right.any { y -> x.left == y.left && !overlaps(x, y) } }
    }

    /**
     * 둘이 **함께 참일 수 있음이 증명되나**(ARTEL-624).
     *
     * [compatible] 과 방향이 반대다. 저쪽은 "모순을 증명했나"를 묻고, 못 읽은 것은 모순이 아니라고
     * 본다 — 전제를 함부로 버리지 않으려는 보수성이다. 여기는 **줄을 합칠지**를 묻는다. 합치면
     * 기대결과가 한 줄에 섞이므로, 모르면서 합치는 것이 곧 거짓 명세다.
     *
     * 실측에서 그 차이가 드러났다. `LeftArrow` 의 두 갈래가 `position == 1` 과
     * `position == 4 또는 position == 5` 인데, 뒤엣것이 `either` 라 [required] 가 빈 목록을 내고
     * [compatible] 이 양립이라 답했다. 그래서 **한 번 왼쪽을 누르면 마을과 3번 전투에 동시에
     * 도착한다**는 케이스가 나왔다.
     *
     * 그래서 갈래나 못 읽은 것이 섞이면 합치지 않는다. 따로 내면 케이스가 하나 늘 뿐이지만,
     * 합치면 실행하는 사람이 일어나지 않을 일을 기다린다.
     */
    fun provablyTogether(a: ConditionNode?, b: ConditionNode?): Boolean =
        settled(a) && settled(b) && compatible(a, b)

    /** 조건이 **모두 성립해야 하는 비교들**만으로 되어 있나. `either` 하나면 어느 갈래인지 모른다. */
    private fun settled(node: ConditionNode?): Boolean = when (node) {
        null, is ConditionNode.Always, is ConditionNode.Gesture, is ConditionNode.Test -> true
        is ConditionNode.Unknown -> false
        is ConditionNode.Group -> node.kind == GroupKind.EVERY && node.parts.all(::settled)
    }

    /** 반드시 성립해야 하는 비교들. 갈래를 모르는 `either` 와 못 읽은 `unknown` 은 빼고 본다. */
    fun required(node: ConditionNode?): List<ConditionNode.Test> = when (node) {
        null, is ConditionNode.Always, is ConditionNode.Gesture, is ConditionNode.Unknown -> emptyList()
        is ConditionNode.Test -> listOf(node)
        is ConditionNode.Group ->
            if (node.kind == GroupKind.EVERY) node.parts.flatMap { required(it) } else emptyList()
    }

    /**
     * 같은 변수를 두고 두 요구가 함께 성립할 수 있나.
     *
     * 오른쪽이 숫자가 아니면(다른 변수를 가리키면) 값을 모르는 것이라 겹친다고 본다. `!=` 는 점
     * 하나만 빼므로, 그 점을 콕 집어 요구하는 상대가 아니면 언제나 겹칠 수 있다.
     */
    private fun overlaps(x: ConditionNode.Test, y: ConditionNode.Test): Boolean {
        val xv = x.right.toDoubleOrNull()
        val yv = y.right.toDoubleOrNull()
        if (xv == null || yv == null) {
            if (x.operator == "==" && y.operator == "==") return x.right == y.right
            if (x.operator == "==" && y.operator == "!=") return x.right != y.right
            if (x.operator == "!=" && y.operator == "==") return x.right != y.right
            return true
        }
        if (x.operator == "!=" || y.operator == "!=") {
            val point = if (x.operator == "!=") xv else yv
            val other = if (x.operator == "!=") y else x
            return !(other.operator == "==" && other.right.toDoubleOrNull() == point)
        }
        val (lowX, highX) = rangeOf(x.operator, xv)
        val (lowY, highY) = rangeOf(y.operator, yv)
        return maxOf(lowX, lowY) <= minOf(highX, highY)
    }

    /** 비교 하나가 허용하는 값의 구간. `==` 은 점 하나다. */
    private fun rangeOf(operator: String, value: Double): Pair<Double, Double> = when (operator) {
        "==" -> value to value
        ">" -> Math.nextUp(value) to Double.POSITIVE_INFINITY
        ">=" -> value to Double.POSITIVE_INFINITY
        "<" -> Double.NEGATIVE_INFINITY to Math.nextDown(value)
        "<=" -> Double.NEGATIVE_INFINITY to value
        else -> Double.NEGATIVE_INFINITY to Double.POSITIVE_INFINITY
    }
}
