package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

data class QaAgentSession(val sessionId: String)

data class QaAgentSessionContext(
    val qaTryId: String,
    val gameInstanceId: String,
    val testScenarioId: String,
    val scenario: JsonNode,
    val model: String?,
    val reasoning: JsonNode?
)

data class QaAgentEnvelope(
    val messageId: String,
    val type: String,
    val qaTryId: String,
    val correlationId: String? = null,
    val sequence: Long? = null,
    val timestamp: Instant,
    val payload: JsonNode
)

interface QaAgentPort {
    suspend fun createSession(
        context: QaAgentSessionContext,
        onMessage: suspend (QaAgentEnvelope) -> Unit,
        onDisconnect: suspend () -> Unit
    ): QaAgentSession

    suspend fun send(sessionId: String, envelope: QaAgentEnvelope)
    suspend fun close(sessionId: String)
}

class QaAgentUnavailableException(message: String) : RuntimeException(message)
