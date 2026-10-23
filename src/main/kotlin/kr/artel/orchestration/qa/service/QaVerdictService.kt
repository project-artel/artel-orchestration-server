package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.config.StorageProperties
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.qa.dto.QaCodeCheck
import kr.artel.orchestration.qa.dto.QaCodeCheckItem
import kr.artel.orchestration.qa.dto.QaRunVerdictsResponse
import kr.artel.orchestration.qa.dto.QaScenarioStepView
import kr.artel.orchestration.qa.dto.QaStepAction
import kr.artel.orchestration.qa.dto.QaStepIssue
import kr.artel.orchestration.qa.dto.QaStepVerdict
import kr.artel.orchestration.qa.dto.QaTryVerdicts
import kr.artel.orchestration.qa.dto.QaVerdictCapture
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.verdict.CodeCheck
import kr.artel.orchestration.qa.verdict.QaVerdictCodeCheckService
import kr.artel.orchestration.qa.verdict.needsReview
import kr.artel.orchestration.testcase.service.CaseScope
import kr.artel.orchestration.testcase.service.TestCaseReader
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service

private val EVIDENCE_STATES = setOf("verified", "uncertain", "none")

/** 한 시나리오 실행에서 이슈를 이만큼 넘게 보고하는 일은 없다. 이슈 화면의 페이지 상한과 같다. */
private const val MAX_ISSUES = 200

/**
 * 런의 스텝 판정과 그 근거를 읽는다(ARTEL-1134). QA 가 끝난 뒤 사용자가 "왜 이 판정인가" 를 보는 자리다.
 *
 * 근거는 따로 저장하지 않는다. agent 가 보낸 스텝 STATUS 가 qa_log 에 그대로 남으므로 그것을 읽는다 — 진행 중
 * 화면(SSE)과 끝난 뒤 화면이 같은 행을 본다.
 */
@Service
class QaVerdictService(
    private val qaRuns: QaRunRepository,
    private val qaTries: QaTryRepository,
    private val qaLogs: QaLogRepository,
    private val scenarios: TestScenarioRepository,
    private val testRuns: TestRunRepository,
    private val gameBuilds: GameBuildRepository,
    private val issues: IssueRepository,
    private val caseReader: TestCaseReader,
    private val codeChecks: QaVerdictCodeCheckService,
    private val storage: DocumentStorage,
    private val storageProperties: StorageProperties,
    private val objectMapper: ObjectMapper,
) {
    /** 보이지 않는 런은 404 다([QaRunChangeService] 와 같은 규칙). */
    suspend fun list(qaRunId: Long, appUserId: Long): QaRunVerdictsResponse {
        val run = qaRuns.findAccessibleById(qaRunId, appUserId) ?: throw NotFoundException()
        val judgedBuildId = caseReader.judgedBuildId(run.gameBuildId)
        val scope = CaseScope.of(judgedBuildId)
        val tries = qaTries.findByQaRunId(qaRunId).toList().sortedBy { it.id }.map { qaTry ->
            val qaTryId = requireNotNull(qaTry.id)
            val scenario = scenarios.findById(qaTry.testScenarioId)
            val steps = scenario?.let { objectMapper.readTree(it.steps.asString()) }
            // 한 스텝에 판정이 둘이면(FREE 의 덮어쓰기) 나중 것이 그 스텝의 판정이다.
            val verdictRows = qaLogs.findStepVerdicts(qaTryId).toList().map { it to payloadOf(it) }
            val rows = verdictRows.associateBy({ it.second.path("step").asInt() }, { it.first })
            val payloads = verdictRows.associateBy({ it.second.path("step").asInt() }, { it.second })
            val checks = codeChecks.checkTry(qaTryId, judgedBuildId, verdictRows)
            val captures = captureLinks(qaTryId, payloads.values.flatMap(::capturedIds).toSet())
            val calls = if (payloads.values.all { it.hasNonNull("reason") }) emptyList()
            else qaLogs.findVerdictToolCalls(qaTryId).toList()
            val actions = actionsByStep(qaTryId)
            QaTryVerdicts(
                qaTryId = qaTryId.toString(),
                testScenarioId = qaTry.testScenarioId.toString(),
                scenarioTitle = scenario?.title.orEmpty(),
                executionMode = scenario?.executionMode?.name ?: "ORDERED",
                status = qaTry.status,
                model = qaTry.model,
                startedAt = qaTry.startedAt,
                completedAt = qaTry.completedAt,
                steps = stepViews(steps, scope),
                verdicts = rows.toSortedMap().map { (step, row) ->
                    verdictOf(row, payloads.getValue(step), steps, captures, calls, actions[step].orEmpty(), checks[row.id])
                },
                issues = issues.findPageByQaTry(qaTryId, null, MAX_ISSUES).toList().sortedBy { it.id }.map { issue ->
                    val detail = objectMapper.readTree(issue.detail.asString())
                    QaStepIssue(
                        issueId = requireNotNull(issue.id).toString(),
                        title = issue.title,
                        severity = issue.severity,
                        step = detail.path("step").takeIf { it.canConvertToInt() }?.asInt(),
                    )
                },
            ).let { tried -> tried.copy(reviewCount = tried.verdicts.count { it.needsReview }) }
        }
        return QaRunVerdictsResponse(
            qaRunId = qaRunId.toString(),
            testRunId = run.testRunId.toString(),
            testRunName = testRuns.findById(run.testRunId)?.name.orEmpty(),
            gameBuildVersion = run.gameBuildId?.let { gameBuilds.findById(it) }?.version,
            status = run.status,
            startedAt = run.startedAt,
            completedAt = run.completedAt,
            tries = tries,
        )
    }

    /** 시나리오의 모든 스텝과 그 TC 의 기대 결과. TC 내용은 이 실행이 판정받는 빌드 버전의 것이다. */
    private suspend fun stepViews(steps: JsonNode?, scope: CaseScope): List<QaScenarioStepView> {
        val items = steps?.toList().orEmpty()
        val caseIds = items.mapNotNull { it.path("case_id").takeIf(JsonNode::canConvertToLong)?.asLong() }
        val cases = caseReader.byIds(caseIds, CaseScope.Latest)
            .let { found -> caseReader.overlay(found.values.toList(), scope).associateBy { requireNotNull(it.id) } }
        return items.mapIndexed { index, item ->
            val caseId = item.path("case_id").takeIf(JsonNode::canConvertToLong)?.asLong()
            QaScenarioStepView(
                step = index + 1,
                title = item.path("action").asText(""),
                caseId = caseId?.toString(),
                expected = caseId?.let { cases[it]?.expectedValue },
            )
        }
    }

    /**
     * 스텝마다 한 행동을 종류별로 센다. agent 가 보낸 행(`AGENT_TO_ORCHE`)만 센다 — 같은 행동이 SDK 로 나간 행도
     * 남아 있어 둘 다 세면 두 배가 된다. 행동 이름은 agent 가 붙인 말(`Pressing Return`, `Clicking #…`)에서 읽는다.
     */
    private suspend fun actionsByStep(qaTryId: Long): Map<Int, List<QaStepAction>> =
        qaLogs.findAgentActions(qaTryId).toList()
            .mapNotNull { row ->
                val payload = payloadOf(row)
                val step = payload.path("step").takeIf { it.canConvertToInt() }?.asInt() ?: return@mapNotNull null
                step to actionOf(payload.path("message").asText(row.message.orEmpty()))
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, actions) ->
                actions.groupingBy { it }.eachCount().map { (key, count) -> QaStepAction(key.first, key.second, count) }
            }

    private fun actionOf(message: String): Pair<String, String?> = when {
        message.startsWith("Pressing ") -> "key" to message.removePrefix("Pressing ").trim()
        message.startsWith("Clicking") -> "click" to null
        message.startsWith("Dragging") -> "drag" to null
        else -> "other" to message.substringBefore(' ').ifBlank { null }
    }

    private fun verdictOf(
        row: QaLogEntity,
        payload: JsonNode,
        steps: JsonNode?,
        captures: Map<String, QaVerdictCapture>,
        calls: List<QaLogEntity>,
        actions: List<QaStepAction>,
        check: CodeCheck?,
    ): QaStepVerdict {
        val step = payload.path("step").asInt()
        val checks = payload.path("checks").takeIf { it.isArray }
        val evidence = payload.path("evidence").asText("").takeIf { it in EVIDENCE_STATES } ?: "none"
        return QaStepVerdict(
            logId = requireNotNull(row.id).toString(),
            step = step,
            stepTitle = steps?.path(step - 1)?.path("action")?.asText()?.takeIf { it.isNotBlank() },
            caseId = payload.path("case_id").takeIf { it.canConvertToLong() }?.asLong()?.toString(),
            isVerification = payload.path("is_verification").asBoolean(false),
            passed = payload.path("status").asText() == "COMPLETED",
            message = payload.path("message").asText(row.message.orEmpty()),
            reason = payload.path("reason").asText("").takeIf { it.isNotBlank() } ?: thoughtOf(row, payload, calls),
            evidence = evidence,
            checks = checks,
            captures = capturedIds(payload).mapNotNull(captures::get),
            actions = actions,
            judgedAt = row.createdAt,
            codeCheck = check?.let { found ->
                QaCodeCheck(
                    result = found.result.name,
                    items = found.items.map { QaCodeCheckItem(it.kind, it.target, it.expected, it.observed, it.result.name, it.note) },
                )
            },
            needsReview = needsReview(payload.path("is_verification").asBoolean(false), payload.path("status").asText() == "COMPLETED", check),
        )
    }

    /**
     * 근거 칸이 생기기 전 판정의 이유. 판정 STATUS 바로 앞의 같은 스텝(FREE 는 같은 TC) 판정 도구 호출에 모델이 쓴
     * `thought` 가 남아 있다 — 그래서 지난 실행도 "왜" 를 보인다.
     */
    private fun thoughtOf(row: QaLogEntity, payload: JsonNode, calls: List<QaLogEntity>): String? {
        val statusId = requireNotNull(row.id)
        val step = payload.path("step").asInt()
        val caseId = payload.path("case_id").takeIf { it.canConvertToLong() }?.asLong()
        return calls.lastOrNull { call ->
            val args = payloadOf(call).path("args")
            requireNotNull(call.id) < statusId &&
                (args.path("step").asInt(-1) == step || (caseId != null && args.path("case_id").asLong(-1) == caseId))
        }?.let { payloadOf(it).path("args").path("thought").asText("").takeIf(String::isNotBlank) }
    }

    private fun payloadOf(row: QaLogEntity): JsonNode = objectMapper.readTree(row.payload.asString())

    /** 판정이 근거로 댄 캡처 id. 기록과 맞은 것만 — 이 런에 없는 id 로 주소를 만들 수는 없다. */
    private fun capturedIds(payload: JsonNode): List<String> =
        payload.path("checks").flatMap { check -> check.path("sources") }
            .filter { it.path("kind").asText() == "capture" && it.path("citation").asText() == "match" }
            .mapNotNull { it.path("key").asText("").takeIf(String::isNotBlank) }
            .distinct()

    /** 캡처 기록(SCREENSHOT 행)의 저장 위치로 내려받는 주소를 새로 발급한다. 행에 있던 주소는 이미 만료됐을 수 있다. */
    private suspend fun captureLinks(qaTryId: Long, captureIds: Set<String>): Map<String, QaVerdictCapture> {
        if (captureIds.isEmpty()) return emptyMap()
        return qaLogs.findScreenshots(qaTryId, captureIds).toList().mapNotNull { row ->
            val captureId = row.messageId ?: return@mapNotNull null
            val objectKey = payloadOf(row).path("objectKey").asText("").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val download = storage.presignDownload(
                objectKey,
                objectKey.substringAfterLast('/'),
                storageProperties.captureDownloadUrlTtl,
            )
            captureId to QaVerdictCapture(captureId, download.url, download.expiresAt)
        }.toMap()
    }
}
