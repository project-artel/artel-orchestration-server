package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.common.error.BadRequestException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testcase.dto.toTestCaseResponse
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.dto.ScenarioCaseItem
import kr.artel.orchestration.testscenario.dto.ScenarioCasesResponse
import kr.artel.orchestration.testscenario.dto.ScenarioStepDto
import kr.artel.orchestration.testscenario.entity.TestScenarioCaseEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioCaseRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * 시나리오 ↔ 케이스 조합(junction) 서비스(코루틴). FE 캔버스가 시나리오를 케이스로 구성/재정렬할 때 쓴다.
 *
 * 조회는 조합 행을 순서대로 읽고 케이스 내용을 리졸브한다(한 번의 findAllById로 N+1 회피).
 * 교체(setCases)는 전체 삭제 후 재삽입을 한 트랜잭션으로 처리한다(원자적, executeAndAwait). 접근은 시나리오의
 * 프로젝트 참여로 인가하며(비접근 → null → 404), 조합에 넣는 케이스는 같은 프로젝트 소속이어야 한다.
 */
@Service
class ScenarioCompositionService(
    private val accessService: TestScenarioAccessService,
    private val scenarioCaseRepository: TestScenarioCaseRepository,
    private val testCaseRepository: TestCaseRepository,
    private val transactionalOperator: TransactionalOperator,
    private val objectMapper: ObjectMapper,
) {
    /** 시나리오의 케이스 조합을 순서대로 조회(케이스 내용 포함). 비접근이면 null. */
    suspend fun getCases(testScenarioId: Long, userId: Long): ScenarioCasesResponse? {
        accessService.accessibleScenario(testScenarioId, userId) ?: return null
        return resolveCases(testScenarioId)
    }

    /** 조합 전체 교체. caseIds 순서 = position. 원자적. 반환은 리졸브된 새 조합(비접근 → null). */
    suspend fun setCases(testScenarioId: Long, userId: Long, caseIds: List<Long>): ScenarioCasesResponse? {
        val scenario = accessService.accessibleScenario(testScenarioId, userId) ?: return null
        validateCases(scenario.projectId, caseIds)
        transactionalOperator.executeAndAwait {
            // 자리(testCaseId, position)가 유지되는 스텝은 삭제 전 스냅샷해 캐리포워드한다(자리 사라지면 폐기).
            val preserved = scenarioCaseRepository.findByTestScenarioIdOrderByPosition(testScenarioId).toList()
                .associate { (it.testCaseId to it.position) to it.steps }
            scenarioCaseRepository.deleteByTestScenarioId(testScenarioId)
            val rows = caseIds.mapIndexed { index, caseId ->
                TestScenarioCaseEntity(
                    testScenarioId = testScenarioId,
                    testCaseId = caseId,
                    position = index,
                    steps = preserved[caseId to index] ?: Json.of("[]"),
                )
            }
            scenarioCaseRepository.saveAll(rows).toList()
        }
        return resolveCases(testScenarioId)
    }

    /** 넣으려는 케이스가 모두 존재하고 같은 프로젝트인지 확인. 아니면 400. */
    private suspend fun validateCases(projectId: Long, caseIds: List<Long>) {
        if (caseIds.isEmpty()) return
        val distinct = caseIds.toSet()
        val cases = testCaseRepository.findAllById(distinct).toList()
        when {
            cases.size != distinct.size ->
                throw BadRequestException("some test cases were not found")
            cases.any { it.projectId != projectId } ->
                throw BadRequestException("a test case belongs to another project")
        }
    }

    private suspend fun resolveCases(testScenarioId: Long): ScenarioCasesResponse {
        val rows = scenarioCaseRepository.findByTestScenarioIdOrderByPosition(testScenarioId).toList()
        if (rows.isEmpty()) return ScenarioCasesResponse(testScenarioId.toString(), emptyList())
        val caseById = testCaseRepository.findAllById(rows.map { it.testCaseId }).toList().associateBy { requireNotNull(it.id) }
        val items = rows.mapNotNull { row ->
            caseById[row.testCaseId]?.let {
                ScenarioCaseItem(row.position, it.toTestCaseResponse(), parseSteps(row.steps))
            }
        }
        return ScenarioCasesResponse(testScenarioId.toString(), items)
    }

    /**
     * QA 실행 시 Agent에 보낼 시나리오 JSON을 조립한다(ARTEL-254): payload(title/description)에
     * 조합을 `cases[]`(position 순서, 각 자리의 TC 내용 + 저작 Step)로 실어 준다. Agent가 이 cases를
     * 가공해 실행 스텝을 만든다. 접근 불가/미조합이면 payload에 빈 `cases`만 붙는다.
     */
    suspend fun agentScenario(testScenarioId: Long, userId: Long, payloadJson: String): JsonNode {
        val node = objectMapper.readTree(payloadJson) as? ObjectNode ?: objectMapper.createObjectNode()
        val items = getCases(testScenarioId, userId)?.items ?: emptyList()
        val cases = objectMapper.createArrayNode()
        for (item in items) {
            val c = objectMapper.createObjectNode()
            c.put("position", item.position)
            c.put("title", item.case.title)
            c.put("category", item.case.category)
            if (item.case.precondition != null) c.put("precondition", item.case.precondition) else c.putNull("precondition")
            c.put("expected", item.case.expected)
            c.set<JsonNode>("steps", objectMapper.valueToTree(item.steps))
            cases.add(c)
        }
        node.set<JsonNode>("cases", cases)
        return node
    }

    /** JSONB steps 컬럼을 타입화된 Step 목록으로 파싱. 빈 배열/공백은 빈 목록. */
    private fun parseSteps(steps: Json): List<ScenarioStepDto> {
        val text = steps.asString()
        if (text.isBlank() || text == "[]") return emptyList()
        return objectMapper.readValue(text, object : TypeReference<List<ScenarioStepDto>>() {})
    }
}
