package kr.artel.orchestration.testscenario.service

/**
 * **걸을 수 있는 흐름으로 나눈다**(ARTEL-657).
 *
 * 지금은 무엇을 묶고 어떤 순서로 놓을지를 모델이 42건을 한 번에 들고 정한다. 그 둘이 실행
 * 가능성을 정하는 판단인데, 모델이 가장 약한 자리가 그 둘이다 — 실측 A/B 에서 전량을 한 번에
 * 주면 시나리오 26개에 못 가는 오름 9건이 나왔고, 여정 하나씩 주면 9개에 1건이었다.
 *
 * 그래서 여기로 옮긴다. 모델에게는 범위·이름·문장이 남는다.
 *
 * ## 짝이 아니라 걸음이다
 *
 * [ScenarioFlowMatrix] 는 두 자리 **사이만** 답한다. 짝으로 맞아도 이어 붙이면 틀릴 수 있다 —
 * 실측(런 216, 시나리오 703)에서 진행도를 0 으로 만드는 브리지가 바로 다음 자리는 살리고 두 칸
 * 뒤를 죽였다. 그래서 여기서는 **상태를 들고** 걷고, 뒤를 깨는 걸음은 아예 안 놓는다.
 *
 * 어긋나는지 보는 규칙은 [ScenarioContradictionCheck] 와 같은 것을 쓴다. 규칙이 둘이면 만드는
 * 쪽과 보는 쪽이 언젠가 갈라지고, 그때 어느 쪽이 맞는지 알 수 없다.
 *
 * ## 시작 조건이 산출물이다
 *
 * 걸음이 성립하려면 무엇이 참이어야 했는지가 걷다 보면 나온다. 지금은 다 쓰고 나서 훑어 적어서
 * 흐름 안에서 갈리는 값의 **한쪽만** 고른다 — 실측(런 217, 시나리오 712)에서 `== 5` 로 시작하라고
 * 적어 놓고 5번째 스텝이 `!= 5` 를 요구했다. 걸으면서 내면 그런 일이 없다.
 *
 * **모르게 된 값을 다시 요구하는 것은 시작 조건이 아니다.** 그건 흐름이 지나온 자리에서 일어나야
 * 하는 일이고(전투를 이긴다), GAP 으로 남을 자리다.
 */
object ScenarioFlowPlan {

    /**
     * 흐름에 놓을 자리 하나.
     *
     * @property clears 이 자리를 지나면 **모르게 되는** 값. 그 화면에서 저절로 바뀌는 것들이다.
     */
    data class Case(
        val id: Long,
        val requires: List<Guard> = emptyList(),
        val sets: Map<String, String> = emptyMap(),
        val clears: Set<String> = emptySet(),
        /**
         * **게임을 켜면 열리는 화면에 있는 자리인가**(ARTEL-659).
         *
         * 씬 그래프는 순환이라 입구를 구조로는 알 수 없고, 그래서 앞서는 "요구가 가장 적은
         * 케이스"에서 출발했다 — 계산의 편의이지 게임의 진실이 아니다. 실측(런 233)에서
         * `진행도 == 5, 위치 == 0` 에서 시작하라는 흐름이 나왔다. 아무도 그렇게 시작하지 않는다.
         */
        val atEntry: Boolean = false,
    )

    /**
     * 두 자리 사이.
     *
     * @property sets 사이에 끼는 **조작이 정하는 값**. 이것을 안 보면 계산이 "조작이 있다"까지만
     *   알고 그 조작이 무엇을 바꾸는지는 모른 채 흐름을 낸다 — 실측(런 232)에서 진행도를 0 으로
     *   되돌리는 조작이 사이에 끼고 두 칸 뒤가 5 를 요구하는 흐름이 셋 나왔다.
     * @property clears 그 사이를 지나며 **모르게 되는** 값(GAP 이 그렇다).
     */
    data class Link(
        val kind: ScenarioFlowMatrix.Link,
        val sets: Map<String, String> = emptyMap(),
        val clears: Set<String> = emptySet(),
    )

    /**
     * 걸을 수 있는 흐름 하나.
     *
     * @property opening 시작할 때 이미 참이어야 하는 것. 흐름이 스스로 만들지 못하고 **한 번도
     *   모르게 된 적 없는** 요구다.
     * @property gaps 사이에 지시할 수 없는 자리가 몇 군데인가. 흐름을 고를 때의 값이다.
     */
    data class Flow(
        val caseIds: List<Long>,
        val opening: List<Guard> = emptyList(),
        val gaps: Int = 0,
    )

    /**
     * [cases] 전부를 덮는 흐름 묶음. 한 자리는 한 흐름에만 들어간다.
     *
     * **가장 적은 수로 나누는 것을 목표로 하지 않는다.** 이어 붙일 수 있다고 다 붙이면 스텝
     * 마흔짜리 한 판이 나오고, 그건 사람이 돌릴 물건이 아니다. 여기서는 *"이 자리 뒤에 놓을 수
     * 있는 것 중 가장 싼 것"* 을 고르는 것까지 하고, 몇 개로 낼지·어디서 끊을지는 부르는 쪽이
     * 정한다.
     */
    fun of(
        cases: List<Case>,
        maxCases: Int = MAX_CASES,
        maxGaps: Int = MAX_GAPS,
        opening: (Guard) -> Boolean = { true },
        link: (Long, Long) -> Link,
    ): List<Flow> {
        val byId = cases.associateBy { it.id }
        val left = cases.map { it.id }.toMutableSet()
        val flows = mutableListOf<Flow>()

        while (left.isNotEmpty()) {
            // **입구에서 시작한다.** 게임을 켜면 열리는 화면의 자리를 먼저 잡고, 그 다음은 요구가
            // 적은 것부터 — 요구가 적다는 것은 앞에 와 있어야 할 것이 적다는 뜻이다.
            val start = left.minByOrNull { id ->
                val case = byId.getValue(id)
                (if (case.atEntry) 0 else 1_000) + case.requires.size
            } ?: break
            flows += walk(start, left, byId, link, maxCases, maxGaps, opening)
        }
        return flows
    }

    /** 한 흐름을 끝까지 걷는다. 놓은 자리는 [left] 에서 뺀다. */
    private fun walk(
        start: Long,
        left: MutableSet<Long>,
        byId: Map<Long, Case>,
        link: (Long, Long) -> Link,
        maxCases: Int,
        maxGaps: Int,
        keepOpening: (Guard) -> Boolean,
    ): Flow {
        val known = mutableMapOf<String, String>()
        val forgotten = mutableSetOf<String>()
        val opening = mutableListOf<Guard>()
        val walked = mutableListOf<Long>()
        var gaps = 0
        var here = start

        while (true) {
            val case = byId.getValue(here)
            for (guard in case.requires) {
                val have = known[guard.variable.lowercase()]
                // 아는 값과 어긋나는 자리는 애초에 안 놓는다 — 아래 [fits] 가 막는다.
                if (have != null) continue
                // **모르게 된 적 있는 값은 시작 조건이 아니다.** 흐름이 지나온 자리에서 일어나야
                // 하는 일이고, 그 자리는 GAP 으로 남는다.
                if (guard.variable.lowercase() in forgotten) continue
                // **거의 모든 케이스가 요구하는 것은 시작 조건이 아니다.** 실측(지도 27)에서
                // `InteractionLock.IsLocked == 0` 이 전제에 스물두 번 나오는데 그 값을 쓰는 기능은
                // 하나도 없다 — 그런 것까지 적으면 안내가 스물여덟 줄이 되고 아무도 안 읽는다.
                if (!keepOpening(guard)) continue
                opening += guard
            }
            // **정하는 것이 먼저고 모르게 되는 것이 나중이다.** 한 자리가 값을 정하면서 동시에
            // 그 값이 저절로 바뀌는 화면이면, 남는 것은 "모른다"다 — 덜 아는 쪽이 안전하다.
            case.sets.forEach { (variable, value) -> known[variable.lowercase()] = value }
            case.clears.forEach { known.remove(it.lowercase()); forgotten += it.lowercase() }
            walked += here
            left -= here

            // **끝을 정한다.** 이어 붙일 수 있다고 다 붙이면 스텝 마흔짜리 한 판이 나오고, 그건
            // 사람이 한 번에 돌릴 물건이 아니다 — 실측(런 228)에서 그렇게 나왔다. 지나갈 자리가
            // 몇 군데까지 참을 만한지는 **제품 판단**이라 부르는 쪽이 정할 수 있게 열어 둔다.
            if (walked.size >= maxCases || gaps >= maxGaps) return Flow(walked, opening.distinctBy { Triple(it.variable, it.operator, it.value) }, gaps)

            val next = left
                .map { it to link(here, it) }
                .filter { (id, edge) ->
                    edge.kind != ScenarioFlowMatrix.Link.BLOCKED &&
                        edge.kind != ScenarioFlowMatrix.Link.UNCHECKED &&
                        fits(byId.getValue(id), known, edge)
                }
                .minByOrNull { (id, edge) -> cost(edge) * 100 + byId.getValue(id).requires.size }
                ?: return Flow(walked, opening.distinctBy { Triple(it.variable, it.operator, it.value) }, gaps)

            val (id, edge) = next
            if (edge.kind == ScenarioFlowMatrix.Link.BY_PLAY) gaps++
            edge.sets.forEach { (variable, value) -> known[variable.lowercase()] = value }
            edge.clears.forEach { known.remove(it.lowercase()); forgotten += it.lowercase() }
            here = id
        }
    }

    /**
     * 이 자리를 지금 놓을 수 있나. **아는 값과 어긋나면 못 놓는다** — 그것이 703 에서 저작이
     * 낸 흐름이고, 어떤 스텝으로도 성립하지 않는다.
     *
     * 사이를 지나며 모르게 되는 값은 어긋남으로 세지 않는다(전투를 지나면 진행도가 오른다).
     */
    private fun fits(case: Case, known: Map<String, String>, edge: Link): Boolean {
        // 사이의 조작이 값을 바꾸고 지나간다 — 그 뒤의 상태로 재야 한다.
        val after = known + edge.sets.mapKeys { it.key.lowercase() }
        return case.requires.none { guard ->
            val key = guard.variable.lowercase()
            if (edge.clears.any { it.lowercase() == key }) return@none false
            val have = after[key] ?: return@none false
            !guard.holds(have)
        }
    }

    /**
     * 한 흐름에 놓을 자리 수와 지나갈 자리 수의 기본값.
     *
     * 근거는 실측이다 — 지금 모델이 42건을 5~9개 흐름으로 내고 있으니 흐름당 대여섯이다. 지나갈
     * 자리는 하나만 있어도 "게임을 해서 거기까지 가라"는 말이라, 한 판에 셋을 넘기면 검증이 아니라
     * 플레이가 된다. **정답이 아니라 출발점이고, 부르는 쪽이 바꿀 수 있다.**
     */
    const val MAX_CASES = 12
    const val MAX_GAPS = 3

    /** 싼 것부터 놓는다 — 아무것도 안 넣는 것이 가장 싸고, 사람이 지나가야 하는 것이 가장 비싸다. */
    private fun cost(edge: Link): Int = when (edge.kind) {
        ScenarioFlowMatrix.Link.BESIDE -> 0
        ScenarioFlowMatrix.Link.BY_OPERATION -> 1
        ScenarioFlowMatrix.Link.BY_PLAY -> 2
        else -> 9
    }
}
