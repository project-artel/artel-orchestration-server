package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ScenarioResult

/**
 * 함께 담을 수 없는 케이스를 **묻지 않고 나눈다**(ARTEL-497).
 *
 * 앞서 이것은 되묻기였다. 실제로 써 보니 되묻기로는 끝나지 않았다(런 152):
 *
 * 1. "네, 나눠 주세요"가 모델에게 갔지만 **어느 쌍이 문제인지는 함께 가지 않는다** — 질문 문구만
 *    간다. 모델은 무엇을 나눠야 하는지 모른 채 다시 썼고, 같은 묶음이 그대로 돌아왔다.
 * 2. 조건이 그대로이므로 검수가 같은 쌍을 또 찾아냈고, **같은 질문이 다시 나갔다.** 사용자가 본
 *    것은 "나눠 주세요"를 눌러도 같은 질문이 계속 뜨는 화면이다.
 * 3. 한 번은 모델이 요청과 무관한 씬을 저작해 돌려주기까지 했다 — 답이 무엇을 뜻하는지 몰라서다.
 *
 * 나누는 일 자체는 **계산이다.** 어느 둘이 동시에 성립할 수 없는지는 [ScenarioSiblingCheck]가
 * 이미 알고, 그것을 갈래별로 모으는 것은 색칠 문제다. 계산되는 것을 모델에게 물어 왕복하는 동안
 * 값을 치르고 정확도를 잃었다. 그래서 여기서 나누고, 나눴다고 말한다.
 *
 * **순서는 지킨다.** 담긴 순서대로 훑어 들어갈 곳을 찾으므로, 나눠진 시나리오 안의 상대 순서는
 * 원래 순서 그대로다 — 순서 판정([ScenarioOrderCheck])은 그다음 자리에서 따로 돈다.
 */
object ScenarioConflictSplit {

    /**
     * @property scenarios 나눈 결과. 나눌 것이 없으면 받은 것 그대로다.
     * @property notes 나눈 시나리오의 `제목 → 조각 수`. 사용자에게 알릴 것이 여기서 나온다.
     * @property anchorOf `조각의 자리 → 첫 조각의 자리`(ARTEL-518). 나눠서 생긴 조각을 **원본 옆에**
     *   놓으려면 어느 것에서 갈라졌는지 알아야 한다. 첫 조각은 자기 자신을 가리키지 않는다 —
     *   여기 있는 것은 새로 생기는 조각뿐이다.
     */
    data class Outcome(
        val scenarios: List<ScenarioResult>,
        val notes: List<Pair<String, Int>> = emptyList(),
        val anchorOf: Map<Int, Int> = emptyMap(),
    )

    /**
     * @param exclusive 두 케이스가 동시에 성립할 수 없나. [ScenarioSiblingCheck]의 판정을 그대로 받는다.
     */
    fun apply(scenarios: List<ScenarioResult>, exclusive: (Long, Long) -> Boolean): Outcome {
        val out = mutableListOf<ScenarioResult>()
        val notes = mutableListOf<Pair<String, Int>>()
        val anchorOf = mutableMapOf<Int, Int>()

        for (scenario in scenarios) {
            val groups = group(scenario.steps.mapNotNull { it.caseId }.distinct(), exclusive)
            if (groups.size <= 1) {
                out += scenario
                continue
            }
            val first = out.size
            val parts = cut(scenario, groups)
            out += parts
            // 첫 조각을 뺀 나머지가 새로 생기는 것이고, 그것들이 원본 옆에 놓여야 한다.
            for (offset in 1 until parts.size) anchorOf[first + offset] = first
            notes += scenario.title to parts.size
        }
        return Outcome(out, notes, anchorOf)
    }

    /**
     * 케이스를 **서로 함께 볼 수 있는 무리**로 모은다.
     *
     * 담긴 순서대로 훑어 이미 있는 무리 중 아무와도 어긋나지 않는 첫 자리에 넣는다. 무리 안의
     * **모든** 원소와 견주므로 한 무리 안에 배타적인 둘이 남는 일은 없다.
     *
     * 가장 적은 수로 나누는 것을 목표로 하지 않는다 — 그건 어려운 문제이고, 여기서 필요한 것은
     * "실행할 수 있는 묶음"이지 "가장 적은 묶음"이 아니다.
     */
    private fun group(caseIds: List<Long>, exclusive: (Long, Long) -> Boolean): List<Set<Long>> {
        val groups = mutableListOf<MutableSet<Long>>()
        for (id in caseIds) {
            val home = groups.firstOrNull { group -> group.none { exclusive(it, id) } }
            if (home != null) home += id else groups += mutableSetOf(id)
        }
        return groups
    }

    /**
     * 시나리오를 무리 수만큼 자른다.
     *
     * **첫 조각이 원래 시나리오다.** `scenarioId`를 물려받아 그 자리를 덮어쓰고, 나머지는 새
     * 시나리오로 런 끝에 붙는다 — 그러지 않으면 수정 요청 한 번에 원본이 남고 사본이 생긴다.
     *
     * 케이스가 없는 스텝(모델이 적은 준비 동작, 미상 블록)은 **뒤따르는 검증 스텝**을 따라간다.
     * 준비는 다음에 볼 것을 위한 것이기 때문이다. 뒤에 검증이 없으면 앞의 것을 따라간다.
     */
    private fun cut(scenario: ScenarioResult, groups: List<Set<Long>>): List<ScenarioResult> {
        val ownerOf = buildMap {
            groups.forEachIndexed { index, group -> group.forEach { put(it, index) } }
        }
        val steps = scenario.steps
        // 스텝마다 어느 조각인지. 케이스가 없는 스텝은 뒤에서 앞으로 물려받는다.
        val owner = IntArray(steps.size) { -1 }
        steps.forEachIndexed { index, step -> owner[index] = step.caseId?.let { ownerOf[it] } ?: -1 }
        for (index in steps.indices.reversed()) {
            if (owner[index] == -1) owner[index] = owner.getOrElse(index + 1) { -1 }
        }
        var carried = 0
        for (index in steps.indices) {
            if (owner[index] == -1) owner[index] = carried else carried = owner[index]
        }

        return groups.indices.map { part ->
            ScenarioResult(
                scenarioId = if (part == 0) scenario.scenarioId else null,
                title = titleOf(scenario.title, part),
                description = scenario.description,
                steps = steps.filterIndexed { index, _ -> owner[index] == part },
            )
        }
    }

    /**
     * 조각의 제목.
     *
     * **접미를 겹쳐 붙이지 않는다**(ARTEL-518). 이미 나뉜 조각을 또 나누는 일이 실제로 일어나고
     * (런 155: 사용자가 지운 케이스를 "다시 담아줘" 하자 `…(5)` 를 다시 나눴다), 그때 그냥 붙이면
     * `…(5) (2)` 가 된다. 한 번 더 나누면 `(5) (2) (2)` 다. 번호를 점으로 이어 괄호는 하나로 둔다.
     */
    private fun titleOf(title: String, part: Int): String {
        if (part == 0) return title
        val trimmed = title.trim()
        val nested = PART_SUFFIX.find(trimmed)
        if (nested != null) {
            return trimmed.removeRange(nested.range).trimEnd() + " (${nested.groupValues[1]}.${part + 1})"
        }
        return "$trimmed (${part + 1})"
    }

    private val PART_SUFFIX = Regex("""\s*\((\d+(?:\.\d+)*)\)$""")
}
