package kr.artel.orchestration.qa.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.verdict.CodeCheck
import kr.artel.orchestration.qa.verdict.QaVerdictCodeCheckService
import kr.artel.orchestration.qa.verdict.needsReview
import kr.artel.orchestration.testcase.service.TestCaseReader
import kr.artel.orchestration.testcase.service.TestCaseVerdictService
import kr.artel.orchestration.testrun.repository.TestRunRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 추가 검토 판정을 고르고, 사람이 판정을 확정한다(ARTEL-1254).
 *
 * 고르는 기준은 코드 검사의 [needsReview] 다. QA 실행이 끝나면 한 번 골라 남기고, 그 실행을 누군가 처음 열 때 빠진
 * 것을 다시 채운다(이 기능 전의 실행). 사람이 확정하면 그 TC 의 판정을 사람 결정으로 바꾼다 — AI 판정 원본은 qa_log 에
 * 그대로 있다.
 */
@Service
class QaVerdictReviewService(
    private val reviews: QaVerdictReviewRepository,
    private val qaRuns: QaRunRepository,
    private val qaTries: QaTryRepository,
    private val qaLogs: QaLogRepository,
    private val testRuns: TestRunRepository,
    private val caseReader: TestCaseReader,
    private val codeChecks: QaVerdictCodeCheckService,
    private val caseVerdicts: TestCaseVerdictService,
    private val projectChanges: ProjectChangeStreamManager,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(QaVerdictReviewService::class.java)

    /**
     * QA 실행이 끝났다. 추가 검토 판정을 골라 남기고 프로젝트에 알린다 — 화면은 이 알림을 받고 실행 목록을 다시 읽어
     * 끝 알림을 띄운다. 여기서 나가는 예외는 없다: 실행의 종단을 이 일이 막으면 안 된다.
     */
    suspend fun onRunFinished(qaRunId: Long) {
        try {
            val run = qaRuns.findById(qaRunId) ?: return
            flagRun(qaRunId, run.gameBuildId)
            testRuns.findById(run.testRunId)?.let { projectChanges.notify(it.projectId, ProjectChangeTopic.QA_RUNS) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("QA 실행 종료 뒤 추가 검토 고르기 실패 [qaRunId={}]: {}", qaRunId, error.message)
        }
    }

    /** 실행의 모든 시나리오에서 추가 검토 판정을 골라 남긴다. 이미 남긴 것은 그대로다. */
    suspend fun flagRun(qaRunId: Long, gameBuildId: Long?) {
        val judgedBuildId = caseReader.judgedBuildId(gameBuildId)
        for (qaTry in qaTries.findByQaRunId(qaRunId).toList()) {
            val qaTryId = requireNotNull(qaTry.id)
            val verdicts = qaLogs.findStepVerdicts(qaTryId).toList().map { it to payloadOf(it) }
            flag(qaRunId, qaTryId, verdicts, codeChecks.checkTry(qaTryId, judgedBuildId, verdicts))
        }
    }

    /**
     * 판정 근거 화면이 이미 계산한 결과로 고른다. 한 스텝을 여러 번 판정했으면(FREE) 마지막 판정만 본다 — 화면이
     * 그 스텝의 판정으로 보이는 것이 그것이다.
     */
    suspend fun flag(qaRunId: Long, qaTryId: Long, verdicts: List<Pair<QaLogEntity, JsonNode>>, checks: Map<Long, CodeCheck>) {
        val lastPerStep = verdicts.associateBy { (_, payload) -> payload.path("step").asInt() }.values
        for ((row, payload) in lastPerStep) {
            val logId = requireNotNull(row.id)
            val passed = passedOf(payload)
            if (!needsReview(payload.path("is_verification").asBoolean(false), passed, checks[logId])) continue
            reviews.flag(logId, qaRunId, qaTryId, caseIdOf(payload), passed)
        }
    }

    /**
     * 사람이 판정을 정한다. 그 판정이 TC 에 붙어 있으면 TC 판정도 사람 결정으로 바꾼다. 보이지 않는 실행이거나
     * 그 실행의 스텝 판정이 아니면 404 다.
     */
    suspend fun decide(qaRunId: Long, logId: Long, appUserId: Long, passed: Boolean, note: String?): QaVerdictReviewEntity {
        qaRuns.findAccessibleById(qaRunId, appUserId) ?: throw NotFoundException()
        val row = qaLogs.findById(logId) ?: throw NotFoundException()
        val qaTry = qaTries.findById(row.qaTryId)?.takeIf { it.qaRunId == qaRunId } ?: throw NotFoundException()
        val payload = payloadOf(row)
        if (row.type != "STATUS" || !payload.has("step") || payload.hasNonNull("result")) {
            throw BadRequestException("스텝 판정이 아닙니다.")
        }
        val caseId = caseIdOf(payload)
        val trimmed = note?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NOTE)
        reviews.decide(
            qaLogId = logId,
            qaRunId = qaRunId,
            qaTryId = row.qaTryId,
            testCaseId = caseId,
            aiPassed = passedOf(payload),
            decision = if (passed) PASSED else FAILED,
            note = trimmed,
            decidedBy = appUserId,
            decidedAt = Instant.now(clock),
        )
        // TC 판정은 사람 결정을 따른다. 판정 기록은 AI 판정과 같은 STATUS 행을 가리킨다.
        if (caseId != null) caseVerdicts.recordStepVerdict(qaTry, caseId, passed, logId)
        return reviews.findById(logId) ?: throw NotFoundException()
    }

    private fun payloadOf(row: QaLogEntity): JsonNode = objectMapper.readTree(row.payload.asString())

    private fun passedOf(payload: JsonNode) = payload.path("status").asText() == "COMPLETED"

    private fun caseIdOf(payload: JsonNode): Long? = payload.path("case_id").takeIf { it.canConvertToLong() }?.asLong()

    companion object {
        const val PASSED = "PASSED"
        const val FAILED = "FAILED"
        private const val MAX_NOTE = 2000
    }
}
