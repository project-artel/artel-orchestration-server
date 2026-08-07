package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import org.springframework.stereotype.Service

/**
 * QA 실행 시 Agent에 넘길 시나리오 JSON을 조립하는 서비스(재설계 2026-08-07).
 *
 * 시나리오 본문은 payload(JSONB)의 **steps 리스트**다(별도 케이스 조합 테이블 폐기). 각 스텝은 행위
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
    private val objectMapper: ObjectMapper,
) {
    /**
     * QA 실행용 시나리오 JSON을 만든다. [payloadJson]은 test_scenario.payload(ScenarioDraft) 문자열이며,
     * 접근 검증은 호출부([kr.artel.orchestration.qa.service.QaTryService])가 이미 마친 뒤 넘긴다.
     */
    suspend fun agentScenario(testScenarioId: Long, userId: Long, payloadJson: String): JsonNode {
        val draft = objectMapper.readValue(payloadJson, ScenarioDraft::class.java)
        val caseIds = draft.steps.mapNotNull { it.caseId }.toSet()
        val caseById = if (caseIds.isEmpty()) {
            emptyMap()
        } else {
            testCaseRepository.findAllById(caseIds).toList().associateBy { requireNotNull(it.id) }
        }

        val node = objectMapper.createObjectNode()
        node.put("title", draft.title)
        node.put("description", draft.description)
        val stepsArr = objectMapper.createArrayNode()
        for (step in draft.steps) {
            val s = objectMapper.createObjectNode()
            s.put("action", step.action)
            if (step.caseId != null) s.put("case_id", step.caseId) else s.putNull("case_id")
            if (step.hint != null) s.put("hint", step.hint) else s.putNull("hint")
            if (step.input != null) s.put("input", step.input) else s.putNull("input")
            val tc = step.caseId?.let { caseById[it] }
            if (tc != null) {
                // TC 임베드 = TC 스펙(CSV) 미러: scene/precondition/test_step/expected(+예약 basis).
                // 현행 TestCase 필드를 그 이름으로 투영한다(category=씬, title=테스트 스텝). TC가 스펙으로
                // 재구조화되면 이 매핑만 바꾸면 되고 계약 필드명은 그대로다.
                val c = objectMapper.createObjectNode()
                c.put("id", requireNotNull(tc.id))
                c.put("scene", tc.category)
                if (tc.precondition != null) c.put("precondition", tc.precondition) else c.putNull("precondition")
                c.put("test_step", tc.title)
                c.put("expected", tc.expected)
                s.set<JsonNode>("case", c)
            } else {
                s.putNull("case")
            }
            stepsArr.add(s)
        }
        node.set<JsonNode>("steps", stepsArr)
        return node
    }
}
