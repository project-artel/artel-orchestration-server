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
import kr.artel.orchestration.testscenario.dto.ScenarioStepKind
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
    // 케이스의 전제를 읽는 유일한 창구(ARTEL-627). 문장이 아니라 구조에서 읽는다.
    private val conditions: CaseConditionReader,

    private val scenarioRepository: TestScenarioRepository,
    private val runScenarioRepository: TestRunScenarioRepository,
    private val transactionalOperator: TransactionalOperator,
    private val objectMapper: ObjectMapper,
    private val testCaseRepository: TestCaseRepository,
    private val pathService: ScenarioPathService,
    private val caseFactService: ScenarioCaseFactService,
    private val runMessageRepository: TestRunMessageRepository,
    private val trace: AuthoringTrace,
    private val effectRepository: kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository,
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
        /**
         * 모르는 자리 **전부**(ARTEL-630). [question] 은 그중 첫 것이고, 옛 화면을 위해 남긴다.
         *
         * 하나만 내던 것은 같은 질문이 매 턴 다시 나가는 것을 막으려던 것이었는데(런 152), 그건
         * 답한 질문을 다시 안 묻는 것으로 풀 일이지 모르는 것을 감춰서 풀 일이 아니다.
         */
        val questions: List<ScenarioQuestion> = emptyList(),
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

        // 같은 자리의 케이스들을 본다(ARTEL-466). 나누고 합치는 문제는 말만 한다 — 그건 요청이
        // 정하는 것이지 코드가 정할 일이 아니다.
        val facts = caseFacts(projectId)
        // 케이스를 사람 말로 부르는 함수. 내부 번호는 사용자가 읽는 글에 넣지 않는다.
        val byId = facts.associateBy { it.id }
        val describe: (Long) -> String = { id -> byId[id]?.let(ScenarioSiblingCheck::describe).orEmpty() }

        // **함께 담을 수 없는 것은 묻지 않고 나눈다**(ARTEL-497). 되묻기로는 끝나지 않았다 —
        // 이유는 [ScenarioConflictSplit] 에 적었다(런 152: 같은 질문이 답할 때마다 다시 나갔다).
        val contested: (Long, Long) -> Set<String> = { a, b ->
            val left = byId[a]
            val right = byId[b]
            if (left == null || right == null) emptySet() else ScenarioSiblingCheck.contested(left, right)
        }
        // **걸어가는 것을 모순이라 하지 않는다**(ARTEL-581). 앞 스텝이 바꿔 놓은 값을 뒤 스텝이
        // 전제로 삼는 것은 함께 못 서는 것이 아니라 순서다 — 실측(런 159)에서 `MapMove.position` 이
        // 그랬고, 걸어가는 시나리오 하나가 네 조각이 났다.
        val changedBy = valuesChangedByCases(projectId)
        // **게임이 스스로 움직이는 값도 움직이는 값이다**(ARTEL-625). 위의 [changedBy] 는 케이스가
        // 된 기능만 알아서, 전투를 이겨야 오르는 값이 영영 얼어 있는 것으로 보인다.
        val movable = movableValues(projectId)
        val divided = ScenarioConflictSplit.apply(
            scenarios,
            contested,
            movable = { value -> movable.any { written -> sameTail(written, value) } },
        ) { changedBy[it].orEmpty() }
        val given = divided.scenarios
        divided.notes.forEach { (title, parts) ->
            logger.info("함께 담을 수 없어 나눴다 [runId={}] {} → {}조각", runId, title, parts)
        }
        if (divided.notes.isEmpty()) trace.record(runId, "1. 나눈다", "나눌 것 없음")
        else trace.record(
            runId, "1. 나눈다",
            divided.notes.joinToString("\n") { (title, parts) -> "$title → ${parts}조각" },
        )

        val split = repairedSplit(given)
        // **덜 담긴 것은 런 전체로 본다**(ARTEL-516). 이번 턴에 쓴 것만 보면, 다른 시나리오에
        // 이미 있는 갈래를 "빠졌다"고 세어 전건을 담은 뒤에도 되묻기가 멈추지 않는다.
        val covered = coveredInRun(runId, given, split)
        val siblings = ScenarioSiblingCheck.analyze(facts, split, covered) { value -> movable.any { written -> sameTail(written, value) } }
        if (siblings.conflicting.isNotEmpty()) {
            // 나눈 뒤에도 남았다면 나누는 쪽에 구멍이 있다는 뜻이다. 저장은 막지 않되 남긴다.
            logger.warn("나눈 뒤에도 동거 불가가 남았다 [runId={}] {}", runId, siblings.conflicting)
        }

        // 경로 조회는 한 번만 한다. 메우는 쪽과 검수하는 쪽이 같은 질문을 하기 때문에, 따로 물으면
        // 한 턴에 같은 케이스 쌍을 두 번씩 조회하게 된다.
        val routes = PathLookup(projectId, appUserId)

        // 검수보다 **먼저** 메운다. 순서가 반대면 코드가 고칠 수 있는 것 때문에 저장이 막히고,
        // 그러면 에이전트에게 다시 쓰라고 시키게 된다 — 그 방법이 안 통한다는 것이 이 작업의 전제다.
        val (bridged, notices, blocked) =
            repairByInsertion(routes, given, describe, openingFacts(projectId, facts))
        trace.record(
            runId, "2. 메운다",
            bridged.mapIndexed { index, after ->
                val before = given.getOrNull(index)?.steps?.size ?: 0
                "${after.title}: 스텝 $before → ${after.steps.size}"
            }.joinToString("\n") +
                (if (notices.isEmpty()) "" else "\n못 메운 구간:\n" + notices.joinToString("\n")) +
                (if (blocked.isEmpty()) "" else "\n막힌 것: $blocked"),
        )
        // 글자까지 같은 스텝이 서로 다른 케이스를 보면 무엇이 다른지 붙인다. 화면에서는 같은 줄이
        // 두 번 있는 것으로 보이고, 실행하는 사람은 중복이라 여겨 하나를 건너뛴다.
        val repaired = ScenarioSiblingLabel.apply(
            withCaseOperations(projectId, appUserId, bridged),
        ) { id -> byId[id]?.guards.orEmpty() }

        val raised = raisedIn(projectId)
        // **코드가 끼운 브리지도 값을 바꾼다.** 그것을 안 읽으면 멀쩡한 걸음을 어긋났다고 한다 —
        // 실측(런 225)에서 `position` 을 한 칸씩 옮기는 브리지 여섯을 못 읽고 여섯 건을 잘못 짚었다.
        val bridgeEffects = effectsOfBridges(repaired)
        // **한 번 걷고 둘을 함께 받는다**(ARTEL-660). 어긋남과 시작 조건을 따로 계산하면 규칙이
        // 둘이 되고, 그러면 갈라진다 — 실측(런 233)에서 저장된 안내가 계산과 정반대를 적었다.
        val walked = repaired.map { ScenarioContradictionCheck.walk(walkOf(it, byId, raised, bridgeEffects)) }
        val contradictions = repaired.zip(walked).flatMap { (scenario, found) ->
            found.contradictions.map { scenario.title to it }
        }
        val opened = repaired.zip(walked).map { (scenario, found) ->
            scenario.copy(
                steps = withOpeningNote(scenario.steps, openingFacts(projectId, facts), found.opening)
            )
        }
        // 검수는 저장 **전에** 끝난다. 통과하지 못한 결과는 한 줄도 들어가지 않는다 — 절반만 저장하면
        // "일부만 검증된 시나리오"가 남고, 그건 검사를 안 한 것보다 나쁘다(믿을 수 있어 보인다).
        val findings = ScenarioCoverageAudit.audit(
            projectCaseIds = testCaseRepository.findIdsByProjectId(projectId).toSet(),
            reviewed = reviewed,
            scenarios = opened,
        ).let {
            it.copy(
                ungrounded = it.ungrounded + ghostCapabilities(projectId, appUserId, opened),
                falseUnknowns = falseUnknowns(routes, opened),
                conflicting = siblings.conflicting,
            )
        }
        // **이 흐름이 자기가 말한 것과 어긋나나**(ARTEL-656). 게임을 돌리지 않는다 — 지도가
        // 적어 둔 것끼리 부딪히는지만 본다. 지금은 **막지 않고 남긴다**: 저작이 검수에 막혀
        // 결과가 통째로 버려지는 것이 사용자가 가장 싫어한 일이고, 흐름 계산이 들어와 이런
        // 자리가 애초에 안 생기게 된 뒤에 관문으로 올리는 것이 순서다.
        contradictions.forEach { (title, found) ->
            logger.warn("흐름이 스스로 어긋난다 [runId={}] {} — {}", runId, title, found.describe())
        }
        trace.record(
            runId, "3. 검수한다",
            "판정: ${if (findings.rejected) "막음 — 한 줄도 저장하지 않는다" else "통과"}\n" +
                "안 담은 것 ${findings.missing} · 판정 안 한 것 ${findings.unreviewed} · " +
                "없는 번호 ${findings.ghost} · 없는 근거 ${findings.ungrounded.size}건 · " +
                "모른다고 했으나 아는 자리 ${findings.falseUnknowns.size}건 · " +
                "함께 못 서는 것 ${findings.conflicting.size}건 · " +
                "스스로 어긋남 ${contradictions.size}건(막지 않음)" +
                contradictions.joinToString("") { (title, found) -> "\n  $title: ${found.describe()}" },
        )
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
        if (findings.unsourced.isNotEmpty()) {
            // 막지 않는 이유는 ScenarioCoverageAudit.Findings 에 적었다. 남기는 이유도 excess 와
            // 같다 — 이 값이 늘어나면 스텝 계약에 자리가 없다는 뜻이라 계약을 고칠 근거가 된다.
            logger.info("근거를 적지 않은 스텝(허용) [runId={}] {}개", runId, findings.unsourced.size)
        }

        val scope = partialScenes(facts, split, covered)
        // **물은 것은 통보로 되풀이하지 않는다.** 같은 말이 두 줄로 붙으면 어느 쪽에 답해야 하는지
        // 알 수 없고, 질문은 답할 자리가 있는 쪽이다.
        // **한 번 거절한 질문은 다시 묻지 않는다**(ARTEL-487). 조건은 그대로이므로 매 턴 같은
        // 질문이 다시 만들어지는데, 그것을 그대로 내보내면 "그대로 두기"를 누른 사용자에게 같은
        // 것을 계속 묻는 셈이 된다. 답한 기록은 대화에 `answered` 로 남아 있다.
        val answered = answeredQuestionIds(runId, appUserId)
        // **모르는 자리를 전부 낸다**(ARTEL-630). 하나만 내면 나머지는 아무 말 없이 미상으로 남고,
        // 사용자는 시나리오가 완성된 줄 안다 — 실측(런 178)에서 못 간다고 적은 자리가 일곱인데
        // 물은 것은 하나였다.
        val questions = ScenarioQuestionBuilder.all(
            blocked, siblings.untestedArms, scope, describe,
        ).filterNot { it.id in answered }
        // 옛 화면은 아직 한 개짜리 칸을 읽는다. 첫 질문을 거기 그대로 둬서 옮겨 올 시간을 준다.
        val question = questions.firstOrNull()
        val asked = question?.id?.substringBefore(":")
        val allNotices = buildList {
            // 나눈 것은 **먼저** 말한다. 시나리오 수가 달라진 이유이므로, 아래 알림들보다 먼저
            // 읽혀야 화면에 늘어난 카드가 무엇인지 알 수 있다.
            // **위의 답변과 어긋나지 않게 말한다**(ARTEL-518). 모델은 "기존 시나리오에 담았다"고
            // 답했는데 화면에는 새 시나리오가 늘어나 있는 일이 실제로 나왔다(런 155) — 모델이
            // 거짓말을 한 것이 아니라 그 뒤에 코드가 나눴기 때문이다. 몇 개가 새로 생겼는지까지
            // 말해 주면 둘이 이어진다.
            divided.notes.forEach { (title, parts) ->
                add(
                    "‘$title’ 은 함께 담을 수 없는 케이스가 있어 코드가 ${parts}개로 나눴습니다" +
                        "(새 시나리오 ${parts - 1}개가 바로 뒤에 붙었습니다) — " +
                        "사전조건이 어긋나 한 번의 실행으로 다 볼 수 없습니다."
                )
            }
            if (asked != "gap") addAll(notices)
            addAll(siblingNotices(siblings, describe, skipArms = asked == "arm"))
            unsourcedNotice(findings, opened)?.let(::add)
            if (asked != "scope" && scope.isNotEmpty()) {
                add(
                    "이 런에 담긴 범위 — " +
                        scope.joinToString(" · ") { (scene, taken, all) -> "$scene $taken/$all" } +
                        ". 같은 씬의 나머지도 담으려면 말씀해 주세요."
                )
            }
        }

        var applied = 0
        transactionalOperator.executeAndAwait {
            val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
            // 새 시나리오는 런의 현재 마지막 position 다음부터 붙인다. 비어 있으면 0부터.
            var runPosition = (links.maxOfOrNull { it.position } ?: -1) + 1
            // 나눠서 생긴 조각을 원본 옆으로 옮기려면 저장된 id 를 자리별로 들고 있어야 한다.
            val savedId = arrayOfNulls<Long>(opened.size)
            val fromSplit = mutableListOf<Pair<Long, Int>>()
            for ((index, scenario) in opened.withIndex()) {
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
                    savedId[index] = scenarioId
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
                    savedId[index] = saved.id
                    divided.anchorOf[index]?.let { anchor -> fromSplit += saved.id!! to anchor }
                    applied++
                }
            }
            if (fromSplit.isNotEmpty()) {
                placeBesideOrigin(runId, fromSplit.mapNotNull { (id, anchor) -> savedId[anchor]?.let { id to it } })
            }
        }
        logger.info("시나리오 반영 완료 [runId=$runId, applied=$applied/${opened.size}]")
        trace.record(
            runId, "4. 저장한다",
            "시나리오 $applied/${opened.size}개\n" +
                opened.joinToString("\n") { "  · ${it.title} — 스텝 ${it.steps.size}" } +
                (if (questions.isEmpty()) "" else "\n되묻는다: " + questions.joinToString(" · ") { it.id }),
        )
        return ReconcileOutcome(applied, findings, allNotices, question, questions)
    }

    /**
     * 나눠서 생긴 조각을 **원본 바로 뒤로** 옮긴다(ARTEL-518).
     *
     * 새 시나리오는 런 끝에 붙는다. 대부분은 그게 맞다 — 새로 만든 것은 마지막에 하면 된다.
     * 그런데 나눠서 생긴 조각은 새로 만든 것이 아니라 **원본의 나머지 반쪽**이다. 실측(런 155)에서
     * 맵 구간(position 2~8)을 나눈 조각이 EndingScene 뒤인 position 21 에 놓였다. 흐름을 순서대로
     * 읽는 화면에서 그 조각은 아무 데서도 이어지지 않는다.
     *
     * `(test_run_id, position)` 이 유일하므로 한 번에 옮길 수 없다. 전부 멀리 밀어 둔 뒤 최종
     * 번호를 다시 매긴다 — 그 사이에도 유일성이 깨지지 않는다.
     *
     * @param parts `조각 시나리오 id → 원본 시나리오 id`. 같은 원본의 조각들은 받은 순서대로 붙는다.
     */
    private suspend fun placeBesideOrigin(runId: Long, parts: List<Pair<Long, Long>>) {
        val links = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        val order = links.map { it.testScenarioId }.toMutableList()

        // 한 원본에서 여러 조각이 나오면 **받은 순서대로** 이어 붙인다. 매번 원본 바로 뒤에
        // 꽂으면 순서가 뒤집힌다.
        val after = mutableMapOf<Long, Long>()
        var moved = false
        for ((part, origin) in parts) {
            val from = order.indexOf(part)
            val previous = after[origin] ?: origin
            // 원본이 이 런에 없으면(다른 런의 시나리오를 고친 경우) 끝에 그대로 둔다.
            if (from < 0 || order.indexOf(previous) < 0) continue
            order.removeAt(from)
            order.add(order.indexOf(previous) + 1, part)
            after[origin] = part
            moved = true
        }
        if (!moved || order == links.map { it.testScenarioId }) return

        // 1) 자리를 비운다. 유일 제약 때문에 한 행씩 최종 번호로 바로 못 간다.
        //
        // 밀어 두는 거리는 **지금 쓰이는 자리에서 계산한다.** 최종 번호는 0부터 `links.size - 1`
        // 까지이므로, 지금 가장 큰 자리보다 하나 더 큰 곳부터 밀면 그 구간과 겹치지 않는다.
        // 상수로 박아 두면 그 수를 넘는 런에서 조용히 부딪힌다.
        val parking = maxOf(links.maxOf { it.position } + 1, links.size)
        links.forEach { runScenarioRepository.save(it.copy(position = it.position + parking)) }
        // 2) 최종 번호.
        val byScenario = links.associateBy { it.testScenarioId }
        order.forEachIndexed { position, scenarioId ->
            byScenario[scenarioId]?.let { runScenarioRepository.save(it.copy(position = position)) }
        }
        logger.info("나눈 조각을 원본 옆으로 옮겼다 [runId={}] {}건", runId, parts.size)
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
        openingFacts: OpeningFacts,
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
            // **시작 안내는 여기서 안 붙인다**(ARTEL-660). 무엇이 미리 참이어야 하는지는 메운
            // 뒤에 흐름을 한 번 걸어 봐야 알고, 그 걸음은 어긋남 검사가 이미 걷는다.
            scenario.copy(steps = result.steps)
        }
        return Triple(repaired, notices.distinct(), blocked.distinct())
    }

    /**
     * **여기까지 와야 시작한다**를 첫 스텝 앞에 적는다(ARTEL-636).
     *
     * 시나리오는 하나가 끝날 때마다 게임을 초기화하는데 검증하는 순간은 게임 곳곳에 흩어져 있다.
     * 엔딩을 보는 시나리오는 매번 엔딩까지 다시 가야 한다. 첫 스텝이 `StagePosition >= 4` 를
     * 요구하는데 아무 말도 없으면, 실행하는 쪽에게는 "알아서 네 번 이겨라"와 같다.
     *
     * **길을 찾아 주지는 않는다.** 무엇이 참이어야 시작하는지와 그 값이 어디서 오르는지만 적는다 —
     * 어떻게 가는지는 실행하는 쪽과 지도가 풀 문제다.
     *
     * 그 시나리오가 **스스로 만드는 값은 빼고** 본다. 앞 스텝이 만들어 주는 것까지 "미리 와 있으라"
     * 고 하면 할 수 있는 일을 못 하게 막는 셈이다.
     */
    private fun withOpeningNote(
        steps: List<ChatScenarioStep>,
        facts: OpeningFacts,
        selfMade: List<Guard>,
    ): List<ChatScenarioStep> {
        val first = steps.firstOrNull() ?: return steps
        // 이미 붙어 있으면 다시 붙이지 않는다 — 재작성 턴이 같은 시나리오를 다시 낸다.
        if (first.stepKind == ScenarioStepKind.OPENING) return steps
        val note = ScenarioOpeningNote.of(openingNeeds(selfMade, facts)) ?: return steps
        return listOf(
            // 근거를 달지 않는다. 이 줄은 모델이 쓴 것이 아니라 **지도에서 계산한 것**이고,
            // `CAPABILITY` 로 적으면 어느 기능인지를 대야 하는데 댈 것이 없다.
            ChatScenarioStep(action = note, stepKind = ScenarioStepKind.OPENING)
        ) + steps
    }

    /**
     * 시작할 때 이미 참이어야 하는 것들(ARTEL-636 · ARTEL-660).
     *
     * **무엇이 그것인지는 걸음이 정한다.** 흐름을 위에서 아래로 걸어 보면, 한 번도 정해진 적 없고
     * 모르게 된 적도 없이 요구된 것이 곧 스스로 못 만드는 것이다([ScenarioContradictionCheck.walk]).
     *
     * 앞서는 여기서 따로 훑어 세었고, 그래서 **저장되는 안내가 계산과 갈라졌다.** 실측(런 233)에서
     * 한 시나리오는 계산이 `flag != 0` 인데 안내는 `진행도 >= 4 로 시작하라`고 적었고, 다른 하나는
     * 계산이 `!= 5` 인데 안내는 `== 5` 로 **정반대**였다. 그리고 값을 변수별 최댓값으로 고르다 보니
     * 흐름이 사이에서 만들어 내는 값까지 미리 와 있으라고 했다 — "보스 앞까지 이겨 놓고 와서 전투를
     * 세 번 더 해라"가 된다(런 235, 시나리오 799).
     *
     * 여기 남은 것은 **무엇을 적지 않을지**뿐이다. 진행을 요구하는 비교만 보고, `!= 5` 나 `== 0`
     * 처럼 초기화 직후로도 성립하는 것은 뺀다 — 적으면 매 시나리오에 한 줄이 붙어 소음이 된다.
     * 어디서 오르는지 모르는 것도 뺀다: 찾아갈 실마리가 없는 안내는 "알아서 하라"와 같다.
     */
    private fun openingNeeds(
        opening: List<Guard>,
        facts: OpeningFacts,
    ): List<ScenarioOpeningNote.Requirement> = opening
        .filter { guard -> guard.operator in PROGRESS_OPERATORS }
        .filter { guard -> (guard.value.toDoubleOrNull() ?: 0.0) > 0 }
        .mapNotNull { guard ->
            val raisedIn = facts.raisedIn[ScenarioStateReader.normalize(guard.path)].orEmpty()
            if (raisedIn.isEmpty()) null
            else ScenarioOpeningNote.Requirement(
                variable = guard.variable,
                comparison = "${guard.operator} ${guard.value}",
                raisedIn = raisedIn.toList(),
            )
        }
        .distinctBy { it.variable to it.comparison }
        .sortedBy { it.variable }

    /**
     * 시작 안내를 쓰는 데 필요한 사실들. 케이스마다 한 벌씩 미리 모아 둔다 — 시나리오마다 다시
     * 조회하면 같은 값을 판마다 여러 번 읽는다.
     */
    private data class OpeningFacts(
        val guards: Map<Long, List<Guard>>,
        val arrivesAt: Map<Long, String?>,
        val raisedIn: Map<String, List<String>>,
    )

    private suspend fun openingFacts(projectId: Long, facts: List<ScenarioSiblingCheck.CaseFact>): OpeningFacts =
        OpeningFacts(
            guards = facts.associate { it.id to it.guards },
            arrivesAt = runCatching {
                testCaseRepository.findByProjectIdOrderByIdAsc(projectId).toList().associate { case ->
                    case.id!! to runCatching { objectMapper.readTree(case.metadata.asString()) }
                        .getOrNull()?.path("arrives_at")?.asText(null)?.takeIf { it.isNotBlank() }
                }
            }.getOrElse { emptyMap() },
            raisedIn = runCatching {
                testCaseRepository.findValueRaisers(projectId).toList()
                    .groupBy({ ScenarioStateReader.normalize(it.target) }, { it.scene })
                    .mapValues { (_, scenes) -> scenes.distinct().sorted() }
            }.getOrElse { emptyMap() },
        )

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
                guards = conditions.guardsOf(case),
                declared = conditions.knownValuesOf(case),
            )
        }
    }.onFailure { logger.warn("케이스 전량 조회 실패 — 검사들을 넘어간다: ${it.message}") }
        .getOrElse { emptyList() }

    /**
     * 케이스마다 **바꾸는 값들**(ARTEL-581). 지도를 못 되짚는 케이스는 여기 없고, 그때는 바꾸는
     * 것을 모르는 것이라 예전처럼 나눈다 — 모르면 나누는 쪽이 안전하다.
     */
    /**
     * **지도 안에서 움직이는 값들**(ARTEL-625). 케이스가 아니라 지도 전체에 묻는다.
     *
     * 못 읽으면 빈 목록이고, 그때는 이 칸이 생기기 전과 똑같이 나눈다.
     */
    /**
     * **그 값이 어느 화면에서 저절로 움직이나.** 흐름이 그 화면을 지나면 그 값은 모르는 것이 된다.
     *
     * 이것을 안 읽으면 멀쩡한 흐름을 틀렸다고 한다 — 전투에 들어갔다 나오면 진행도가 올라 있는데,
     * 그것을 안 놓으면 다음 자리가 전부 어긋나 보인다.
     */
    /**
     * 코드가 끼운 브리지가 **무엇을 바꾸나**. 값 → (대상, 어떻게) 로 돌려준다.
     *
     * 스텝의 글이 아니라 그 스텝이 든 기능 번호로 읽는다 — 문구를 다듬을 때마다 검사가 조용히
     * 깨지는 길은 이 저장소가 이미 걷어냈다.
     */
    private suspend fun effectsOfBridges(
        scenarios: List<ScenarioResult>,
    ): Map<Long, List<Pair<String, String?>>> {
        val ids = scenarios.flatMap { it.steps }
            .filter { it.caseId == null }
            .mapNotNull { it.stepSourceCapabilityId }
            .distinct()
        if (ids.isEmpty()) return emptyMap()
        return ids.associateWith { id ->
            runCatching {
                effectRepository.findByCapabilityIdOrderByIdAsc(id).toList()
                    .filter { it.kind == "write" || it.kind == "saved" }
                    .mapNotNull { effect -> effect.target?.let { it to effect.detail } }
            }.getOrDefault(emptyList())
        }
    }

    private suspend fun raisedIn(projectId: Long): Map<String, Set<String>> = runCatching {
        testCaseRepository.findValueRaisers(projectId).toList()
            .groupBy({ ScenarioStateReader.normalize(it.target) }, { it.scene })
            .mapValues { (_, scenes) -> scenes.toSet() }
    }.onFailure { logger.warn("저절로 바뀌는 자리 조회 실패 — 어긋남을 덜 잡는다: ${it.message}") }
        .getOrDefault(emptyMap())

    /**
     * 흐름 하나를 [ScenarioContradictionCheck] 가 읽을 사실로 옮긴다(ARTEL-656).
     *
     * **스텝의 글을 되읽지 않는다.** 값은 케이스의 전제 구조와 스텝의 칸(`step_unknown_reason`)에서
     * 온다 — 문구를 다듬을 때마다 검사가 조용히 깨지는 길은 이 저장소가 이미 걷어냈다.
     */
    private fun walkOf(
        scenario: ScenarioResult,
        byId: Map<Long, ScenarioSiblingCheck.CaseFact>,
        raisedIn: Map<String, Set<String>>,
        bridgeEffects: Map<Long, List<Pair<String, String?>>>,
    ): List<ScenarioContradictionCheck.Step> = scenario.steps.mapIndexed { index, step ->
        val fact = step.caseId?.let { byId[it] }
        // 메우지 못한 구간을 지나면 그 자리가 무엇으로 바뀌는지 모른다. 막은 것이 화면 쌍이면
        // (`A→B`) 그 화면들을 지나는 것이므로 거기서 오르는 값도 함께 놓아 준다.
        val gap = step.stepUnknownReason?.takeIf { step.stepKind == ScenarioStepKind.GAP }
        val passed = buildSet {
            fact?.scene?.let(::add)
            gap?.split("→")?.map { it.trim() }?.takeIf { it.size == 2 }?.let(::addAll)
        }
        // 브리지가 값을 **정하면** 그 값이고, 몇 칸씩 미는 것이거나 무엇이 될지 모르면 놓아 준다.
        //
        // **부호가 붙은 것은 정하는 것이 아니다.** `+1` 은 물론이고 `-1` 도 그렇다 — 적재기가
        // 음수 대입과 감소를 같은 글자로 내기 때문에 형식이 둘을 못 가른다(경로 서비스도 같은
        // 자리에서 감소로 읽는다). 실측(런 226)에서 `-1` 을 대입으로 읽어 멀쩡한 걸음을 어긋났다고
        // 짚었다. 되풀이 브리지도 여기서 놓아 주는 쪽이 맞다 — 몇 번 눌러 어디에 멈추는지는
        // 그 다음 케이스의 전제가 말한다.
        val written = step.stepSourceCapabilityId?.let { bridgeEffects[it] }.orEmpty()
        val bridgeSets = written.mapNotNull { (target, detail) ->
            detail?.trim()
                ?.takeIf { it.toDoubleOrNull() != null && it.first() != '+' && it.first() != '-' }
                ?.let { ScenarioStateReader.normalize(target) to it }
        }.toMap()
        ScenarioContradictionCheck.Step(
            at = index + 1,
            caseId = step.caseId,
            requires = fact?.guards.orEmpty(),
            sets = fact?.guards.orEmpty()
                .filter { it.operator == "==" && !it.symbolic }
                .associate { it.variable to it.value } + fact?.declared.orEmpty() + bridgeSets,
            clears = buildSet {
                gap?.takeIf { !it.contains("→") }?.let(::add)
                raisedIn.forEach { (value, scenes) -> if (scenes.any { it in passed }) add(value) }
                written.map { ScenarioStateReader.normalize(it.first) }
                    .filterNotTo(this) { it in bridgeSets }
            },
        )
    }

    private suspend fun movableValues(projectId: Long): Set<String> = runCatching {
        testCaseRepository.findWrittenValues(projectId).toList().toSet()
    }.onFailure { logger.warn("지도가 움직이는 값 조회 실패 — 예전처럼 나눈다: ${it.message}") }
        .getOrElse { emptySet() }

    /**
     * 지도가 부르는 이름과 사전조건이 적는 이름이 **같은 값**인가.
     *
     * 효과는 물건을 가리키고(`CombineButton.combineZone`) 전제는 그 물건의 속성을 읽는다
     * (`...combineZone.activeSelf`). 마디 하나 차이라 꼬리로는 안 만난다.
     */
    private fun sameTail(written: String, guarded: String): Boolean =
        written == guarded || written.endsWith(".$guarded") || guarded.endsWith(".$written") ||
            guarded.substringBeforeLast('.', "") == written

    private suspend fun valuesChangedByCases(projectId: Long): Map<Long, Set<String>> = runCatching {
        testCaseRepository.findValuesChangedByCases(projectId).toList()
            .groupBy({ it.caseId }, { it.target })
            .mapValues { (_, targets) -> targets.toSet() }
    }.onFailure { logger.warn("케이스가 바꾸는 값 조회 실패 — 순서를 모른 채 나눈다: ${it.message}") }
        .getOrElse { emptyMap() }

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
     *
     * **어느 씬을 말할지는 이번 턴이 정하고, 몇 건인지는 런 전체가 정한다**(ARTEL-516). 둘을
     * 갈라야 하는 이유가 실측에 있다(런 155): 비율을 이번 턴으로 세면 미커버 0/66 인 화면에서
     * "덜 담긴 씬이 있습니다 — StoryScene 2/6"이 뜬다. 화면의 배지와 정면으로 어긋나고, 사용자는
     * 둘 중 무엇을 믿어야 하는지 알 수 없다. 반대로 씬 목록까지 런 전체로 하면 이번 요청과
     * 상관없는 씬이 매 턴 딸려 온다.
     */
    private fun partialScenes(
        facts: List<ScenarioSiblingCheck.CaseFact>,
        split: List<List<Long>>,
        covered: Set<Long>,
    ): List<Triple<String, Int, Int>> {
        val used = split.flatten().toSet()
        if (used.isEmpty() || facts.isEmpty()) return emptyList()

        val touched = facts.filter { it.id in used }.mapNotNull { it.scene }.toSet()
        return touched.sorted()
            .map { scene ->
                Triple(
                    scene,
                    facts.count { it.scene == scene && it.id in covered },
                    facts.count { it.scene == scene },
                )
            }
            .filter { (_, taken, all) -> taken < all }
    }

    /**
     * **이 런에 지금 담겨 있는 케이스 전량**(ARTEL-516).
     *
     * 이번 턴에 쓴 것과, 런의 다른 시나리오에 이미 들어 있는 것을 합친 값이다. 이번 턴이 기존
     * 시나리오를 고쳐 쓰는 경우 **그 시나리오의 옛 내용은 세지 않는다** — 곧 덮어쓸 것이라,
     * 그것까지 담긴 것으로 세면 방금 빠진 케이스가 담겨 있다고 나온다.
     *
     * 조회가 터지면 이번 턴만 돌려준다. 예전 동작이고, 되묻기가 한 번 더 나갈 뿐이다 — 읽지
     * 못했다고 저작을 막을 일은 아니다.
     */
    private suspend fun coveredInRun(
        runId: Long,
        turn: List<ScenarioResult>,
        split: List<List<Long>>,
    ): Set<Long> {
        val thisTurn = split.flatten().toSet()
        val rewritten = turn.mapNotNull { it.scenarioId }.toSet()
        return runCatching {
            val elsewhere = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
                .map { it.testScenarioId }
                .filterNot { it in rewritten }
                .flatMap { scenarioId ->
                    scenarioRepository.findById(scenarioId)
                        ?.toDraft(objectMapper)?.steps.orEmpty()
                        .mapNotNull { it.caseId }
                }
            thisTurn + elsewhere
        }.onFailure { logger.warn("런에 담긴 케이스를 읽지 못했다 — 이번 턴만 본다: ${it.message}") }
            .getOrDefault(thisTurn)
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

    /**
     * 근거를 적지 않은 스텝을 **말만 한다**(ARTEL-515). 막지 않는 이유는 [ScenarioCoverageAudit.Findings]에 있다.
     *
     * 실측에서 이런 스텝은 전부 한 종류였다 — `case_id` 가 없는 전제 세팅 동작("스테이지 위치가
     * 5인 상태로 EndingScene에 진입한다"). 실행하는 사람이 **그 자리에서 막힌다**는 뜻이므로,
     * 조용히 저장하면 실행하다 만난다.
     *
     * 한 예만 든다. 열세 줄을 늘어놓으면 읽히지 않고, 어느 것을 손볼지는 어차피 사람이 정한다.
     */
    private fun unsourcedNotice(
        findings: ScenarioCoverageAudit.Findings,
        scenarios: List<ScenarioResult>,
    ): String? {
        val first = findings.unsourced.firstOrNull() ?: return null
        val example = scenarios.getOrNull(first.scenarioIndex)
            ?.steps?.getOrNull(first.stepIndex)?.action?.trim()?.ifBlank { null }
        return "어떻게 하는지 적히지 않은 준비 동작이 ${findings.unsourced.size}개 있습니다" +
            (example?.let { " — 예: $it" } ?: "") +
            ". 실행하기 전에 그 자리를 채워 두세요."
    }

    private fun siblingNotices(
        findings: ScenarioSiblingCheck.Findings,
        describe: (Long) -> String,
        skipArms: Boolean = false,
    ): List<String> = buildList {
        // 함께 담을 수 없는 것은 [ScenarioConflictSplit] 이 이미 나눴다. 여기까지 남았다면 나누는
        // 쪽이 못 푼 것이므로 **말은 해 준다** — 조용히 두면 실행하다 멎는 이유를 알 수 없다.
        if (findings.conflicting.isNotEmpty()) {
            val (a, b) = findings.conflicting.first()
            add(
                "함께 담을 수 없는 케이스가 ${findings.conflicting.size}쌍 남았습니다 — " +
                    "예: ${describe(a)} ↔ ${describe(b)}. 사전조건이 어긋나 한 번의 실행으로 둘 다 " +
                    "볼 수 없습니다."
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
    private companion object {
        /**
         * 진행을 요구하는 비교. `!= 5` 나 `== 0` 은 초기화 직후로도 성립할 수 있어 시작 안내에
         * 적을 것이 없고, 적으면 매 시나리오에 한 줄이 붙어 그 자체가 소음이 된다.
         */
        val PROGRESS_OPERATORS = setOf("==", ">=", ">")
    }

}
