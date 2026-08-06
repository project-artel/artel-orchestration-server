package kr.artel.orchestration.testrun.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.AgentAuthoredStep
import kr.artel.orchestration.testscenario.dto.CurrentScenario
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioResultCase
import kr.artel.orchestration.testscenario.dto.ScenarioStepDto
import kr.artel.orchestration.testscenario.repository.TestScenarioCaseRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service

/**
 * 런의 현재 시나리오 구성을 Agent 컨텍스트([CurrentScenario])로 읽어주는 서비스(ARTEL-206 Step 6).
 *
 * 세션 오픈·턴마다 이 목록을 Agent에 실어, Agent가 "어느 기존 시나리오를 수정할지"를 id로 지목하게 한다.
 * 조회는 런 조합 순서(position)를 따르며, 각 시나리오의 제목/설명(payload)과 케이스 링크를 함께 담는다.
 */
@Service
class RunScenarioReader(
    private val runScenarioRepository: TestRunScenarioRepository,
    private val scenarioRepository: TestScenarioRepository,
    private val scenarioCaseRepository: TestScenarioCaseRepository,
    private val objectMapper: ObjectMapper,
) {
    suspend fun currentScenarios(runId: Long): List<CurrentScenario> {
        val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        return links.mapNotNull { link ->
            val scenario = scenarioRepository.findById(link.testScenarioId) ?: return@mapNotNull null
            val draft = objectMapper.readValue(scenario.payload.asString(), ScenarioDraft::class.java)
            val cases = scenarioCaseRepository
                .findByTestScenarioIdOrderByPosition(link.testScenarioId)
                .toList()
                .map { row -> ScenarioResultCase(caseId = row.testCaseId, steps = parseSteps(row.steps)) }
            CurrentScenario(
                scenarioId = link.testScenarioId,
                title = draft.title,
                description = draft.description,
                cases = cases
            )
        }
    }

    /**
     * 저장된 steps(JSONB, ScenarioStepDto 배열)를 Agent에 보낼 형태(AuthoredStep)로 되돌린다 —
     * 그래야 Agent가 기존 저작 Step을 보고 편집할 수 있다. 저장 전용 필드(id/assert/observe)는 뺀다.
     */
    private fun parseSteps(steps: Json): List<AgentAuthoredStep> {
        val text = steps.asString()
        if (text.isBlank() || text == "[]") return emptyList()
        return objectMapper.readValue(text, object : TypeReference<List<ScenarioStepDto>>() {})
            .map { AgentAuthoredStep(kind = it.kind, intent = it.intent, hint = it.hint, input = it.input) }
    }
}
