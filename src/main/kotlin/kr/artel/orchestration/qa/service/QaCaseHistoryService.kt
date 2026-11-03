package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.qa.dto.QaCaseHistoryAttempt
import kr.artel.orchestration.qa.dto.QaCaseHistoryEvent
import kr.artel.orchestration.qa.dto.QaCaseHistoryResponse
import kr.artel.orchestration.qa.repository.QaCaseHistoryRepository
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class QaCaseHistoryService(
    private val history: QaCaseHistoryRepository,
    private val tries: QaTryRepository,
    private val scenarios: TestScenarioRepository,
    private val testCases: TestCaseRepository,
    private val logs: QaLogRepository,
    private val runs: QaRunRepository,
    private val objectMapper: ObjectMapper,
) {
    suspend fun read(
        currentTryId: Long,
        caseId: Long,
        previousTryId: Long? = null,
        beforeTryId: Long? = null,
        afterLogId: Long = 0,
        limit: Int = 12,
        afterQuestionId: UUID? = null,
        beforeCaseAttemptId: UUID? = null,
    ): QaCaseHistoryResponse {
        if (limit !in 1..25 || afterLogId < 0 ||
            ((afterLogId > 0 || afterQuestionId != null || beforeCaseAttemptId != null) && previousTryId == null)) {
            throw BadRequestException("로그 cursor에는 이전 실행 id와 유효한 조회 범위가 필요합니다.")
        }
        val current = tries.findById(currentTryId) ?: throw NotFoundException()
        val scenario = scenarios.findById(current.testScenarioId) ?: throw NotFoundException()
        val testCase = testCases.findById(caseId)?.takeIf { it.projectId == scenario.projectId }
            ?: throw NotFoundException()
        val selected = history.attempts(current, testCase.projectId, caseId, beforeTryId, previousTryId, 6)
        if (previousTryId != null && selected.isEmpty()) throw NotFoundException()
        val attempts = selected.take(5).mapIndexed { index, attempt ->
            val caseAttempts = if (index == 0) history.caseAttempts(attempt.qaTryId, caseId, current.startedAt, beforeCaseAttemptId) else emptyList()
            val caseSteps = logs.findStepVerdicts(attempt.qaTryId).toList().map { log ->
                objectMapper.readValue(log.payload.asString(), HistoryLogMetadata::class.java)
            }.filter { it.caseId == caseId }.mapNotNull { it.step }.distinct().sorted()
            QaCaseHistoryAttempt(
                qaTryId = attempt.qaTryId.toString(),
                testScenarioId = attempt.testScenarioId.toString(),
                gameBuildId = attempt.gameBuildId?.toString(),
                status = attempt.status,
                startedAt = attempt.startedAt,
                completedAt = attempt.completedAt,
                caseSteps = caseSteps,
                caseAttempts = caseAttempts.take(3),
                caseAttemptsTruncated = caseAttempts.size > 3,
            )
        }
        val chosen = attempts.firstOrNull()
        val selectedQuestions = chosen?.let { history.questions(it.qaTryId.toLong(), afterQuestionId, current.startedAt) }.orEmpty()
        val selectedEvents = chosen?.let { history.events(it.qaTryId.toLong(), caseId, current.startedAt, afterLogId, limit + 1) }.orEmpty()
        val events = selectedEvents.take(limit).map { event ->
            val log = event.log
            val metadata = objectMapper.readValue(log.payload.asString(), HistoryLogMetadata::class.java)
            val belongsToCase = if (metadata.caseId != null) metadata.caseId == caseId
                else metadata.step in chosen?.caseSteps.orEmpty() || event.inCaseAttempt
            QaCaseHistoryEvent(
                id = requireNotNull(log.id).toString(),
                direction = log.direction,
                type = log.type,
                // CHAT에는 TC 연결 정보가 없다. 다른 TC의 지시도 실행 전체의 참고로만 읽는다.
                scope = if (log.type != "CHAT" && belongsToCase) "case" else "run",
                message = log.message,
                payload = objectMapper.readTree(log.payload.asString()),
                createdAt = requireNotNull(log.createdAt),
            )
        }
        return QaCaseHistoryResponse(
            testCaseId = caseId.toString(),
            currentGameBuildId = current.qaRunId?.let { runs.findById(it)?.gameBuildId?.toString() },
            attempts = attempts,
            nextBeforeTryId = attempts.lastOrNull()?.qaTryId?.takeIf { selected.size > 5 },
            selectedTryId = chosen?.qaTryId,
            events = events,
            nextAfterLogId = events.lastOrNull()?.id?.takeIf { selectedEvents.size > limit },
            questions = selectedQuestions.take(2),
            nextAfterQuestionId = selectedQuestions.getOrNull(1)?.id?.takeIf { selectedQuestions.size > 2 },
            nextBeforeCaseAttemptId = chosen?.caseAttempts?.lastOrNull()?.id?.takeIf { chosen.caseAttemptsTruncated },
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class HistoryLogMetadata(
    @JsonProperty("case_id") val caseId: Long? = null,
    val step: Int? = null,
)
