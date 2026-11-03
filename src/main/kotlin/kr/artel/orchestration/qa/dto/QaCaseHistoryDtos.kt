package kr.artel.orchestration.qa.dto

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

data class QaCaseHistoryAttempt(
    val qaTryId: String,
    val testScenarioId: String,
    val gameBuildId: String?,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val caseSteps: List<Int>,
    val caseAttempts: List<QaCaseHistoryCaseAttempt>,
    val caseAttemptsTruncated: Boolean,
)

data class QaCaseHistoryCaseAttempt(
    val id: String,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val verdict: String?,
    val caseSnapshot: JsonNode,
    val snapshotOrigin: String,
    val originalReason: String?,
    val humanDecision: String?,
    val humanNote: String?,
)

data class QaCaseHistoryEvent(
    val id: String,
    val direction: String,
    val type: String,
    val scope: String,
    val message: String?,
    val payload: JsonNode,
    val createdAt: Instant,
)

data class QaCaseHistoryQuestion(
    val id: String,
    val question: String,
    val answer: String,
    val answeredBy: String?,
    val createdAt: Instant,
    val answeredAt: Instant,
)

data class QaCaseHistoryResponse(
    val testCaseId: String,
    val currentGameBuildId: String?,
    val attempts: List<QaCaseHistoryAttempt>,
    val nextBeforeTryId: String?,
    val selectedTryId: String?,
    val events: List<QaCaseHistoryEvent>,
    val nextAfterLogId: String?,
    val questions: List<QaCaseHistoryQuestion>,
    val nextAfterQuestionId: String?,
    val nextBeforeCaseAttemptId: String?,
)
