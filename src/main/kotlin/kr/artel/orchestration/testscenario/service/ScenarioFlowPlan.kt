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
        /**
         * **가장 싼 갈래가 요구하는 진행도**(ARTEL-666).
         *
         * [requires] 는 *"무엇이 반드시 참이어야 하나"* 라 `또는` 갈래에서는 교집합만 남는다. 그
         * 규칙은 맞다 — 갈래 하나만 만족해도 되니까. 그런데 **먼저 오는 자리를 고르는 데는 그것이
         * 아무 말도 안 해 준다**: 실측(런 242)에서 지도의 `Return` 넷이 전부 요구 하나(`IsLocked`)
         * 진행 0 으로 점수가 같아져 순서가 임의로 잡혔다.
         *
         * ```
         * 1876   (진행도 >= 1 그리고 위치 == 0) 또는 진행도 == 2      가장 싼 갈래 → 1
         * 1873   위치 == 3 또는 (진행도 >= 4 …) 또는 진행도 == 5      가장 싼 갈래 → 3
         * ```
         *
         * 갈래 중 가장 적게 요구하는 것으로 재 봤는데(런 243) **더 나빠졌다** — 점수는 갈렸지만
         * 지도의 걸음이 흐름 셋으로 흩어지고 지나갈 자리가 8 에서 11 로 늘었다. 순서를 가르는 데는
         * 이것 말고 다른 신호가 필요하다. 지금은 **아무도 안 채운다.**
         */
        val reach: Int = 0,
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
        /**
         * **게임을 켜면 값이 무엇으로 시작하나**(ARTEL-665). 입구에서 출발하는 흐름에만 깐다 —
         * 다른 데서 시작하는 흐름은 그때까지 게임이 해 온 것이 있고, "처음"을 가정할 수 없다.
         */
        starting: Map<String, String> = emptyMap(),
        opening: (Guard) -> Boolean = { true },
        /**
         * **고른 이유를 남길 자리**(ARTEL-666). 순수 계산이 기록에 매이지 않도록 함수로 받는다.
         *
         * 없으면 아무것도 안 한다. 실측(런 241)에서 순서가 왜 그렇게 잡혔는지 못 짚었고, 비용·요구
         * 개수·진행도 셋 중 무엇이 이기는지 볼 방법이 없었다 — 추측으로 고치지 않으려면 이게 먼저다.
         */
        log: (String) -> Unit = {},
        link: (Long, Long) -> Link,
    ): List<Flow> {
        val byId = cases.associateBy { it.id }
        val left = cases.map { it.id }.toMutableSet()
        val flows = mutableListOf<Flow>()
        // 값 이름은 한 번만 맞춰 둔다 — 아래 [fits] 와 [walk] 이 같은 열쇠로 찾아야 한다.
        val from = starting.mapKeys { it.key.lowercase() }

        while (left.isNotEmpty()) {
            // **입구에서 시작한다.** 게임을 켜면 열리는 화면의 자리를 먼저 잡고, 그 다음은 요구가
            // 적은 것부터 — 요구가 적다는 것은 앞에 와 있어야 할 것이 적다는 뜻이다.
            val start = left.minByOrNull { id ->
                val case = byId.getValue(id)
                // 입구의 자리라도 **처음 상태와 어긋나면** 거기서 시작할 수 없다 — 게임을 막 켠
                // 사람에게 "진행도 5 로 와 있어라"라고 하는 자리가 그것이다.
                val fitsStart = case.atEntry && fits(case, from, Link(ScenarioFlowMatrix.Link.BESIDE))
                // 이어 붙일 때와 같은 잣대다 — 덜 요구하는 자리가 게임에서 먼저 온다.
                (if (fitsStart) 0 else 1_000_000) + case.requires.size * 100 + progress(case)
            } ?: break
            val seed = if (byId.getValue(start).atEntry) from else emptyMap()
            log("흐름 ${flows.size + 1} 시작 ← $start (입구=${byId.getValue(start).atEntry})")
            flows += walk(start, left, byId, link, maxCases, maxGaps, opening, seed, log)
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
        starting: Map<String, String>,
        log: (String) -> Unit,
    ): Flow {
        val known = starting.toMutableMap()
        // **값을 확정하지 않는 요구도 기억한다.** `!= 5` 는 값을 못 정하지만 *"5는 아니다"* 를
        // 말한다 — 그것을 안 들고 있으면 바로 뒤에 `== 5` 를 놓게 된다(런 216, 시나리오 703).
        val asserted = mutableMapOf<String, MutableList<Guard>>()
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
            case.requires.forEach {
                asserted.getOrPut(it.variable.lowercase(), ::mutableListOf).add(it)
            }
            case.clears.forEach {
                known.remove(it.lowercase()); asserted.remove(it.lowercase()); forgotten += it.lowercase()
            }
            walked += here
            left -= here

            // **끝을 정한다.** 이어 붙일 수 있다고 다 붙이면 스텝 마흔짜리 한 판이 나오고, 그건
            // 사람이 한 번에 돌릴 물건이 아니다 — 실측(런 228)에서 그렇게 나왔다. 지나갈 자리가
            // 몇 군데까지 참을 만한지는 **제품 판단**이라 부르는 쪽이 정할 수 있게 열어 둔다.
            if (walked.size >= maxCases || gaps >= maxGaps) return Flow(walked, opening.distinctBy { Triple(it.variable, it.operator, it.value) }, gaps)

            val weighed = left.map { it to link(here, it) }
            val open = weighed.filter { (id, edge) ->
                edge.kind != ScenarioFlowMatrix.Link.BLOCKED &&
                    edge.kind != ScenarioFlowMatrix.Link.UNCHECKED &&
                    fits(byId.getValue(id), known, edge) &&
                    agrees(byId.getValue(id), asserted, edge)
            }
            // **왜 그것을 골랐는지 남긴다.** 점수와 그 재료를 함께 적어야 셋 중 무엇이 이겼는지 안다.
            log(
                "  $here 다음 — 후보 ${open.size}/${weighed.size}: " +
                    open.sortedBy { (id, edge) -> score(byId.getValue(id), edge) }
                        .take(4)
                        .joinToString(", ") { (id, edge) ->
                            val case = byId.getValue(id)
                            "$id(${edge.kind}·요구${case.requires.size}·진행${progress(case)}" +
                                "=${score(case, edge)})"
                        }
            )
            val next = open
                // **같은 값이면 덜 요구하는 것부터.** 비용도 요구 개수도 같은 자리가 흔한데, 그때
                // 순서가 임의가 되어 실측(런 239·240)에서 진행도 5 → 4 → 3 → 2 로 거꾸로 놓였다.
                // 진행을 요구하는 값이 낮은 쪽이 게임에서 먼저 오는 자리다.
                .minByOrNull { (id, edge) -> score(byId.getValue(id), edge) }
                ?: return Flow(walked, opening.distinctBy { Triple(it.variable, it.operator, it.value) }, gaps)

            val (id, edge) = next
            if (edge.kind == ScenarioFlowMatrix.Link.BY_PLAY) gaps++
            edge.sets.forEach { (variable, value) ->
                known[variable.lowercase()] = value
                // 사이의 조작이 값을 정하면 앞서 모아 둔 요구는 더 이상 지금 이야기가 아니다.
                asserted.remove(variable.lowercase())
            }
            edge.clears.forEach {
                known.remove(it.lowercase()); asserted.remove(it.lowercase()); forgotten += it.lowercase()
            }
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

    /**
     * 이 자리의 요구가 **앞서 요구한 것들과 함께 참일 수 있나**(ARTEL-665).
     *
     * 값을 확정하는 요구만 보면 놓치는 자리가 있다. `!= 5` 는 값을 못 정하지만 뒤의 `== 5` 와
     * 함께 참일 수 없다 — 실측(런 216, 시나리오 703)이 그 모양이었다.
     *
     * 두 비교가 함께 설 수 있는지는 **값을 넣어 보면 안다.** 후보는 두 값과 그 언저리다 —
     * 비교가 정수 위주라 이것으로 갈린다. 숫자가 아니면 글자로 견준다.
     */
    private fun agrees(
        case: Case,
        asserted: Map<String, List<Guard>>,
        edge: Link,
    ): Boolean = case.requires.none { guard ->
        val key = guard.variable.lowercase()
        if (edge.clears.any { it.lowercase() == key } || edge.sets.keys.any { it.lowercase() == key }) {
            return@none false
        }
        asserted[key].orEmpty().any { before -> !canBothHold(before, guard) }
    }

    /** 두 비교가 **동시에 참일 수 있나.** 모르겠으면 설 수 있다고 본다 — 이 저장소의 규칙이다. */
    private fun canBothHold(a: Guard, b: Guard): Boolean {
        if (a.symbolic || b.symbolic) return true
        val numbers = listOfNotNull(a.value.toDoubleOrNull(), b.value.toDoubleOrNull())
        // **글자로 견주는 자리가 있다.** `==` 는 문자열 비교라 `5.0` 은 `5` 와 다르다 —
        // 후보를 만들 때 소수점을 붙이면 `==` 가 영영 안 맞는다.
        val probes =
            if (numbers.size == 2) numbers.flatMap { listOf(it - 1, it, it + 1) }.map(::plain)
            else listOf(a.value, b.value)
        return probes.any { a.holds(it) && b.holds(it) }
    }

    /**
     * 다음에 놓을 자리를 고르는 **점수.** 작을수록 먼저다.
     *
     * 세 가지를 자릿수로 갈라 둔다 — 앞의 것이 뒤의 것을 항상 이긴다. 그래야 "무엇 때문에 골랐나"를
     * 점수만 보고도 안다.
     *
     * ```
     * 비용        사이에 아무것도 안 드는 것 < 조작 < 사람이 지나가야 하는 것
     * 요구 개수    적게 요구하는 자리가 먼저 온다
     * 진행도      같은 값이면 덜 나아간 상태를 요구하는 쪽
     * ```
     */
    private fun score(case: Case, edge: Link): Int =
        cost(edge) * 10_000 + case.requires.size * 100 + progress(case)

    /** 정수면 소수점을 안 붙인다. `==` 가 글자로 견주기 때문이다. */
    private fun plain(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /**
     * 이 자리가 **얼마나 나아간 상태를 요구하나.** 진행을 요구하는 비교의 값들을 더한다.
     *
     * 크기를 재는 것이 아니라 **먼저 오는 것을 고르는 것**이 목적이라, 값이 없거나 숫자가 아니면
     * 0 이다 — 모르는 것을 뒤로 미룰 근거가 없다. 100 을 넘으면 잘라 앞의 두 자리(비용·요구 개수)를
     * 침범하지 않게 한다.
     */
    private fun progress(case: Case): Int = (
        case.requires
            .filter { it.operator == "==" || it.operator == ">=" || it.operator == ">" }
            .sumOf { it.value.toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0 } + case.reach
        ).coerceAtMost(99)

    /** 싼 것부터 놓는다 — 아무것도 안 넣는 것이 가장 싸고, 사람이 지나가야 하는 것이 가장 비싸다. */
    private fun cost(edge: Link): Int = when (edge.kind) {
        ScenarioFlowMatrix.Link.BESIDE -> 0
        ScenarioFlowMatrix.Link.BY_OPERATION -> 1
        ScenarioFlowMatrix.Link.BY_PLAY -> 2
        else -> 9
    }
}
