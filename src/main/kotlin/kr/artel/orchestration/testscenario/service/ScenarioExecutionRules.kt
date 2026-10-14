package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioExecutionMode
import kr.artel.orchestration.testscenario.dto.ScenarioStepKind

/** FREE 시나리오 초안이 규칙을 어겼다. 400, code `invalid_free_scenario`. */
class InvalidFreeScenarioException(message: String) :
    BadRequestException(message, code = "invalid_free_scenario")

/**
 * [ScenarioExecutionMode.FREE] 초안의 저장 규칙(ARTEL-1028). 자동저장과 승인, 작성 챗봇의 저장(reconcile)이
 * 같은 규칙을 지난다.
 *
 * - 모든 step 에 `case_id` 가 있다.
 * - 같은 `case_id` 가 두 번 나오지 않는다.
 * - `step_kind` 는 null 이거나 ACTION 이다(GAP·OPENING 은 ORDERED 의 알림 줄이다).
 *
 * step 이 하나도 없는 초안은 만드는 중이라 통과한다. ORDERED 초안은 검사하지 않는다.
 */
object ScenarioExecutionRules {
    fun validate(draft: ScenarioDraft) {
        if (draft.executionMode != ScenarioExecutionMode.FREE) return
        val seen = mutableSetOf<Long>()
        draft.steps.forEachIndexed { index, step ->
            val number = index + 1
            val caseId = step.caseId
                ?: throw InvalidFreeScenarioException("FREE 시나리오의 ${number}번째 step 에 case_id 가 없습니다.")
            if (!seen.add(caseId)) {
                throw InvalidFreeScenarioException("FREE 시나리오에 case_id $caseId 가 두 번 들어 있습니다.")
            }
            if (step.stepKind != null && step.stepKind != ScenarioStepKind.ACTION) {
                throw InvalidFreeScenarioException(
                    "FREE 시나리오의 ${number}번째 step 은 step_kind 가 ACTION 이어야 합니다.",
                )
            }
        }
    }
}
