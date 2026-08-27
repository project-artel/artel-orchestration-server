package kr.artel.orchestration.contentmap.evidence

/**
 * **반복하면 닿는 자리를 가려낸다**(ARTEL-613).
 *
 * 사전조건에 그 메서드 안에서만 사는 값이 오는 일이 있다 — `i >= 총개수` 같은 루프 카운터다.
 * 실행하는 사람은 `i` 를 읽을 수 없으므로 그것은 전제가 못 된다. 그런데 **만들 수는 있다**:
 * 같은 조작을 끝까지 되풀이하면 반드시 그 자리에 닿는다.
 *
 * 그래서 지우는 것과 옮기는 것을 갈라야 한다. 실측(word-venture)에서 갈리는 자리가 이렇다:
 *
 * ```
 * 되돌아가는 갈래  i <  StoryController.scriptContainer.GetScriptNum()   ← 대사가 남았다
 * 빠져나온 갈래    i >= StoryController.scriptContainer.GetScriptNum()   ← 다 넘겼다 → 맵으로 간다
 * ```
 *
 * 앞엣것은 루프가 도는 동안 늘 참이라 따로 만들 것이 없다 — 지운다. 뒤엣것은 **끝까지 눌러야**
 * 닿는 자리라, 지우면 "아무 키나 한 번 누르면 타이틀로 간다"는 거짓이 된다. 스텝으로 옮긴다.
 *
 * 구버전 `specs_v2` 의 `observable.loop_exits` 와 같은 판정이고, 같은 주석을 남긴다 —
 * *"사람은 `i` 를 읽을 수 없지만 끝까지 눌러 그 자리를 만들 수는 있다"*.
 *
 * 재료는 문서의 `loopsBackTo` 다. 파서가 이미 읽고 있었는데 적재가 안 앉혀서, `repeat_until_done`
 * 칸이 실측 491행 내내 `false` 였다.
 */
object LoopExits {

    /**
     * 되돌아가는 갈래들의 가드를 **뒤집어** 모은다. 그것이 곧 "빠져나온 자리"다.
     *
     * @param looping `loops_back_to` 가 있는 갈래들의 조건. 어느 갈래가 되도는지는 부르는 쪽이
     *   골라서 준다 — 여기는 뒤집는 일만 한다.
     *
     * 홑이름만 본다. 오브젝트를 거치는 값(`MapMove.position`)은 실행하는 사람이 직접 만들 수 있어
     * 반복으로 미룰 일이 아니고, 미루면 만들 수 있는 전제를 스텝 문구로 흘려보낸다.
     */
    fun of(looping: List<ConditionNode>): Set<Guard> =
        looping.flatMap(::tests)
            .mapNotNull { test ->
                val opposite = OPPOSITE[test.operator] ?: return@mapNotNull null
                if (!LOCAL.matches(test.left.trim())) return@mapNotNull null
                Guard(test.left.trim(), opposite, test.right.trim())
            }
            .toSet()

    /** 이 조건이 **끝까지 되풀이해야** 닿는 자리인가. */
    fun reachedByRepeating(condition: ConditionNode?, exits: Set<Guard>): Boolean =
        tests(condition).any { Guard(it.left.trim(), it.operator, it.right.trim()) in exits }

    /** 비교 하나. 갈래를 견주는 데 필요한 세 조각만 든다. */
    data class Guard(val left: String, val operator: String, val right: String)

    private fun tests(node: ConditionNode?): List<ConditionNode.Test> = when (node) {
        is ConditionNode.Test -> listOf(node)
        is ConditionNode.Group -> node.parts.flatMap(::tests)
        else -> emptyList()
    }

    /** 서로를 부정하는 비교. 되돌아가는 가드의 반대가 빠져나온 가드다. */
    private val OPPOSITE = mapOf(
        "<" to ">=", ">=" to "<", ">" to "<=", "<=" to ">", "==" to "!=", "!=" to "==",
    )

    /**
     * 오브젝트를 거치지 않는 홑이름. 지도가 부르는 값은 `Owner.field` 꼴이라, 마디도 괄호도 없는
     * 이름은 그 메서드 안에서만 사는 것이다.
     *
     * **대소문자를 보지 않는다.** "지역 변수는 소문자"는 사람의 관례이지 구조가 아니고, 관례를
     * 어긴 이름 하나가 조용히 필드로 읽힌다. 주인이 있느냐만 본다.
     */
    private val LOCAL = Regex("""^[A-Za-z_]\w*$""")
}
