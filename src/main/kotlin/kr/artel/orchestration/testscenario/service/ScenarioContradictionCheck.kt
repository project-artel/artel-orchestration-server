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
        /**
         * 이 걸음을 지나면 **줄지는 않는** 값(ARTEL-672).
         *
         * [clears] 와 갈라 두는 이유가 전부다. 전투를 지나면 진행도가 얼마인지는 모르지만
         * *줄지 않았다는 것은 안다* — 지도가 그 값을 올리는 법을 `+1` 하나로만 말하고 내리는
         * 법을 그 자리에 두지 않았기 때문이다.
         *
         * 통째로 놓아 주면 그 뒤로는 무엇을 요구해도 할 말이 없어진다. 실측(런 247)에서 검사가
         * 0건을 답하는데 같은 결과물에 거꾸로 놓인 자리가 네 군데 있었고, 넷 다 사이에 전투가
         * 끼어 있었다. 순서를 만드는 유일한 값에서 눈을 감은 셈이다.
         */
        val climbs: Set<String> = emptySet(),
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

    /**
     * 한 번 걷고 나온 것 둘.
     *
     * @property opening 걷는 동안 **한 번도 정해진 적 없고 모르게 된 적도 없이** 요구된 것.
     *   흐름이 스스로 만들지 못하는 것이라 시작할 때 이미 참이어야 한다. 사이에서 모르게 된 값을
     *   다시 요구하는 것은 여기 안 든다 — 그건 흐름이 지나온 자리에서 일어날 일이고 GAP 이 말한다.
     */
    data class Walked(
        val contradictions: List<Contradiction> = emptyList(),
        val opening: List<Guard> = emptyList(),
    )

    /** 어긋난 자리만. 시작 조건까지 필요하면 [walk] 를 쓴다. */
    fun find(walk: List<Step>): List<Contradiction> = walk(walk).contradictions

    /**
     * 위에서 아래로 한 번 걷는다. 되짚지 않는다 — 흐름은 순서이지 집합이 아니다.
     *
     * **어긋남과 시작 조건은 같은 걸음에서 나온다**(ARTEL-660). 따로 계산하면 규칙이 둘이 되고,
     * 그러면 갈라진다 — 실측(런 233)에서 저장된 안내가 계산과 **정반대**를 적었다(계산은
     * `진행도 != 5` 인데 안내는 `== 5` 로 시작하라고 했다).
     */
    fun walk(walk: List<Step>): Walked {
        val known = mutableMapOf<String, String>()
        // **줄지 않는다는 것만 아는 값**(ARTEL-672). 정확히 얼마인지는 모르고 바닥만 안다.
        val atLeast = mutableMapOf<String, Double>()
        val madeAt = mutableMapOf<String, Int>()
        val forgotten = mutableSetOf<String>()
        val found = mutableListOf<Contradiction>()
        val opening = mutableListOf<Guard>()

        for (step in walk) {
            for (guard in step.requires) {
                val have = known[key(guard.variable)]
                if (have != null) {
                    if (guard.holds(have)) continue
                    found += Contradiction(
                        at = step.at, caseId = step.caseId, guard = guard,
                        have = have, madeAt = madeAt[key(guard.variable)] ?: 0,
                    )
                    continue
                }
                val floor = atLeast[key(guard.variable)]
                if (floor != null) {
                    // 바닥보다 **낮게** 요구하는 것만 짚는다. 더 높게 요구하는 것은 흐름이 걸어
                    // 오르면 되는 자리라 어긋남이 아니고, 시작 조건도 아니다.
                    if (asksBelow(guard, floor)) {
                        found += Contradiction(
                            at = step.at, caseId = step.caseId, guard = guard,
                            have = "적어도 ${plain(floor)}", madeAt = madeAt[key(guard.variable)] ?: 0,
                        )
                    }
                    continue
                }
                // 한 번도 정해진 적 없고 모르게 된 적도 없다면 **흐름이 스스로 못 만드는 것**이다.
                if (key(guard.variable) !in forgotten) opening += guard
            }
            // **정하는 것이 먼저고 모르게 되는 것이 나중이다.** 한 걸음이 값을 정하면서 동시에
            // 그 값이 저절로 바뀌는 화면을 지나면, 남는 것은 "모른다"다 — 덜 아는 쪽이 안전하다.
            step.sets.forEach { (variable, value) ->
                known[key(variable)] = value
                atLeast.remove(key(variable))
                madeAt[key(variable)] = step.at
            }
            // 오르기만 하는 자리를 지난다 — 얼마인지는 놓고 **바닥은 들고 간다**.
            step.climbs.forEach {
                val floor = known[key(it)]?.toDoubleOrNull() ?: atLeast[key(it)]
                known.remove(key(it))
                if (floor != null) atLeast[key(it)] = floor else forgotten += key(it)
            }
            step.clears.forEach {
                known.remove(key(it)); atLeast.remove(key(it)); madeAt.remove(key(it)); forgotten += key(it)
            }
        }
        return Walked(found, opening.distinctBy { Triple(it.variable, it.operator, it.value) })
    }

    /**
     * 이 요구가 **바닥보다 낮은 값을 달라고 하나.**
     *
     * 여기서만 짚는다. `>= 4` 는 바닥이 2여도 걸어 오르면 되고, `!= 5` 는 5가 아닌 어떤 값도
     * 되니 바닥과 싸우지 않는다 — 무엇이 될지 모르는 값을 위반이라 부르지 않는 규칙 그대로다.
     */
    private fun asksBelow(guard: Guard, floor: Double): Boolean {
        val want = guard.value.toDoubleOrNull() ?: return false
        return when (guard.operator) {
            "==" -> want < floor
            "<" -> want <= floor
            "<=" -> want < floor
            else -> false
        }
    }

    /** 정수면 소수점을 안 붙인다 — 사람이 읽을 문장에 들어가는 값이다. */
    private fun plain(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
