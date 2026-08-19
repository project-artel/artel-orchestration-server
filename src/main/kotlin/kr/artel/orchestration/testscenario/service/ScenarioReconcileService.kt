package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.toSet
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ReviewedCases
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.dto.toStoredStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * Agent가 낸 시나리오 결과를 런에 반영하는 공용 서비스(ARTEL-206 Step 5·6, 재설계 2026-08-07).
 *
 * 저장 경로는 하나(이 서비스)지만 트리거는 둘이다:
 * - **자동 반영**: [TestScenarioAgentService]가 result 프레임을 받고 사용자의 autoApply가 켜져 있을 때.
 * - **수동 커밋**: 카드 검토 모드에서 사용자가 카드로 고른/편집한 결과를 커밋할 때.
 *
 * **재설계**: 시나리오 본문 = **steps 리스트** 하나. 별도 케이스 조합 테이블(test_scenario_case)은
 * 폐기됐다 — 각 스텝은 행위 하나이며 검증 대상 TC를 `caseId`로 옵션 참조한다. 따라서 upsert는
 * 본문(title/description/steps) 통째 저장뿐이다.
 *
 * 항목별로 [ScenarioResult.scenarioId]로 분기한다:
 * - `null` → 새 시나리오 INSERT + 런 끝에 append.
 * - 값 있음 → 그 기존 시나리오 본문 UPDATE(방어적으로 같은 프로젝트 소속일 때만).
 *
 * ⚠️ **안전규칙(협상 불가): [scenarios]가 비면 DB를 절대 건드리지 않는다** — 빈 배열은 질문·거절·무매치
 * 같은 정상 턴이지 "런을 비워라"가 아니다. 이 경로에는 어떤 삭제도 없다.
 */
@Service
class ScenarioReconcileService(
    private val scenarioRepository: TestScenarioRepository,
    private val runScenarioRepository: TestRunScenarioRepository,
    private val transactionalOperator: TransactionalOperator,
    private val objectMapper: ObjectMapper,
    private val testCaseRepository: TestCaseRepository,
) {
    private val logger = LoggerFactory.getLogger(ScenarioReconcileService::class.java)

    /**
     * 반영 결과. 개수만으로는 **"0건 반영"과 "검수에서 막힘"이 구분되지 않는다** — 앞은 정상 턴이고
     * 뒤는 사용자에게 이유를 말해야 하는 실패다.
     *
     * @property applied 실제로 반영된 시나리오 수(추가 + 수정).
     * @property findings 검수 결과. 판정 필드가 없어 검사를 건너뛴 경우에도 빈 [Findings]가 들어온다.
     */
    data class ReconcileOutcome(
        val applied: Int,
        val findings: ScenarioCoverageAudit.Findings = ScenarioCoverageAudit.Findings(),
    ) {
        val rejected: Boolean get() = findings.rejected
    }

    /**
     * [scenarios]를 [runId]/[projectId]에 upsert한다. 빈 배열이면 아무것도 하지 않는다.
     *
     * [reviewed]가 있으면 **저장 전에** 검수한다(2단계). 통과하지 못하면 한 줄도 저장하지 않는다 —
     * 절반만 저장하면 "일부만 검증된 시나리오"가 남고, 그건 검사를 안 한 것보다 나쁘다(믿을 수 있어
     * 보인다). 규칙은 [ScenarioCoverageAudit]에 있다.
     */
    suspend fun reconcile(
        runId: Long,
        projectId: Long,
        scenarios: List<ScenarioResult>,
        reviewed: ReviewedCases? = null,
    ): ReconcileOutcome {
        // SAFETY: 빈 배열은 정상 턴 — DB 무변경(삽입도 삭제도 없음).
        if (scenarios.isEmpty()) {
            logger.info("빈 scenarios — DB 무변경(정상 턴) [runId=$runId]")
            return ReconcileOutcome(0)
        }

        // 검수는 저장 **전에** 끝난다. 통과하지 못한 결과는 한 줄도 들어가지 않는다 — 절반만 저장하면
        // "일부만 검증된 시나리오"가 남고, 그건 검사를 안 한 것보다 나쁘다(믿을 수 있어 보인다).
        val findings = ScenarioCoverageAudit.audit(
            projectCaseIds = testCaseRepository.findIdsByProjectId(projectId).toSet(),
            reviewed = reviewed,
            scenarios = scenarios,
        )
        if (findings.rejected) {
            logger.warn(
                "저작 검수 실패 — 저장하지 않음 [runId={}] {} · unreviewed={} missing={} ghost={}",
                runId, findings.summary(), findings.unreviewed, findings.missing, findings.ghost
            )
            return ReconcileOutcome(0, findings)
        }
        if (findings.excess.isNotEmpty()) {
            // 거부하지 않는 이유는 ScenarioCoverageAudit.Findings에 적었다. 남기는 이유는, 이 값이
            // 늘어나면 1패스 판정이 좁다는 뜻이라 프롬프트를 고칠 근거가 되기 때문이다.
            logger.info("판정 밖 케이스 담김(허용) [runId={}] excess={}", runId, findings.excess)
        }

        var applied = 0
        transactionalOperator.executeAndAwait {
            // 새 시나리오는 런의 현재 마지막 position 다음부터 붙인다. 비어 있으면 0부터.
            var runPosition = (runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
                .maxOfOrNull { it.position } ?: -1) + 1
            for (scenario in scenarios) {
                val scenarioId = scenario.scenarioId
                if (scenarioId != null) {
                    // 수정: 기존 시나리오 본문을 통째로 교체한다.
                    val existing = scenarioRepository.findById(scenarioId)
                    if (existing == null || existing.projectId != projectId) {
                        // 방어: 없는/남의 프로젝트 시나리오는 건드리지 않는다(엉뚱한 덮어쓰기 방지).
                        logger.warn("수정 대상 시나리오 무효 — 스킵 [runId=$runId, scenarioId=$scenarioId]")
                        continue
                    }
                    // 교체 전에 사람이 단 기대 판정 라벨을 건져 온다(ARTEL-301). 에이전트는 라벨을
                    // 본 적도 돌려준 적도 없으므로, 그냥 덮어쓰면 챗봇으로 시나리오를 한 번 고칠
                    // 때마다 그 시나리오의 정답지가 통째로 사라진다.
                    val previous = existing.toDraft(objectMapper).steps
                    scenarioRepository.save(
                        existing.withDraft(draftFor(scenario, previous), objectMapper)
                    )
                    // 런 링크는 그대로 둔다(수정은 위치를 바꾸지 않는다).
                    applied++
                } else {
                    // 추가: 새 시나리오 INSERT + 런 끝에 append. 새 시나리오에는 살릴 라벨이 없다.
                    val saved = scenarioRepository.save(
                        TestScenarioEntity(projectId = projectId)
                            .withDraft(draftFor(scenario, emptyList()), objectMapper)
                    )
                    runScenarioRepository.save(
                        TestRunScenarioEntity(testRunId = runId, testScenarioId = saved.id!!, position = runPosition)
                    )
                    runPosition++
                    applied++
                }
            }
        }
        logger.info("시나리오 반영 완료 [runId=$runId, applied=$applied/${scenarios.size}]")
        return ReconcileOutcome(applied, findings)
    }

    /**
     * 에이전트가 돌려준 시나리오를 저장 본문으로 만든다. [previous]는 교체되기 전의 스텝들이다.
     *
     * 에이전트 계약([ChatScenarioStep])에는 기대 판정 라벨이 없다 — 보여준 적이 없으니 돌려받을
     * 것도 없다(ARTEL-301). 그래서 [ExpectedLabelPolicy]를 통과시키지 않으면 챗봇 편집 한 번에 그
     * 시나리오의 정답지가 통째로 지워진다. 규칙 자체는 그쪽에 있다 — 저장 경로가 셋이라 각자
     * 적어 두면 그중 하나가 언젠가 빠진다.
     */
    private fun draftFor(scenario: ScenarioResult, previous: List<ScenarioStep>) = ScenarioDraft(
        title = scenario.title,
        description = scenario.description,
        steps = ExpectedLabelPolicy.carryOver(scenario.steps.map { it.toStoredStep() }, previous),
    )
}
