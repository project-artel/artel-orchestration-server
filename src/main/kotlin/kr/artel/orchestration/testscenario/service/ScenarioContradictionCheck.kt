package kr.artel.orchestration.testscenario.service

/**
 * **이 흐름이 자기가 말한 것과 어긋나나**(ARTEL-656).
 *
 * ## 걸어 보는 것이 아니다
 *
 * 게임을 돌리지 않는다. 지도가 적어 둔 것끼리 부딪히는지만 본다. 그래서 할 수 있는 말은 하나뿐이다 —
 * *"이 흐름은 자기가 말한 것과 어긋난다."* **"이 흐름은 실제로 된다"는 절대 못 한다.**
 *
 * 못 잡는 것을 적어 둔다: 지도가 안 적은 효과 · 관측 없는 전이 · 몇 번 눌러야 하는지 · 저절로
 * 일어나는 일 · 무엇이 될지 모르는 값. 그런데도 두는 이유는 **지금까지 잰 실행 불가가 전부 이
 * 종류**이기 때문이다. 실측(런 216, 시나리오 703):
 *
 * ```
 * 18. [1720]  진행도 == 5
 * 19. 브리지   클릭 (진행도 → 0)     ← 20번을 살리려고 코드가 끼웠다
 * 21. [1626]  진행도 == 5 를 요구      ← 19번이 방금 0 으로 만들었다
 * ```
 *
 * 게임을 켤 필요가 없다. 둘 다 지도가 한 말이다.
 *
 * ## 아는 값만 본다
 *
 * 모르면 아무 말도 안 한다 — 이 저장소 전체의 규칙이다. 값이 어떻게 될지 모르게 되는 자리
 * ([Step.clears])에서는 그 값을 놓아 준다. 그러지 않으면 **멀쩡한 흐름을 틀렸다고 한다**:
 * 전투에 들어갔다 나오면 진행도가 올라 있는데, 그것을 안 놓으면 다음 자리가 전부 어긋나 보인다.
 */
object ScenarioContradictionCheck {

    /**
     * 흐름 위의 한 걸음. **문장이 아니라 사실로 받는다** — 스텝의 글을 되읽어 값을 뽑으면
     * 문구를 다듬을 때마다 검사가 조용히 깨진다(이 저장소가 V56 에서 이미 걷어낸 길이다).
     *
     * @property requires 이 걸음이 요구하는 것. 케이스면 그 전제고 브리지면 비어 있다.
     * @property sets 이 걸음을 지나면 **확정되는** 값.
     * @property clears 이 걸음을 지나면 **모르게 되는** 값. 저절로 바뀌는 화면을 지나거나,
     *   메우지 못한 구간을 지나거나, 무엇이 될지 모르는 쓰기를 지날 때다.
     */
    data class Step(
        val at: Int,
        val caseId: Long?,
        val requires: List<Guard> = emptyList(),
        val sets: Map<String, String> = emptyMap(),
        val clears: Set<String> = emptySet(),
    )

    /**
     * 어긋난 한 자리.
     *
     * @property have 그 자리에서 이미 알고 있던 값. 무엇이 그렇게 만들었는지는 [madeAt] 이 가리킨다.
     */
    data class Contradiction(
        val at: Int,
        val caseId: Long?,
        val guard: Guard,
        val have: String,
        val madeAt: Int,
    ) {
        fun describe(): String =
            "${at}번째 스텝이 ${guard.variable} ${guard.operator} ${guard.value} 를 요구하는데, " +
                "${madeAt}번째 스텝이 ${guard.variable} 을 $have 으로 만들어 두었습니다"
    }

    /**
     * 값 이름을 맞추는 자리. **대소문자를 안 가린다** — 지도는 같은 값을 `StagePosition` ·
     * `MapMove.StagePosition` · `StageDataSingleton.stagePosition` 세 이름으로 부르고, 경로
     * 조회도 같은 규칙으로 맞춘다([ScenarioPathRepository.findEffectsWriting]).
     *
     * 마디가 겹치는 서로 다른 값을 같은 것으로 볼 수 있다는 것은 알려진 한계인데, 이 검사에서는
     * **넓게 잡는 쪽이 안전하다** — 같다고 보면 그 값을 놓아 주게 되고, 놓아 주면 안 짚는다.
     * 잘못 짚는 것보다 덜 짚는 것이 낫다.
     */
    private fun key(name: String): String = name.lowercase()

    /** 위에서 아래로 한 번 걷는다. 되짚지 않는다 — 흐름은 순서이지 집합이 아니다. */
    fun find(walk: List<Step>): List<Contradiction> {
        val known = mutableMapOf<String, String>()
        val madeAt = mutableMapOf<String, Int>()
        val found = mutableListOf<Contradiction>()

        for (step in walk) {
            for (guard in step.requires) {
                val have = known[key(guard.variable)] ?: continue
                if (guard.holds(have)) continue
                found += Contradiction(
                    at = step.at, caseId = step.caseId, guard = guard,
                    have = have, madeAt = madeAt[key(guard.variable)] ?: 0,
                )
            }
            step.clears.forEach { known.remove(key(it)); madeAt.remove(key(it)) }
            step.sets.forEach { (variable, value) ->
                known[key(variable)] = value
                madeAt[key(variable)] = step.at
            }
        }
        return found
    }
}
