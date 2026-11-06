package kr.artel.orchestration.testscenario.service

/**
 * 두 케이스가 **이 순서로 이어지는가**(ARTEL-466).
 *
 * 케이스는 상태 그래프의 간선처럼 생겼다 — 141번이 `position 0→1`, 142번이 `1→2`다. 그러면
 * "141 다음 142"는 이어지고 "142 다음 141"은 어긋난다. 그 판정이 여기 있다.
 *
 * **왜 필요한가.** 어긋난 순서가 지금은 조용히 덮인다. 142를 앞에 놓으면 경로 계산이 "0에서
 * 1로 가려면 RightArrow"라고 답하고 코드가 이동 스텝을 끼워 넣는다 — 실행은 되지만 케이스가
 * 의도한 순서가 아니고, 화면에는 순서가 이상한데 스텝이 하나 더 붙은 모양으로 남는다. 메우는
 * 것과 순서가 틀린 것은 다른 문제이므로 다르게 말해야 한다.
 *
 * **일반화하지 않는다.** `before` 에 조작 효과를 더하면 `after` 가 된다고 가정하지 않는다 — 한
 * 조작이 두 값을 건드리거나, 그 값이 다른 데서도 바뀌거나, 효과가 조건부일 수 있다. 그래서
 * **케이스가 스스로 선언한 `state_after` 만** 쓰고, 선언이 없으면 [NO_OPINION] 이다. 모르는 것을
 * 틀렸다고 말하지 않는 것이 이 서비스 전체를 관통하는 규칙이다.
 */
object ScenarioOrderCheck {

    /**
     * 앞 케이스를 실행한 뒤([fromAfter]) 뒤 케이스의 요구([toBefore])가 성립하는가, 아니면 그
     * 반대([toAfter] → [fromBefore])가 성립하는가.
     *
     * 판정에 쓰는 것은 **선언된 값이 실제로 결정하는 가드뿐이다.** 값을 모르는 가드는 통과도
     * 위반도 아니어서, 그것만 있으면 답은 [ScenarioOrdering.NO_OPINION] 이다 — 비교가 성립하지
     * 않는 자리에서 순서를 말하면 그게 곧 지어내는 것이다.
     */
    fun verdict(
        fromAfter: Map<String, String>,
        fromBefore: List<Guard>,
        toAfter: Map<String, String>,
        toBefore: List<Guard>,
    ): ScenarioOrdering {
        val forward = holds(fromAfter, toBefore)
        val backward = holds(toAfter, fromBefore)
        return when {
            forward == true -> ScenarioOrdering.CHAINED
            // 이 순서로는 어긋나는데 뒤집으면 이어진다 — 그때만 순서를 지적한다.
            forward == false && backward == true -> ScenarioOrdering.REVERSED
            else -> ScenarioOrdering.NO_OPINION
        }
    }

    /**
     * 이 상태가 그 가드들을 만족하나. **결정되는 가드가 하나도 없으면 null**(말할 것이 없다)이다.
     */
    private fun holds(state: Map<String, String>, guards: List<Guard>): Boolean? {
        val decided = guards.filter { state.containsKey(it.variable) }
        if (decided.isEmpty()) return null
        return decided.all { it.holds(state.getValue(it.variable)) }
    }
}

/** 두 케이스의 순서에 대한 세 답. */
enum class ScenarioOrdering {
    /** 이 순서로 이어진다. */
    CHAINED,

    /** 어긋나는데 **뒤집으면** 이어진다. 순서가 바뀐 것으로 보인다. */
    REVERSED,

    /** 판단하지 않는다 — 선언된 값이 그 비교를 결정하지 못한다. */
    NO_OPINION,
}
