package kr.artel.orchestration.testscenario.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.dto.AgentCase
import kr.artel.orchestration.testscenario.dto.AgentScenario
import kr.artel.orchestration.testscenario.dto.AgentStep
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import org.springframework.stereotype.Service

/**
 * QA 실행 시 Agent에 넘길 시나리오 JSON을 조립하는 서비스(재설계 2026-08-07).
 *
 * 시나리오 본문은 **steps 리스트**다(별도 케이스 조합 테이블 폐기). 각 스텝은 행위
 * 하나이며 검증 대상 TC를 `caseId`로 옵션 참조한다. 실행 전달 시 caseId가 있는 스텝엔 그 TC 내용(`case`
 * = title/category/precondition/expected)을 리졸브해 동봉한다.
 *
 * **Agent 실행 계약(넘기는 구조)**: `{ title, description, steps: [ { action, case_id, hint, input,
 * case: {id, scene, precondition, test_step, expected}|null } ] }`. `case`는 TC 스펙(CSV: 씬/사전조건/
 * 테스트 스텝/기대 결과/근거)을 미러링한다(근거=basis는 TC 확정 시 추가). **연속된 동일 `case_id` = 한
 * TC의 검증 구간**이며 다음처럼 판정한다: 구간 진입 시 `case.precondition` 충족 검증 → 구간의 `action`
 * 들 실행 → 구간 끝에서 `case.expected`로 판정. `case_id`가 null인 스텝은 그냥 수행(판정 없음).
 */
@Service
class ScenarioCompositionService(
    private val testCaseRepository: TestCaseRepository,
) {
    /**
     * QA 실행용 시나리오 계약([AgentScenario])을 만든다. 직렬화는 Jackson에 맡긴다(예전엔 JsonNode를 손으로
     * 조립했으나 타입 DTO로 전환 — 팀 리뷰 피드백). [draft]는 시나리오 본문이며, 접근 검증은 호출부
     * ([kr.artel.orchestration.qa.service.QaTryService])가 이미 마친 뒤 넘긴다.
     */
    suspend fun agentScenario(draft: ScenarioDraft): AgentScenario {
        val caseIds = draft.steps.mapNotNull { it.caseId }.toSet()
        val caseById = if (caseIds.isEmpty()) {
            emptyMap()
        } else {
            testCaseRepository.findAllById(caseIds).toList().associateBy { requireNotNull(it.id) }
        }

        val steps = draft.steps.map { step ->
            // caseId가 있는 스텝엔 그 TC 스펙(CSV: 씬/사전조건/테스트 스텝/기대 결과)을 리졸브해 동봉.
            // 현행 TestCase 필드를 계약 이름으로 투영(category=씬, title=테스트 스텝). TC가 스펙으로
            // 재구조화되면 이 매핑만 바꾸면 되고 계약 필드명(@JsonProperty)은 그대로다.
            val tc = step.caseId?.let { caseById[it] }
            AgentStep(
                action = step.action,
                caseId = step.caseId,
                hint = step.hint,
                input = step.input,
                case = tc?.let {
                    AgentCase(
                        id = requireNotNull(it.id),
                        scene = it.scene,
                        precondition = it.precondition,
                        testStep = it.step,
                        expected = it.expectedValue,
                    )
                },
            )
        }
        return AgentScenario(title = draft.title, description = draft.description, steps = steps)
    }
}
