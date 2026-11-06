package kr.artel.orchestration.testscenario.service

/**
 * **처음부터 걸어갈 수 있나**(ARTEL-635).
 *
 * 지금까지의 검사는 전부 *"이것들이 함께 설 수 있나"* 를 물었다. 나눔도 검수도 그렇다. 그래서
 * **아무도 "출발점에서 첫 스텝이 서나"를 묻지 않았고**, 실측(런 184)에서 이런 시나리오가 통과했다:
 *
 * ```
 * 스텝  1   StagePosition >= 1 요구
 * 스텝  3   StagePosition >= 2 요구
 * 스텝  9   StagePosition >= 4 요구
 * 스텝 13   TurnBattleScene 진입          ← 그 값을 올리는 유일한 길이 맨 끝
 * ```
 *
 * 초기화 직후 그 값은 0이다. 첫 스텝을 하려면 전투를 이겨야 하고, 전투에 들어가는 것이 이
 * 시나리오의 마지막 스텝이다 — **순환이고, 절대 실행되지 않는다.**
 *
 * ## 지도가 답을 안다
 *
 * 그 값이 어디서 오르는지는 지도에 있다(`TurnBattleScene` 의 `+1`, `not-a-step` — 이겨야 한다).
 * 그러니 물을 것은 하나다: **그 씬을 그 앞에서 지나는가.**
 *
 * ## 되돌릴 때 고칠 방법까지 말한다
 *
 * "틀렸다"만 돌려주면 같은 것이 다시 온다(런 152). 그래서 [Blocked] 가 값 이름과 **그 값이
 * 오르는 씬**을 함께 든다 — 부르는 쪽이 거기 가는 조작까지 붙여 한 문장으로 만든다.
 */
object ScenarioStartCheck {

    /**
     * @property step 못 서는 스텝의 자리(1부터).
     * @property variable 그 스텝이 요구하는 값.
     * @property needed 얼마나 요구하나.
     * @property raisedIn 그 값이 오르는 화면들. 비어 있으면 **아무 데서도 안 오른다** —
     *   그때는 순서를 고쳐서 될 일이 아니라 그 케이스를 담을 수 없는 것이다.
     */
    data class Blocked(
        val step: Int,
        val variable: String,
        val needed: String,
        val raisedIn: List<String>,
    )

    /**
     * 출발점에서 못 서는 첫 자리를 찾는다.
     *
     * @param guards 스텝 자리(1부터) → 그 스텝이 요구하는 비교들.
     * @param arrivesAt 스텝 자리 → 그 스텝을 실행하면 도착하는 화면(없으면 null).
     * @param raisesValue 값 이름 → 그 값이 오르는 화면들. 지도가 답한다.
     * @param madeAutomatically 이 값이 **조작으로는 못 만드는** 값인가. 지시로 만들 수 있으면
     *   앞 스텝이 만들었을 수 있으므로 여기서 볼 일이 아니다.
     *
     * **하나만 낸다.** 첫 자리를 고치면 뒤엣것도 대개 함께 풀리고, 다섯 줄을 늘어놓으면 무엇부터
     * 고쳐야 하는지가 흐려진다.
     */
    fun firstBlocked(
        guards: Map<Int, List<Guard>>,
        arrivesAt: Map<Int, String?>,
        raisesValue: (String) -> List<String>,
        madeAutomatically: (String) -> Boolean,
    ): Blocked? {
        val visited = mutableSetOf<String>()
        for (step in guards.keys.sorted()) {
            for (guard in guards.getValue(step)) {
                if (!needsProgress(guard)) continue
                if (!madeAutomatically(guard.variable)) continue
                val raisedIn = raisesValue(guard.variable)
                // 그 값을 올리는 화면을 앞에서 지났으면 이 시나리오가 스스로 만든 것이다.
                if (raisedIn.any { it in visited }) continue
                return Blocked(step, guard.variable, "${guard.operator} ${guard.value}", raisedIn)
            }
            arrivesAt[step]?.let(visited::add)
        }
        return null
    }

    /**
     * **진행을 요구하는 비교인가.**
     *
     * 초기화 직후의 값보다 큰 것을 요구할 때만 걸린다. `!= 5` 나 `== 0` 은 시작 상태로도
     * 성립할 수 있어 여기서 막을 일이 아니다 — 막으면 될 것을 못 하게 한다.
     *
     * 숫자가 아닌 비교는 자리를 말하지 않으므로 지나간다. 모르는 것을 막지 않는다.
     */
    private fun needsProgress(guard: Guard): Boolean {
        if (guard.operator !in PROGRESS) return false
        val wanted = guard.value.toDoubleOrNull() ?: return false
        return wanted > 0
    }

    /** 자리를 말하면서 **0보다 큰 것**을 요구하는 비교. */
    private val PROGRESS = setOf("==", ">=", ">")
}
