package kr.artel.orchestration.qa.dto

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

data class CreateQaTryRequest(
    val testScenarioId: String,
    val gameInstanceId: String
)

data class QaTryResponse(
    val id: String,
    val testScenarioId: String,
    val gameInstanceId: String,
    val startedBy: String,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?
)

data class QaLogResponse(
    val id: String,
    val qaTryId: String,
    val messageId: String?,
    val correlationId: String?,
    val direction: String,
    val type: String,
    val message: String?,
    val payload: JsonNode,
    val createdAt: Instant
)

data class QaLogPageResponse(
    val items: List<QaLogResponse>,
    val nextBeforeId: String?,
    val hasMore: Boolean
)

data class QaStatusPayload(
    val status: String,
    val completedAt: Instant?
)
