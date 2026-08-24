package kr.artel.orchestration.testscenario.service

/**
 * 같은 자리의 케이스들을 본다(ARTEL-466).
 *
 * 케이스 목록에는 **씬도 같고 스텝 문구도 글자 그대로 같은데 사전조건만 다른** 무리가 있다.
 * 실측(word-venture)에서 `StoryScene에 진입해 관찰한다` 가 셋이었고 각각 `i < …` ·
 * `StagePosition != 5` · `== 5` 였다. 한 갈래만 담고 나머지를 빠뜨리거나, 같이 담아도 될 것을
 * 시나리오 둘로 가르는 일이 실제로 나왔다(런 107).
 *
 * **여기서 세는 것은 배타성 하나뿐이다.** 두 사전조건이 동시에 성립할 수 있나 — 그건 계산되는
 * 사실이다. 그 위의 판단("그래서 합쳐야 하나")은 하지 않는다:
 *
 * - 배타적인 둘이 **한 시나리오**에 있으면 그 시나리오는 실행될 수 없다 → 막을 근거가 된다.
 * - 동시 성립하는 형제가 **다른 시나리오**로 갈렸으면 → 말만 한다. 일부러 나눴을 수 있다.
 * - 한 갈래만 담겼으면 → 말만 한다. 나머지 갈래를 안 볼 이유가 있을 수 있다.
 *
 * 뒤의 둘을 막지 않는 이유가 중요하다. 무엇을 어떤 묶음으로 검증할지는 사용자의 요청이 정하는
 * 것이고, 코드가 이기면 "133번만 보고 싶다"는 요청이 영영 통하지 않는다.
 */
object ScenarioSiblingCheck {

    /**
     * @property scene 사전조건이 말하는 씬. 없으면 `scene` 컬럼.
     * @property step 스텝 문구. 이 둘이 같으면 형제다.
     * @property guards 사전조건이 요구하는 비교들.
     * @property declared 사전조건이 **확정하는** 값(`==` 만). 배타성 판정에 쓴다.
     */
    data class CaseFact(
        val id: Long,
        val scene: String?,
        val step: String,
        val guards: List<Guard>,
        val declared: Map<String, String>,
    )

    /**
     * @property conflicting 한 시나리오 안에 있는 배타적인 케이스 쌍. **저장을 막는 근거다.**
     * @property splitApart 동시 성립하는데 시나리오가 갈린 형제 쌍. 알림.
     * @property untestedArms 담긴 갈래와 배타적이라 함께 볼 수 없는, 안 담긴 형제. 알림.
     */
    data class Findings(
        val conflicting: List<Pair<Long, Long>> = emptyList(),
        val splitApart: List<Pair<Long, Long>> = emptyList(),
        val untestedArms: List<Pair<Long, Long>> = emptyList(),
    )

    /**
     * @param facts 이 프로젝트의 케이스 전량. 안 담긴 형제를 말하려면 담긴 것만으로는 모자란다.
     * @param split 시나리오별로 담긴 케이스 id. 순서는 보지 않는다 — 순서는 [ScenarioOrderCheck] 몫이다.
     * @param covered **런 전체에서** 이미 담긴 케이스(ARTEL-516). 빠진 갈래를 셀 때 기준이 된다 —
     *   [split]은 이번 턴에 쓴 것뿐이라, 그것만 보면 다른 시나리오에 이미 있는 갈래를 "빠졌다"고
     *   센다. 실측(런 155)에서 미커버 0/66 인 화면에서 "이 갈래도 만들까요?"가 계속 나온 이유다.
     *   비워 두면 이번 턴만 본다(순수 함수 테스트용).
     */
    fun analyze(
        facts: List<CaseFact>,
        split: List<List<Long>>,
        covered: Set<Long> = split.flatten().toSet(),
    ): Findings {
        val byId = facts.associateBy { it.id }
        val used = split.flatten().toSet()

        val conflicting = buildList {
            for (scenario in split) {
                val members = scenario.distinct().mapNotNull { byId[it] }
                for (i in members.indices) {
                    for (j in i + 1 until members.size) {
                        if (exclusive(members[i], members[j])) add(members[i].id to members[j].id)
                    }
                }
            }
        }

        val siblings = facts.filter { it.id in used || it.scene != null }
            .groupBy { it.scene to it.step }
            .values
            .filter { it.size > 1 }

        val splitApart = buildList {
            for (group in siblings) {
                val here = group.filter { it.id in used }
                for (i in here.indices) {
                    for (j in i + 1 until here.size) {
                        val (a, b) = here[i] to here[j]
                        if (exclusive(a, b)) continue
                        if (scenarioOf(split, a.id) != scenarioOf(split, b.id)) add(a.id to b.id)
                    }
                }
            }
        }

        val untestedArms = buildList {
            for (group in siblings) {
                // 담긴 쪽은 **이번 턴**이다 — 방금 쓴 것에 대해서만 물을 것이 있다.
                val here = group.filter { it.id in used }
                // 빠진 쪽은 **런 전체**다. 다른 시나리오에 이미 있는 갈래를 빠졌다고 세면,
                // 전건을 담은 뒤에도 "이 갈래도 만들까요?"가 영영 멈추지 않는다.
                val missing = group.filter { it.id !in covered }
                for (taken in here) {
                    for (other in missing) {
                        if (exclusive(taken, other)) add(taken.id to other.id)
                    }
                }
            }
        }

        // 쌍은 **번호 순으로 고정한다.** 무리를 훑는 순서가 답을 바꾸면 같은 상황에서 다른 문구가
        // 나오고, 그건 사용자가 보기에 판정이 흔들리는 것과 구분되지 않는다.
        return Findings(
            conflicting.map(::ordered).distinct().sortedBy { it.first },
            splitApart.map(::ordered).distinct().sortedBy { it.first },
            // 이쪽은 방향이 뜻을 가진다 — 앞이 담긴 것, 뒤가 빠진 것이다.
            untestedArms.distinct().sortedBy { it.first },
        )
    }

    /**
     * 케이스를 **사람이 알아보는 말로** 부른다.
     *
     * 내부 번호를 사용자에게 내보내지 않는다 — 화면은 등장 순번만 보여주고, 에이전트 프롬프트에도
     * 같은 금지가 있다(`case_id` 는 구조적 필드로만 오간다). 알림·질문은 사용자가 읽는 글이므로
     * 같은 규칙을 따른다.
     *
     * 형제끼리는 씬과 스텝 문구가 같으므로 **사전조건이 유일하게 다른 부분**이다. 그것을 붙여야
     * 어느 갈래인지 갈린다.
     */
    fun describe(fact: CaseFact): String {
        val step = fact.step.trim()
        // 스텝 문구가 이미 씬 이름으로 시작하는 일이 흔하다("StoryScene에서 Space 입력을 한다").
        // 그대로 앞에 붙이면 "StoryScene · StoryScene에서…"가 된다.
        val where = when {
            step.isBlank() -> fact.scene.orEmpty()
            fact.scene == null || step.contains(fact.scene) -> step
            else -> "${fact.scene} · $step"
        }
        val guards = fact.guards.joinToString(", ") { "${it.variable} ${it.operator} ${it.value}" }
        return if (guards.isBlank()) where else "$where ($guards)"
    }

    private fun ordered(pair: Pair<Long, Long>) =
        if (pair.first <= pair.second) pair else pair.second to pair.first

    /**
     * 두 케이스가 **동시에 성립할 수 없나.**
     *
     * 두 갈래로 본다:
     *
     * 1. 한쪽이 확정한 값이 다른 쪽의 요구를 어긴다 — `StagePosition == 5` 와 `!= 5`.
     * 2. 같은 변수를 두고 **어느 값도 양쪽을 만족시키지 못한다** — `hp <= 0` 과 `hp > 0`(ARTEL-497).
     *
     * 2번이 없을 때 무엇을 놓쳤는지가 이 함수를 고친 이유다. word-venture `TurnBattleScene` 24건
     * 요청에서 사망(`Player.hp <= 0`) 2건과 생존(`hp > 0`) 8건이 한 시나리오에 함께 담겼는데,
     * 양쪽 다 부등식이라 확정값이 없어 **16쌍이 전부 통과했다.** 사망 케이스 뒤의 생존 전제
     * 케이스는 실행이 도달할 수 없다.
     *
     * 모르는 것은 충돌이라 부르지 않는다 — 비교할 수 없는 값(문자열 부등식 등)은 겹친다고 본다.
     */
    fun exclusive(a: CaseFact, b: CaseFact): Boolean =
        violates(a.declared, b.guards) || violates(b.declared, a.guards) || disjoint(a.guards, b.guards)

    private fun violates(declared: Map<String, String>, guards: List<Guard>): Boolean =
        guards.any { guard -> declared[guard.variable]?.let { !guard.holds(it) } ?: false }

    /** 같은 변수를 두고 겹치는 값이 없는 요구가 하나라도 있나. */
    private fun disjoint(a: List<Guard>, b: List<Guard>): Boolean =
        a.any { x -> b.any { y -> sameVariable(x, y) && !overlaps(x, y) } }

    /**
     * 두 가드가 **같은 값**을 말하나.
     *
     * 마지막 마디만으로는 모자란다. `magicTypeCards.Count` 와 `spellCards.Count` 는 둘 다 `Count`
     * 지만 서로 다른 값이고, 그것을 한 변수로 뭉개면 `== 1` 과 `== 2` 가 충돌로 잡힌다.
     * 적힌 경로가 서로의 꼬리일 때만 같은 것으로 본다 — `Player.hp` 와 `hp`(`Player.PlayerInt().hp`
     * 에서 읽힌 것)가 그 경우다.
     */
    private fun sameVariable(x: Guard, y: Guard): Boolean =
        x.variable == y.variable &&
            (x.path == y.path || x.path.endsWith(".${y.path}") || y.path.endsWith(".${x.path}"))

    /**
     * 두 요구를 **동시에 만족시키는 값이 있나.**
     *
     * 숫자로 읽히면 구간으로 바꿔 겹치는지 본다. 열린 경계를 살리려고 `>` 는 값보다 [EPSILON] 만큼
     * 큰 곳에서 시작한다 — 그러지 않으면 `<= 0` 과 `> 0` 이 0 에서 만나 겹친다고 나온다.
     *
     * 숫자가 아니면 `==`·`!=` 만 뜻이 있다. 문자열에 `>` 를 쓴 사전조건이 무슨 뜻인지 코드는
     * 모르고, 모르는 것은 겹친다고 본다.
     */
    private fun overlaps(x: Guard, y: Guard): Boolean {
        // 오른쪽이 다른 변수인 비교는 값을 모르는 것이다. 모르는 것은 겹친다고 본다.
        if (x.symbolic || y.symbolic) return true
        val xv = x.value.toDoubleOrNull()
        val yv = y.value.toDoubleOrNull()
        if (xv == null || yv == null) {
            if (x.operator == "==" && y.operator == "==") return x.value == y.value
            if (x.operator == "==" && y.operator == "!=") return x.value != y.value
            if (x.operator == "!=" && y.operator == "==") return x.value != y.value
            return true
        }
        // `!=` 는 점 하나만 뺀다. 그 점을 콕 집어 요구하는 상대가 아니면 언제나 겹칠 수 있다.
        if (x.operator == "!=" || y.operator == "!=") {
            val (excluded, other) = if (x.operator == "!=") xv to y else yv to x
            return !(other.operator == "==" && other.value.toDoubleOrNull() == excluded)
        }
        val (lowX, highX) = range(x.operator, xv)
        val (lowY, highY) = range(y.operator, yv)
        return maxOf(lowX, lowY) <= minOf(highX, highY)
    }

    /** 비교 하나를 구간으로. 모르는 연산자는 열린 구간이라 아무것도 배제하지 않는다. */
    private fun range(operator: String, value: Double): Pair<Double, Double> = when (operator) {
        "==" -> value to value
        ">" -> value + EPSILON to Double.POSITIVE_INFINITY
        ">=" -> value to Double.POSITIVE_INFINITY
        "<" -> Double.NEGATIVE_INFINITY to value - EPSILON
        "<=" -> Double.NEGATIVE_INFINITY to value
        else -> Double.NEGATIVE_INFINITY to Double.POSITIVE_INFINITY
    }

    /** 열린 경계를 닫힌 구간 계산으로 다루기 위한 최소 간격. 게임 상태값은 대개 정수다. */
    private const val EPSILON = 1e-9

    private fun scenarioOf(split: List<List<Long>>, caseId: Long): Int =
        split.indexOfFirst { caseId in it }
}
