package kr.artel.orchestration.testrun.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.CurrentScenario
import kr.artel.orchestration.testscenario.dto.toChatStep
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service

/**
 * 런의 현재 시나리오 구성을 Agent 컨텍스트([CurrentScenario])로 읽어주는 서비스(ARTEL-206 Step 6,
 * 재설계 2026-08-07).
 *
 * 세션 오픈·턴마다 이 목록을 Agent에 실어, Agent가 "어느 기존 시나리오를 수정할지"를 id로 지목하고
 * 그 시나리오의 **기존 steps까지 보고** 편집하게 한다. 조회는 런 조합 순서(position)를 따르며, 각
 * 시나리오의 본문(title/description/steps)을 그대로 담는다.
 */
@Service
class RunScenarioReader(
    private val runScenarioRepository: TestRunScenarioRepository,
    private val scenarioRepository: TestScenarioRepository,
    private val objectMapper: ObjectMapper,
) {
    /** 시나리오 하나의 지금 스텝(챗봇 계약 모양). 없으면 null. 저장 직전 비교용(ARTEL-1264). */
    suspend fun currentSteps(scenarioId: Long): List<ChatScenarioStep>? =
        scenarioRepository.findById(scenarioId)?.toDraft(objectMapper)?.steps?.map { it.toChatStep() }

    /** 시나리오 하나의 지금 제목 · 설명 · 실행 방식. 없으면 null. 스텝과 함께 저장 직전 비교에 쓴다(ARTEL-1264). */
    suspend fun currentHead(scenarioId: Long): ScenarioHead? {
        val scenario = scenarioRepository.findById(scenarioId) ?: return null
        val draft = scenario.toDraft(objectMapper)
        return ScenarioHead(draft.title, draft.description, scenario.executionMode)
    }

    /**
     * 이 결과가 실제로 덮을 시나리오. 번호가 있으면 그것이고, 없으면 이 런에서 제목이 같은 시나리오다 —
     * [kr.artel.orchestration.testscenario.service.ScenarioReconcileService.reconcile] 이 그렇게 갈아끼운다. 잠금 검사는
     * 저장할 대상과 같은 것을 봐야 한다. 새로 생길 것이면 null.
     */
    suspend fun targetIdOf(runId: Long, scenarioId: Long?, title: String): Long? {
        if (scenarioId != null && scenarioId > 0) return scenarioId
        val wanted = title.trim()
        return runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().firstNotNullOfOrNull { link ->
            scenarioRepository.findById(link.testScenarioId)?.takeIf { it.title.trim() == wanted }?.id
        }
    }

    suspend fun currentScenarios(runId: Long): List<CurrentScenario> {
        val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        return links.mapNotNull { link ->
            val scenario = scenarioRepository.findById(link.testScenarioId) ?: return@mapNotNull null
            val draft = scenario.toDraft(objectMapper)
            CurrentScenario(
                scenarioId = link.testScenarioId,
                title = draft.title,
                description = draft.description,
                // 저장 스텝을 챗봇 계약으로 투영한다. 기대 판정 라벨은 여기서 떨어지며, 그것이
                // 이 한 줄의 요점이다 — 에이전트에게 정답지를 보여주지 않는다(ARTEL-301).
                steps = draft.steps.map { it.toChatStep() },
                executionMode = scenario.executionMode,
            )
        }
    }
}

/** 시나리오의 스텝 밖 본문. 이것만 바뀌어도 AI 결과가 덮으면 사람이 고친 것이 사라진다(ARTEL-1264). */
data class ScenarioHead(
    val title: String,
    val description: String,
    val executionMode: kr.artel.orchestration.testscenario.dto.ScenarioExecutionMode?,
)
