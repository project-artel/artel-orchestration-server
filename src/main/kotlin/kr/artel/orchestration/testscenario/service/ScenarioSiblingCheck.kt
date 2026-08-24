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
     */
    fun analyze(facts: List<CaseFact>, split: List<List<Long>>): Findings {
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
                val here = group.filter { it.id in used }
                val missing = group.filter { it.id !in used }
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
     * 한쪽이 확정한 값이 다른 쪽의 요구를 어기면 배타적이다 — `StagePosition == 5` 와 `!= 5` 가
     * 그것이다. 확정된 값이 없거나 서로 다른 변수만 말하면 배타적이라고 하지 않는다. 모르는 것을
     * 충돌이라 부르면 멀쩡한 시나리오가 막힌다.
     */
    private fun exclusive(a: CaseFact, b: CaseFact): Boolean =
        violates(a.declared, b.guards) || violates(b.declared, a.guards)

    private fun violates(declared: Map<String, String>, guards: List<Guard>): Boolean =
        guards.any { guard -> declared[guard.variable]?.let { !guard.holds(it) } ?: false }

    private fun scenarioOf(split: List<List<Long>>, caseId: Long): Int =
        split.indexOfFirst { caseId in it }
}
