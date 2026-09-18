package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource

/**
 * 모델이 쓴 다리 스텝의 **근거를 코드가 되찾는다**.
 *
 * 계측(2026-09-08, 30판)의 제출 반려 41건이 전원 같은 사유였다 — "근거 없는 스텝".
 * 스텝 계약은 다리에 `CAPABILITY` + capability id 를 요구하는데, 그 id 를 알 수 있는
 * 자료가 모델에게 하나도 안 간다(케이스 목록·도달표·흐름 어디에도 없다). 즉 모델이 적는
 * id 는 지어낸 수일 수밖에 없고, 검수가 그것을 잡아 되돌리면 모델은 이유를 알아도 고칠
 * 재료가 없다 — 같은 자리에서 9연속(런 31)·10연속(런 40) 반려가 그렇게 났다.
 *
 * 그래서 모델에게 id 를 시키지 않는다. 다리의 양옆 CASE 스텝이 구조(case_id → 씬)를 들고
 * 있으므로, **어느 씬 사이를 잇는 다리인지는 코드가 안다.** 그 사이의 간선(`scene_edge`)이
 * 후보이고, word-venture 실측으로 화면 짝당 1~2개다. 문장을 이해하는 것이 아니라 위치로
 * 좁히고, 여럿이면 조작 기계값(`Return` 같은, 우리가 프롬프트에 실어 준 그 표기)의 포함
 * 여부로 가른다.
 *
 * **그래도 못 가르면 고르지 않는다.** 후보 중 하나를 코드가 찍으려면 우선순위 잣대를
 * 만들어야 하고, 그 잣대는 게임 하나에 맞춰진다 — 흐름 점수식이 밟았던 그 길이다. 대신
 * `UNKNOWN` 으로 남기고 후보를 [ChatScenarioStep.stepUnknownReason] 에 적어 사용자가
 * 고르게 한다. 반려 대신 흡수라, 헛바퀴 자체가 사라진다.
 */
object ScenarioBridgeGrounding {

    /** 지도 간선 하나 — 시킬 수 있는 것만 후보가 된다([by] 가 null 이면 저절로 = 지시 불가). */
    data class Edge(
        val fromScene: String,
        val toScene: String,
        val by: String?,
        val capabilityId: Long,
    )

    data class Grounded(
        val scenarios: List<ScenarioResult>,
        /** 무엇을 어떻게 되찾았는지 — 트레이스가 싣는다. */
        val notes: List<String>,
    )

    /**
     * @param sceneOf 케이스가 서 있는 씬.
     * @param arrivesAt 케이스를 실행한 뒤 서 있는 씬(화면을 넘기는 케이스). 없으면 [sceneOf].
     * @param live 실재하는 capability id 들 — 여기 있는 id 를 단 스텝은 건드리지 않는다.
     */
    fun apply(
        scenarios: List<ScenarioResult>,
        sceneOf: (Long) -> String?,
        arrivesAt: (Long) -> String?,
        edges: List<Edge>,
        live: Set<Long>,
    ): Grounded {
        val notes = mutableListOf<String>()
        val grounded = scenarios.map { scenario ->
            scenario.copy(
                steps = scenario.steps.mapIndexed { index, step ->
                    if (!needsGrounding(step, live)) step
                    else ground(scenario, index, step, sceneOf, arrivesAt, edges, notes)
                }
            )
        }
        return Grounded(grounded, notes)
    }

    /**
     * 다리인데 근거가 없거나 지어낸 것. `CASE` 스텝과 이미 실재하는 id, 그리고 근거 검사를
     * 건너뛰는 구버전(`stepSource == null`)은 건드리지 않는다 — 이 패스는 반려를 흡수하는
     * 것이지 검사를 넓히는 것이 아니다.
     */
    private fun needsGrounding(step: ChatScenarioStep, live: Set<Long>): Boolean =
        step.caseId == null &&
            step.stepSource == ScenarioStepSource.CAPABILITY &&
            (step.stepSourceCapabilityId == null || step.stepSourceCapabilityId !in live)

    private fun ground(
        scenario: ScenarioResult,
        index: Int,
        step: ChatScenarioStep,
        sceneOf: (Long) -> String?,
        arrivesAt: (Long) -> String?,
        edges: List<Edge>,
        notes: MutableList<String>,
    ): ChatScenarioStep {
        // 다리의 양옆 — 앞은 가장 가까운 앞선 CASE 스텝이 **도착한** 씬, 뒤는 가장 가까운
        // 다음 CASE 스텝이 서 있는 씬이다.
        val before = scenario.steps.take(index).lastOrNull { it.caseId != null }?.caseId
            ?.let { arrivesAt(it) ?: sceneOf(it) }
        val after = scenario.steps.drop(index + 1).firstOrNull { it.caseId != null }?.caseId
            ?.let { sceneOf(it) }

        fun unknown(reason: String): ChatScenarioStep {
            notes += "${scenario.title} ${index + 1}번째 스텝: $reason"
            return step.copy(
                stepSource = ScenarioStepSource.UNKNOWN,
                stepSourceCapabilityId = null,
                stepUnknownReason = reason,
            )
        }

        if (before == null || after == null) {
            return unknown("다리의 양옆 씬을 몰라 근거를 못 찾았습니다 — 어떤 조작인지 알려주세요.")
        }
        if (before == after) {
            return unknown("$before 안에서의 조작이라 화면 간선으로는 근거를 못 찾았습니다 — 어떤 조작인지 알려주세요.")
        }

        val candidates = edges.filter { it.fromScene == before && it.toScene == after }
        val instructable = candidates.filter { it.by != null }
        if (candidates.isNotEmpty() && instructable.isEmpty()) {
            return unknown("$before → $after 는 게임이 저절로 넘기는 자리라 스텝으로 시킬 수 없습니다.")
        }
        if (instructable.isEmpty()) {
            return unknown("$before → $after 를 잇는 간선을 지도가 모릅니다 — 어떻게 가는지 알려주세요.")
        }

        val matched =
            if (instructable.size == 1) instructable
            // 문장 이해가 아니다 — 우리가 프롬프트에 실어 준 조작 표기가 문장에 그대로
            // 들어있는지만 본다(런 16 실측: 모델은 그 표기로 말한다).
            else instructable.filter { step.action.contains(it.by!!, ignoreCase = true) }

        if (matched.size == 1) {
            val edge = matched.single()
            notes += "${scenario.title} ${index + 1}번째 스텝: $before → $after [${edge.by}] = 기능 ${edge.capabilityId}"
            return step.copy(
                stepSource = ScenarioStepSource.CAPABILITY,
                stepSourceCapabilityId = edge.capabilityId,
                stepUnknownReason = null,
            )
        }
        // 여럿인데 문장이 안 가른다 — 고르면 잣대가 생기고 잣대는 게임에 맞춰진다. 묻는다.
        return unknown(
            "$before → $after 로 가는 길이 여럿입니다 — 후보: " +
                instructable.joinToString(" / ") { it.by!! } + ". 어느 길인지 알려주세요."
        )
    }
}
