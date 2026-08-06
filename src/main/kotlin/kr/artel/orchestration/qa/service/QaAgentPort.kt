package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * @param runConfig what the Agent resolved this session to, or null from an Agent
 *   that does not report it. Kept rather than dropped because it — not the
 *   request — is what makes a finished run attributable: the request carries
 *   aliases ("newest prompt", "vision auto") whose meaning is settled on the
 *   Agent side and changes over time.
 */
data class QaAgentSession(val sessionId: String, val runConfig: JsonNode? = null)

/** 런 안의 시나리오 하나 = 자기 qa_try_id + 실행 본문(cases 포함). ids는 문자열(FE 64bit 정밀도 관례). */
data class QaAgentScenario(
    val qaTryId: String,
    val testScenarioId: String,
    val scenario: JsonNode
)

/**
 * Agent 세션 개설 컨텍스트. **런 단위**([qaRunId] + [scenarios])가 기본이고, 단일 시나리오
 * ([qaTryId]/[testScenarioId]/[scenario])는 하위호환 경로다 — Agent가 둘 다 받아 1-시나리오 런으로
 * 정규화한다(ARTEL-258).
 */
data class QaAgentSessionContext(
    val gameInstanceId: String,
    val model: String?,
    val language: String? = null,
    val promptVersion: String? = null,
    val reasoning: JsonNode? = null,
    val arch: JsonNode? = null,
    // 런 단위
    val qaRunId: String? = null,
    val scenarios: List<QaAgentScenario>? = null,
    // 단일(하위호환)
    val qaTryId: String? = null,
    val testScenarioId: String? = null,
    val scenario: JsonNode? = null
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
