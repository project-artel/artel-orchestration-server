package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioResult

/**
 * 한 시나리오 안에서 **글자까지 같은 스텝**이 서로 다른 케이스를 볼 때, 무엇이 다른지 붙인다.
 *
 * 실측(런 152, TS 247)에서 2번과 7번 스텝이 이랬다:
 *
 * ```
 * 2. TurnBattleScene의 SpellObj에서 OnTriggerEnter2D 충돌이 발생한다.   ← 케이스 1297
 * 7. TurnBattleScene의 SpellObj에서 OnTriggerEnter2D 충돌이 발생한다.   ← 케이스 1301
 * ```
 *
 * 다른 케이스이고 다른 상태를 요구하는데 **화면에서는 같은 줄이 두 번 있는 것으로 보인다.** 실행
 * 하는 사람은 중복인지 아닌지 알 수 없고, 중복이라고 판단하면 하나를 건너뛴다.
 *
 * 형제 케이스끼리 다른 것은 사전조건뿐이므로([ScenarioSiblingCheck.describe] 도 같은 것을 쓴다),
 * **다른 부분만** 뒤에 붙인다. 공통 조건까지 붙이면 두 줄이 다시 길고 비슷해져 가르는 데 도움이
 * 되지 않는다.
 *
 * 붙이지 않는 경우가 셋이다:
 *
 * - 같은 문구가 **같은 케이스**를 가리키는 경우. "하기"와 "확인하기"가 한 케이스의 검증 구간으로
 *   나뉜 것이라 가를 것이 없다.
 * - 가를 사전조건이 없는 경우. 지어낼 수는 없다.
 * - 이미 그 라벨로 끝나는 경우. 모델은 지난 턴의 시나리오를 그대로 돌려받으므로, 멱등하지 않으면
 *   턴마다 `(hp > 0) (hp > 0)` 으로 늘어난다.
 */
object ScenarioSiblingLabel {

    /** @param guardsOf 케이스가 요구하는 비교들. 모르는 케이스는 빈 목록이다. */
    fun apply(scenarios: List<ScenarioResult>, guardsOf: (Long) -> List<Guard>): List<ScenarioResult> =
        scenarios.map { scenario ->
            val labels = labels(scenario.steps, guardsOf)
            if (labels.isEmpty()) return@map scenario
            scenario.copy(
                steps = scenario.steps.mapIndexed { index, step ->
                    val label = labels[index] ?: return@mapIndexed step
                    val action = step.action.trimEnd()
                    if (action.endsWith(label)) step else step.copy(action = "$action $label")
                }
            )
        }

    /** 스텝 자리 → 붙일 라벨. 붙일 것이 없는 자리는 없다. */
    private fun labels(
        steps: List<ChatScenarioStep>,
        guardsOf: (Long) -> List<Guard>,
    ): Map<Int, String> = buildMap {
        steps.withIndex()
            .filter { it.value.caseId != null }
            .groupBy { it.value.action.trim() }
            .values
            .filter { group -> group.mapNotNull { it.value.caseId }.distinct().size > 1 }
            .forEach { group ->
                // 값을 모르는 비교(오른쪽이 다른 변수)는 라벨에 쓰지 않는다 — 읽는 사람에게
                // `tag == SpellObj.target.gameObject.tag` 는 무엇이 다른지 말해 주지 않는다.
                val required = group.associate { (index, step) ->
                    index to guardsOf(step.caseId!!).filterNot { it.symbolic }
                }
                val shared = required.values.reduce { common, next -> common.filter { it in next } }
                required.forEach { (index, guards) ->
                    val distinguishing = guards.filterNot { it in shared }
                    if (distinguishing.isNotEmpty()) put(index, phrase(distinguishing))
                }
            }
    }

    private fun phrase(guards: List<Guard>): String =
        guards.joinToString(", ", prefix = "(", postfix = ")") {
            "${it.variable} ${it.operator} ${it.value}"
        }
}
