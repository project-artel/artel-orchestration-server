package kr.artel.orchestration.testrun.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.CurrentScenario
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service

/**
 * 런의 현재 시나리오 구성을 Agent 컨텍스트([CurrentScenario])로 읽어주는 서비스(ARTEL-206 Step 6,
 * 재설계 2026-08-07).
 *
 * 세션 오픈·턴마다 이 목록을 Agent에 실어, Agent가 "어느 기존 시나리오를 수정할지"를 id로 지목하고
 * 그 시나리오의 **기존 steps까지 보고** 편집하게 한다. 조회는 런 조합 순서(position)를 따르며, 각
 * 시나리오의 본문(payload = title/description/steps)을 그대로 담는다.
 */
@Service
class RunScenarioReader(
    private val runScenarioRepository: TestRunScenarioRepository,
    private val scenarioRepository: TestScenarioRepository,
    private val objectMapper: ObjectMapper,
) {
    suspend fun currentScenarios(runId: Long): List<CurrentScenario> {
        val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        return links.mapNotNull { link ->
            val scenario = scenarioRepository.findById(link.testScenarioId) ?: return@mapNotNull null
            val draft = objectMapper.readValue(scenario.payload.asString(), ScenarioDraft::class.java)
            CurrentScenario(
                scenarioId = link.testScenarioId,
                title = draft.title,
                description = draft.description,
                steps = draft.steps,
            )
        }
    }
}
