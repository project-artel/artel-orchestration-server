package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioResult

/**
 * 저장할 시나리오를 **처음부터 끝까지 굴려 본다**(ARTEL-528).
 *
 * 저작이 완벽할 수 없다는 것은 이 저장소가 이미 받아들인 전제다. 그래서 목표를 하나로 좁힌다 —
 * **실행이 도중에 막히는 시나리오만은 내놓지 않는다.** 케이스를 몇 개 담을지, 어떤 묶음이 나을지는
 * 요청이 정하고 모델이 판단하지만, "이 순서로는 두 번째 줄에서 멎는다"는 계산되는 사실이다.
 *
 * 실측(런 155)에서 시나리오 22개 중 **4개가 실행 불가였고 막힌 자리가 10군데**였다. 화면은 그 사실을
 * 한마디도 하지 않았다 — 사람이 스텝을 하나씩 짚어 가며 케이스 사전조건과 대조해야 알 수 있었다.
 *
 * ## 무엇을 굴리나
 *
 * 두 가지다. **어느 화면에 있나**와 **아는 변수값이 무엇인가.** 둘 다 케이스가 말해 준다 —
 * 사전조건이 요구하는 것, 확정하는 것, 그리고 [ScenarioStateReader.sceneAfter] 가 읽는 도착 화면.
 *
 * ## 스텝이 무엇을 바꿨는지는 **그 스텝의 근거**가 말해 준다
 *
 * 케이스를 보지 않는 스텝이 셋 있고, 믿을 수 있는 만큼이 서로 다르다:
 *
 * - `CAPABILITY` — 지도가 아는 조작이다. 화면을 넘는 것일 수도 있으므로 **화면도 값도 모른다**고
 *   둔다. 코드가 스스로 끼워 넣은 씬 이동 브리지가 여기 해당한다 — 그것을 막힘이라 부르면 이
 *   검사는 자기가 고친 자리를 지적하는 셈이 된다.
 * - `HUMAN` · `UNKNOWN` — 사람이 알려준 것이거나 미상이다. 마찬가지로 모른다.
 * - **근거 없음** — 무엇을 했는지 아무도 적지 않았다. 값은 버리되 **화면은 그대로 둔다.**
 *   화면을 옮겼다는 근거가 어디에도 없기 때문이다. 실측(런 155 시나리오 293)에서 사망 뒤에
 *   "전투 화면에서 …" 로 시작하는 준비 문장이 재진입을 **말만** 하고 있었고, 그것을 쳐 주면
 *   막힘이 통째로 사라진다.
 *
 * ## 안 잡는 쪽으로 틀린다
 *
 * 값을 모르면 그 뒤의 요구가 어긋나는지도 못 보므로 놓치는 자리가 생긴다. 반대로 하면(브리지가
 * 무엇을 만들었다고 **치면**) 있지도 않은 막힘을 만들어 낸다. 이 검사의 말이 한 번 틀리기 시작하면
 * 사용자는 전부 무시하게 되므로, 조용한 쪽으로 기운다.
 */
object ScenarioReachabilityCheck {

    /**
     * @property scenarioIndex `scenarios` 안의 자리.
     * @property stepIndex 그 시나리오 안의 스텝 자리(0부터).
     * @property reason 무엇이 막았는지. 사용자가 읽는 글이 아니라 알림을 만드는 쪽의 재료다.
     */
    data class Blocked(val scenarioIndex: Int, val stepIndex: Int, val reason: String)

    /**
     * 케이스 하나가 말하는 것 전부. DB 를 모르는 순수 값이라 이 검사를 테이블 없이 돌릴 수 있다.
     *
     * @property scene 이 케이스가 **시작하는** 화면.
     * @property moves 하고 나면 화면이 어떻게 되나.
     * @property requires 사전조건이 요구하는 비교들.
     * @property declares 사전조건이 확정하는 값(`==` 만).
     * @property leaves 하고 나면 확정되는 값(`state_after`).
     */
    data class CaseFact(
        val scene: String?,
        val moves: SceneMove,
        val requires: List<Guard>,
        val declares: Map<String, String>,
        val leaves: Map<String, String>,
    )

    fun analyze(scenarios: List<ScenarioResult>, facts: Map<Long, CaseFact>): List<Blocked> = buildList {
        scenarios.forEachIndexed { scenarioIndex, scenario ->
            addAll(walk(scenarioIndex, scenario.steps, facts))
        }
    }

    /** 시나리오 하나를 스텝 순서대로 굴린다. */
    private fun walk(
        scenarioIndex: Int,
        steps: List<ChatScenarioStep>,
        facts: Map<Long, CaseFact>,
    ): List<Blocked> = buildList {
        val caseIds = steps.map { it.caseId }
        var scene: String? = null
        val state = mutableMapOf<String, String>()

        steps.forEachIndexed { stepIndex, step ->
            val caseId = step.caseId
            if (caseId == null) {
                // 무엇을 했는지는 그 스텝의 근거가 말해 준다 — 자세한 이유는 파일 주석에 있다.
                state.clear()
                if (step.stepSource != null) scene = null
                return@forEachIndexed
            }
            val fact = facts[caseId] ?: return@forEachIndexed

            if (scene != null && fact.scene != null && fact.scene != scene) {
                add(Blocked(scenarioIndex, stepIndex, "${scene} 에서 ${fact.scene} 를 요구한다"))
                // 여기서부터는 그 케이스가 말하는 화면에 있다고 보고 이어 간다. 한 번 어긋난 것을
                // 계속 끌고 가면 뒤가 전부 같은 말로 덮여 어디가 진짜 시작인지 안 보인다.
                state.clear()
            }
            fact.requires.forEach { guard ->
                val have = state[guard.variable] ?: return@forEach
                if (!guard.holds(have)) {
                    add(
                        Blocked(
                            scenarioIndex, stepIndex,
                            "${guard.variable} 가 ${guard.operator} ${guard.value} 여야 하는데 $have 다",
                        )
                    )
                }
            }

            if (fact.scene != null) scene = fact.scene
            state += fact.declares
            state += fact.leaves

            // **검증 구간의 마지막에서만 화면이 넘어간다.** 잇달아 같은 케이스를 가리키는 스텝들은
            // 그 케이스 하나를 보는 구간이고, 판정은 구간의 끝에서 난다.
            if (caseIds.getOrNull(stepIndex + 1) == caseId) return@forEachIndexed

            // 구간이 끝났는데 뒤에 같은 케이스가 또 나온다 — 다른 케이스가 사이에 끼었다는 뜻이다.
            // 그 케이스가 화면을 옮겼다면 남은 반쪽은 실행될 수 없다(실측 런 155 의 시나리오 300·301).
            if (caseIds.drop(stepIndex + 1).any { it == caseId }) {
                add(Blocked(scenarioIndex, stepIndex, "이 케이스의 검증 구간에 다른 케이스가 끼었다"))
            }
            when (val moves = fact.moves) {
                is SceneMove.To -> {
                    scene = moves.scene
                    // 화면을 넘으면 알던 값을 버린다. 무엇이 유지되는지 명세가 말해 주지 않으므로
                    // 유지된다고 치는 것은 지어내는 것이다 — 경로 계산이 쓰는 규칙과 같다.
                    state.clear()
                }
                SceneMove.Unknown -> {
                    scene = null
                    state.clear()
                }
                SceneMove.Stays -> Unit
            }
        }
    }
}
