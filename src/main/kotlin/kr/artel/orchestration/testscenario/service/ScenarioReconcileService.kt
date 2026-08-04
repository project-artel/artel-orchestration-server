package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.entity.TestScenarioCaseEntity
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioCaseRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * Agent가 낸 시나리오 결과를 런에 반영하는 공용 서비스(ARTEL-206 Step 5·6 Layer 2 — upsert).
 *
 * 저장 경로는 하나(이 서비스)지만 트리거는 둘이다:
 * - **자동 반영**: [TestScenarioAgentService]가 result 프레임을 받고 사용자의 autoApply가 켜져 있을 때.
 * - **수동 커밋**: 카드 검토 모드에서 사용자가 카드로 고른/편집한 결과를 커밋할 때
 *   ([kr.artel.orchestration.testrun.service.TestRunChatService.commitScenarios]).
 *
 * 항목별로 [ScenarioResult.scenarioId]로 분기한다:
 * - `null` → 새 시나리오 INSERT + 런 끝에 append.
 * - 값 있음 → 그 기존 시나리오 UPDATE(payload + 케이스 링크 통째 교체). 방어적으로 같은 프로젝트 소속일 때만.
 *
 * ⚠️ **안전규칙(협상 불가): [scenarios]가 비면 DB를 절대 건드리지 않는다** — 빈 배열은 질문·거절·무매치
 * 같은 정상 턴이지 "런을 비워라"가 아니다. 이 경로에는 어떤 삭제도 없다(케이스 링크 교체만 존재).
 */
@Service
class ScenarioReconcileService(
    private val scenarioRepository: TestScenarioRepository,
    private val scenarioCaseRepository: TestScenarioCaseRepository,
    private val runScenarioRepository: TestRunScenarioRepository,
    private val transactionalOperator: TransactionalOperator,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(ScenarioReconcileService::class.java)

    /**
     * [scenarios]를 [runId]/[projectId]에 upsert한다. 빈 배열이면 아무것도 하지 않는다.
     * @return 실제로 반영된 시나리오 수(추가 + 수정). 빈 입력이거나 전부 방어 스킵되면 0.
     */
    suspend fun reconcile(runId: Long, projectId: Long, scenarios: List<ScenarioResult>): Int {
        // SAFETY: 빈 배열은 정상 턴 — DB 무변경(삽입도 삭제도 없음).
        if (scenarios.isEmpty()) {
            logger.info("빈 scenarios — DB 무변경(정상 턴) [runId=$runId]")
            return 0
        }
        var applied = 0
        transactionalOperator.executeAndAwait {
            // 새 시나리오는 런의 현재 마지막 position 다음부터 붙인다. 비어 있으면 0부터.
            var runPosition = (runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
                .maxOfOrNull { it.position } ?: -1) + 1
            for (scenario in scenarios) {
                val payloadJson = Json.of(
                    objectMapper.writeValueAsString(
                        mapOf("title" to scenario.title, "description" to scenario.description)
                    )
                )
                val scenarioId = scenario.scenarioId
                if (scenarioId != null) {
                    // 수정: 기존 시나리오를 찾아 payload + 케이스 링크를 통째로 교체한다.
                    val existing = scenarioRepository.findById(scenarioId)
                    if (existing == null || existing.projectId != projectId) {
                        // 방어: 없는/남의 프로젝트 시나리오는 건드리지 않는다(엉뚱한 덮어쓰기 방지).
                        logger.warn("수정 대상 시나리오 무효 — 스킵 [runId=$runId, scenarioId=$scenarioId]")
                        continue
                    }
                    scenarioRepository.save(existing.copy(payload = payloadJson))
                    scenarioCaseRepository.deleteByTestScenarioId(scenarioId)
                    saveCaseLinks(scenarioId, scenario.caseIds)
                    // 런 링크는 그대로 둔다(수정은 위치를 바꾸지 않는다). 이미 이 런에 속한 시나리오를 겨냥한다.
                    applied++
                } else {
                    // 추가: 새 시나리오 INSERT + 케이스 링크 + 런 끝에 append.
                    val saved = scenarioRepository.save(
                        TestScenarioEntity(projectId = projectId, payload = payloadJson)
                    )
                    val newId = saved.id!!
                    saveCaseLinks(newId, scenario.caseIds)
                    runScenarioRepository.save(
                        TestRunScenarioEntity(testRunId = runId, testScenarioId = newId, position = runPosition)
                    )
                    runPosition++
                    applied++
                }
            }
        }
        logger.info("시나리오 반영 완료 [runId=$runId, applied=$applied/${scenarios.size}]")
        return applied
    }

    private suspend fun saveCaseLinks(scenarioId: Long, caseIds: List<Long>) {
        caseIds.forEachIndexed { index, caseId ->
            scenarioCaseRepository.save(
                TestScenarioCaseEntity(testScenarioId = scenarioId, testCaseId = caseId, position = index)
            )
        }
    }
}
