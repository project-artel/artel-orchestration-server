package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.toSet
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunMessageRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ReviewedCases
import kr.artel.orchestration.testscenario.dto.ScenarioQuestion
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource
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
    private val pathService: ScenarioPathService,
    private val caseFactService: ScenarioCaseFactService,
    private val runMessageRepository: TestRunMessageRepository,
) {
    private val logger = LoggerFactory.getLogger(ScenarioReconcileService::class.java)

    /**
     * 반영 결과. 개수만으로는 **"0건 반영"과 "검수에서 막힘"이 구분되지 않는다** — 앞은 정상 턴이고
     * 뒤는 사용자에게 이유를 말해야 하는 실패다.
     *
     * @property applied 실제로 반영된 시나리오 수(추가 + 수정).
     * @property findings 검수 결과. 판정 필드가 없어 검사를 건너뛴 경우에도 빈 [Findings]가 들어온다.
     * @property notices 미상으로 남은 구간을 사용자에게 알릴 문장들(ARTEL-468). 저장된 턴에만 채워진다.
     * @property question 되묻는 한 가지(ARTEL-487). **저장을 막지 않는다** — 답하지 않아도 결과물은
     *   남는다. 없으면 물을 것이 없는 턴이다.
     */
    data class ReconcileOutcome(
        val applied: Int,
        val findings: ScenarioCoverageAudit.Findings = ScenarioCoverageAudit.Findings(),
        val notices: List<String> = emptyList(),
        val question: ScenarioQuestion? = null,
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
        appUserId: Long,
        scenarios: List<ScenarioResult>,
        reviewed: ReviewedCases? = null,
    ): ReconcileOutcome {
        // SAFETY: 빈 배열은 정상 턴 — DB 무변경(삽입도 삭제도 없음).
        if (scenarios.isEmpty()) {
            logger.info("빈 scenarios — DB 무변경(정상 턴) [runId=$runId]")
            return ReconcileOutcome(0)
        }

        // 같은 자리의 케이스들을 본다(ARTEL-466). 배타적인 둘이 한 시나리오에 있으면 막고,
        // 나누고 합치는 문제는 말만 한다 — 그건 요청이 정하는 것이지 코드가 정할 일이 아니다.
        val facts = caseFacts(projectId)
        // 케이스를 사람 말로 부르는 함수. 내부 번호는 사용자가 읽는 글에 넣지 않는다.
        val byId = facts.associateBy { it.id }
        val describe: (Long) -> String = { id -> byId[id]?.let(ScenarioSiblingCheck::describe).orEmpty() }
        val split = repairedSplit(scenarios)
        val siblings = ScenarioSiblingCheck.analyze(facts, split)

        // 경로 조회는 한 번만 한다. 메우는 쪽과 검수하는 쪽이 같은 질문을 하기 때문에, 따로 물으면
        // 한 턴에 같은 케이스 쌍을 두 번씩 조회하게 된다.
        val routes = PathLookup(projectId, appUserId)

        // 검수보다 **먼저** 메운다. 순서가 반대면 코드가 고칠 수 있는 것 때문에 저장이 막히고,
        // 그러면 에이전트에게 다시 쓰라고 시키게 된다 — 그 방법이 안 통한다는 것이 이 작업의 전제다.
        val (bridged, notices, blocked) = repairByInsertion(routes, scenarios, describe)
        val repaired = withCaseOperations(projectId, appUserId, bridged)

        // 검수는 저장 **전에** 끝난다. 통과하지 못한 결과는 한 줄도 들어가지 않는다 — 절반만 저장하면
        // "일부만 검증된 시나리오"가 남고, 그건 검사를 안 한 것보다 나쁘다(믿을 수 있어 보인다).
        val findings = ScenarioCoverageAudit.audit(
            projectCaseIds = testCaseRepository.findIdsByProjectId(projectId).toSet(),
            reviewed = reviewed,
            scenarios = repaired,
        ).let {
            it.copy(
                ungrounded = it.ungrounded + ghostCapabilities(projectId, appUserId, repaired),
                falseUnknowns = falseUnknowns(routes, repaired),
                conflicting = siblings.conflicting,
            )
        }
        if (findings.rejected) {
            logger.warn(
                "저작 검수 실패 — 저장하지 않음 [runId={}] {} · unreviewed={} missing={} ghost={} " +
                    "ungrounded={} falseUnknown={}",
                runId, findings.summary(), findings.unreviewed, findings.missing, findings.ghost,
                findings.ungrounded.size, findings.falseUnknowns.size
            )
            return ReconcileOutcome(0, findings)
        }
        if (findings.excess.isNotEmpty()) {
            // 거부하지 않는 이유는 ScenarioCoverageAudit.Findings에 적었다. 남기는 이유는, 이 값이
            // 늘어나면 1패스 판정이 좁다는 뜻이라 프롬프트를 고칠 근거가 되기 때문이다.
            logger.info("판정 밖 케이스 담김(허용) [runId={}] excess={}", runId, findings.excess)
        }

        val scope = partialScenes(facts, split)
        // **물은 것은 통보로 되풀이하지 않는다.** 같은 말이 두 줄로 붙으면 어느 쪽에 답해야 하는지
        // 알 수 없고, 질문은 답할 자리가 있는 쪽이다.
        // **한 번 거절한 질문은 다시 묻지 않는다**(ARTEL-487). 조건은 그대로이므로 매 턴 같은
        // 질문이 다시 만들어지는데, 그것을 그대로 내보내면 "그대로 두기"를 누른 사용자에게 같은
        // 것을 계속 묻는 셈이 된다. 답한 기록은 대화에 `answered` 로 남아 있다.
        val answered = answeredQuestionIds(runId, appUserId)
        val question = ScenarioQuestionBuilder.from(
            siblings.conflicting, blocked, siblings.untestedArms, scope, describe,
        )?.takeIf { it.id !in answered }
        val asked = question?.id?.substringBefore(":")
        val allNotices = buildList {
            if (asked != "gap") addAll(notices)
            addAll(
                siblingNotices(
                    siblings, describe,
                    skipArms = asked == "arm",
                    skipConflicts = asked == "conflict",
                )
            )
            if (asked != "scope" && scope.isNotEmpty()) {
                add(
                    "이번에 담은 범위 — " +
                        scope.joinToString(" · ") { (scene, taken, all) -> "$scene $taken/$all" } +
                        ". 같은 씬의 나머지도 담으려면 말씀해 주세요."
                )
            }
        }

        var applied = 0
        transactionalOperator.executeAndAwait {
            // 새 시나리오는 런의 현재 마지막 position 다음부터 붙인다. 비어 있으면 0부터.
            var runPosition = (runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
                .maxOfOrNull { it.position } ?: -1) + 1
            for (scenario in repaired) {
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
        logger.info("시나리오 반영 완료 [runId=$runId, applied=$applied/${repaired.size}]")
        return ReconcileOutcome(applied, findings, allNotices, question)
    }

    /**
     * 검증 스텝 사이를 계산된 경로로 메운다(ARTEL-468).
     *
     * 저작이 만드는 시나리오는 상태를 **만들지 않고 그렇다고 치는 스텝**을 쓴다 — 실측(2026-08-18)에서
     * 같은 씬 안의 상태 전이 66.7%, 씬 넷을 건너뛴 경우 100%가 그랬다. 지적해서 다시 쓰게 하는 것은
     * 통하지 않았고(해소 0/3), 여기서 계산값을 끼워 넣자 0%가 됐다.
     *
     * 실패는 **손대지 않는 쪽으로** 떨어진다. 씬 명세가 없는 프로젝트, 케이스가 지워진 경우, 조회
     * 자체가 터진 경우 모두 원본 그대로 흘려보낸다 — 저작을 막을 일이 아니다.
     *
     * 같은 케이스 쌍은 한 번만 묻는다. 한 턴에 시나리오 여럿이 같은 전이를 쓰는 일이 흔하고, 답은
     * 그 사이에 바뀌지 않는다.
     */
    private suspend fun repairByInsertion(
        routes: PathLookup,
        scenarios: List<ScenarioResult>,
        describe: (Long) -> String,
    ): Triple<List<ScenarioResult>, List<String>, List<String>> {
        val notices = mutableListOf<String>()
        // 무엇이 막았는지를 따로 모은다 — 알림 문장에서 되뽑으면 문구를 다듬을 때마다 깨진다.
        val blocked = mutableListOf<String>()

        val repaired = scenarios.map { scenario ->
            // **메울 구간이 없어도 지나간다.** 여기서 건너뛰면 근거를 확정하는 일까지 함께 건너뛰어,
            // 씬 이동이 없는 시나리오(1스텝짜리 포함)만 근거가 비어 저장된다 — 검사는 통과하는데
            // 실행하는 쪽에서 보면 그 스텝만 출처를 모르는 상태다.
            val gaps = ScenarioBridgeRepair.gaps(scenario.steps)
            val answers = buildMap {
                gaps.forEachIndexed { index, gap ->
                    val answer = routes.between(gap.fromCaseId, gap.toCaseId)
                    // 확인 자체를 못한 답은 답으로 치지 않는다 — 지도가 없는 프로젝트에까지
                    // 미상 스텝을 뿌리면 저작이 온통 "모른다"로 덮인다.
                    if (answer != null && !answer.unchecked) {
                        put(index, answer)
                        if (answer.result == ScenarioPathResult.UNKNOWN) {
                            answer.blockedBy?.let(blocked::add)
                        }
                    }
                }
            }
            val result = ScenarioBridgeRepair.apply(scenario.steps, answers, describe)
            if (result.steps.size != scenario.steps.size) {
                logger.info(
                    "교정 · 브리지 {}건 삽입 [scenarioId={}] {} → {} 스텝",
                    result.steps.size - scenario.steps.size, scenario.scenarioId,
                    scenario.steps.size, result.steps.size,
                )
            }
            notices += result.notices
            scenario.copy(steps = result.steps)
        }
        return Triple(repaired, notices.distinct(), blocked.distinct())
    }

    /**
     * **모른다고 적었는데 명세는 아는 길**인 자리를 찾는다(ARTEL-467).
     *
     * `UNKNOWN`을 무조건 통과시키면 전부 그것으로 적는 것이 가장 싼 통과 방법이 되어 검사가
     * 무의미해진다. 그래서 통과 사유이면서 동시에 검사 대상이다 — 이 대조를 하려면 검사하는 쪽이
     * 씬 명세를 쥐고 있어야 하고, 경로 계산을 Agent가 아니라 여기 둔 이유가 그것이다.
     *
     * 대조는 그 브리지를 감싼 **두 검증 스텝** 사이에 대해 한다. 브리지 자신은 케이스가 없어
     * 물어볼 좌표가 없기 때문이다. 앞뒤 어느 한쪽이라도 없으면(시나리오 처음이나 끝) 판정하지
     * 않는다 — 비교할 상대가 없는 것을 거짓이라 말할 수는 없다.
     */
    private suspend fun falseUnknowns(
        routes: PathLookup,
        scenarios: List<ScenarioResult>,
    ): List<ScenarioCoverageAudit.StepRef> = buildList {
        scenarios.forEachIndexed { scenarioIndex, scenario ->
            scenario.steps.forEachIndexed { stepIndex, step ->
                if (step.stepSource != ScenarioStepSource.UNKNOWN) return@forEachIndexed
                val before = scenario.steps.take(stepIndex).lastOrNull { it.caseId != null }?.caseId
                val after = scenario.steps.drop(stepIndex + 1).firstOrNull { it.caseId != null }?.caseId
                if (before == null || after == null) return@forEachIndexed

                val answer = routes.between(before, after) ?: return@forEachIndexed
                if (answer.result == ScenarioPathResult.KNOWN) {
                    add(
                        ScenarioCoverageAudit.StepRef(
                            scenarioIndex, stepIndex,
                            "모른다고 했지만 명세는 그 길을 안다(${answer.capabilityIds.joinToString()})",
                        )
                    )
                }
            }
        }
    }

    /**
     * 검증 스텝에 **그 케이스의 조작**을 채운다(ARTEL-466).
     *
     * 브리지에는 계산된 조작이 들어가는데 검증 스텝은 비어 있었다. 그래서 "맵을 확인한다"까지만
     * 적히고 무엇을 눌러 확인하는지는 실행하는 쪽이 다시 추측해야 했다.
     *
     * **근거 키로 짚은 조작이 딱 하나일 때만** 채운다. 여럿이면 어느 것인지 모르고, 값으로 닿은
     * 것(`effect`)은 같은 값을 건드리는 기능이 여럿일 수 있어 이 자리에 쓸 수 없다 — 하나로
     * 좁혀지지 않는 것을 채우면 그게 곧 지어내는 것이다.
     *
     * 모델이 이미 적어 둔 `input` 은 건드리지 않는다. 사용자가 고친 값일 수도 있다.
     */
    private suspend fun withCaseOperations(
        projectId: Long,
        appUserId: Long,
        scenarios: List<ScenarioResult>,
    ): List<ScenarioResult> {
        val needed = scenarios.flatMap { it.steps }
            .filter { it.caseId != null && it.input.isNullOrBlank() }
            .mapNotNull { it.caseId }
            .distinct()
        if (needed.isEmpty()) return scenarios

        val operation = needed.associateWith { caseId ->
            runCatching { caseFactService.explain(projectId, appUserId, caseId) }
                .onFailure { logger.warn("케이스 조작 조회 실패 — 비워 둔다: ${it.message}") }
                .getOrNull()
                ?.operations
                ?.filter { it.matchedBy == "evidence" }
                ?.singleOrNull()
                ?.input
                ?.ifBlank { null }
        }

        return scenarios.map { scenario ->
            scenario.copy(
                steps = scenario.steps.map { step ->
                    val input = step.caseId?.let { operation[it] }
                    if (step.input.isNullOrBlank() && input != null) step.copy(input = input) else step
                }
            )
        }
    }

    /** 시나리오별로 담긴 케이스 id. 순서는 여기서 보지 않는다 — 순서는 경로 쪽 질문이다. */
    private fun repairedSplit(scenarios: List<ScenarioResult>): List<List<Long>> =
        scenarios.map { scenario -> scenario.steps.mapNotNull { it.caseId }.distinct() }

    /**
     * 이 프로젝트의 케이스 전량을 판정에 쓸 모양으로 읽는다.
     *
     * 전량인 이유는 **안 담긴 것**을 말하려면 담긴 것만으로 모자라기 때문이다. 조회가 터지면 빈
     * 목록이고, 그러면 아래 검사들이 전부 조용해진다 — 이것들이 저작을 막을 이유는 없다.
     */
    private suspend fun caseFacts(projectId: Long): List<ScenarioSiblingCheck.CaseFact> = runCatching {
        testCaseRepository.findByProjectIdOrderByIdAsc(projectId).toList().map { case ->
            ScenarioSiblingCheck.CaseFact(
                id = case.id!!,
                scene = ScenarioStateReader.sceneOf(case),
                step = case.step.trim(),
                guards = ScenarioStateReader.guardsOf(case.precondition),
                declared = ScenarioStateReader.knownValuesOf(case.precondition),
            )
        }
    }.onFailure { logger.warn("케이스 전량 조회 실패 — 검사들을 넘어간다: ${it.message}") }
        .getOrElse { emptyList() }

    /**
     * **이번에 무엇을 고른 것인지 한 줄로 드러낸다**(ARTEL-466).
     *
     * 요청이 애매하면 경계는 누군가 정해야 하고, 지금은 모델이 조용히 정한다. 실측(같은 요청
     * 5회)에서 흔들린 것이 정확히 그 경계였다 — 타이틀 화면의 버튼 표시 확인을 넣은 회차와 뺀
     * 회차가 갈렸다. 애매한 요청은 예외가 아니라 기본값이므로(도구에 익숙하지 않은 1인 개발자가
     * 이 서비스의 대상이다), 고칠 것은 질문이 아니라 **고른 결과가 보이지 않는 것**이다.
     *
     * 씬별 비율만 낸다. id 를 늘어놓으면 스물몇 건짜리 씬에서 읽히지 않고, 사용자가 다음에 할
     * 말("타이틀 것도 다 넣어줘")에 필요한 것은 비율까지다.
     */
    private fun partialScenes(
        facts: List<ScenarioSiblingCheck.CaseFact>,
        split: List<List<Long>>,
    ): List<Triple<String, Int, Int>> {
        val used = split.flatten().toSet()
        if (used.isEmpty() || facts.isEmpty()) return emptyList()

        val touched = facts.filter { it.id in used }.mapNotNull { it.scene }.toSet()
        return touched.sorted()
            .map { scene ->
                Triple(
                    scene,
                    facts.count { it.scene == scene && it.id in used },
                    facts.count { it.scene == scene },
                )
            }
            .filter { (_, taken, all) -> taken < all }
    }

    /**
     * 형제에 대해 **말만 하는** 두 가지.
     *
     * 막지 않는 이유는 하나다. 무엇을 어떤 묶음으로 검증할지는 요청이 정하는 것이고, 코드가
     * 이기면 "133번만 보고 싶다"는 요청이 영영 통하지 않는다.
     */
    /**
     * 이 런에서 **이미 답한** 질문의 id(ARTEL-487).
     *
     * 거절도 답이다. 답한 질문을 다시 묻지 않으려면 무엇에 답했는지 알아야 하고, 그 기록은
     * 대화에 남는다 — 질문은 `kind=question`, 답하고 닫힌 것은 `kind=answered` 다.
     *
     * 읽지 못하면 빈 집합이다. 그때는 예전처럼 한 번 더 묻게 되는데, 못 읽었다고 질문을 통째로
     * 삼키는 것보다는 낫다.
     */
    private suspend fun answeredQuestionIds(runId: Long, appUserId: Long): Set<String> = runCatching {
        runMessageRepository.findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .toList()
            .mapNotNull { row -> row.payload?.let { objectMapper.readTree(it.asString()) } }
            .filter { it.path("kind").asText() == "answered" }
            .mapNotNull { it.path("id").asText().takeIf(String::isNotBlank) }
            .toSet()
    }.onFailure { logger.warn("답한 질문을 읽지 못했다 — 다시 물을 수 있다: ${it.message}") }
        .getOrDefault(emptySet())

    private fun siblingNotices(
        findings: ScenarioSiblingCheck.Findings,
        describe: (Long) -> String,
        skipArms: Boolean = false,
        skipConflicts: Boolean = false,
    ): List<String> = buildList {
        // 함께 담을 수 없는 것을 담았다(ARTEL-497). **막지 않고 말한다** — 요청이 그것이었을 수
        // 있고, 거절하면 사용자에게 남는 것이 없다. 몇 쌍인지와 한 예만 든다. 여덟 쌍을 모두
        // 늘어놓으면 읽히지 않고, 어느 것을 손볼지는 어차피 사람이 정한다.
        if (!skipConflicts && findings.conflicting.isNotEmpty()) {
            val (a, b) = findings.conflicting.first()
            add(
                "함께 담을 수 없는 케이스가 ${findings.conflicting.size}쌍 있습니다 — " +
                    "예: ${describe(a)} ↔ ${describe(b)}. 사전조건이 어긋나 한 번의 실행으로 둘 다 " +
                    "볼 수 없습니다. 나누려면 말씀해 주세요."
            )
        }
        findings.splitApart.forEach { (a, b) ->
            add(
                "${describe(a)} 와 ${describe(b)} 는 같은 자리의 케이스이고 동시에 성립합니다 — " +
                    "한 시나리오로 합쳐도 됩니다. 일부러 나눈 것이면 그대로 두세요."
            )
        }
        if (!skipArms) findings.untestedArms.forEach { (taken, missing) ->
            add(
                "${describe(taken)} 를 담았는데 ${describe(missing)} 가 빠졌습니다 — 같은 자리의 " +
                    "다른 갈래라 함께 볼 수는 없고, 따로 시나리오가 필요합니다."
            )
        }
    }

    /**
     * **없는 기능을 인용한** 자리를 찾는다(ARTEL-467).
     *
     * 없는 케이스 번호를 지어낸 것([ScenarioCoverageAudit.Findings.ghost])과 같은 종류다. 인용한
     * 번호가 실재하는지 보지 않으면 아무 숫자나 적는 것이 가장 싼 통과 방법이 되고, 그러면 근거
     * 필드는 근거가 아니라 형식이 된다.
     *
     * 코드가 메운 자리는 여기에 걸릴 수 없다 — 그 번호는 명세에서 읽어 온 것이다. 걸리는 것은
     * 계산이 닿지 않은 자리(사이가 필요 없다고 판정된 구간, 시나리오의 처음·끝)에 모델이 스스로
     * 적은 번호뿐이다.
     */
    private suspend fun ghostCapabilities(
        projectId: Long,
        appUserId: Long,
        scenarios: List<ScenarioResult>,
    ): List<ScenarioCoverageAudit.StepRef> {
        val cited = scenarios.flatMap { it.steps }
            .filter { it.stepSource == ScenarioStepSource.CAPABILITY }
            .mapNotNull { it.stepSourceCapabilityId }
        if (cited.isEmpty()) return emptyList()

        val live = runCatching { pathService.liveCapabilities(projectId, appUserId, cited) }
            .onFailure { logger.warn("기능 실재 확인 실패 — 통과시킨다: ${it.message}") }
            .getOrNull() ?: return emptyList()

        return buildList {
            scenarios.forEachIndexed { scenarioIndex, scenario ->
                scenario.steps.forEachIndexed { stepIndex, step ->
                    val id = step.stepSourceCapabilityId ?: return@forEachIndexed
                    if (step.stepSource == ScenarioStepSource.CAPABILITY && id !in live) {
                        add(
                            ScenarioCoverageAudit.StepRef(
                                scenarioIndex, stepIndex, "명세에 없는 기능을 인용했다($id)",
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 한 번의 reconcile 안에서 쓰는 경로 조회. **같은 케이스 쌍은 한 번만 묻는다** — 한 턴에
     * 시나리오 여럿이 같은 전이를 쓰는 일이 흔하고, 그 사이에 답이 바뀌지 않는다.
     *
     * 조회가 터지면 `null`이다. 부르는 쪽은 전부 "그 구간은 손대지 않는다"로 떨어진다 — 씬 명세를
     * 못 읽은 것이 저작을 막을 이유는 아니다.
     */
    private inner class PathLookup(private val projectId: Long, private val appUserId: Long) {
        private val asked = mutableMapOf<Pair<Long, Long>, ScenarioPathAnswer?>()

        suspend fun between(fromCaseId: Long, toCaseId: Long): ScenarioPathAnswer? {
            val key = fromCaseId to toCaseId
            if (asked.containsKey(key)) return asked[key]
            val answer = runCatching { pathService.findPath(projectId, appUserId, fromCaseId, toCaseId) }
                .onFailure { logger.warn("경로 조회 실패 — 그 구간은 손대지 않는다: ${it.message}") }
                .getOrNull()
            asked[key] = answer
            return answer
        }
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
