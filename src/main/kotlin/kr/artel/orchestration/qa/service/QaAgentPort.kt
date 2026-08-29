package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import kr.artel.orchestration.testscenario.dto.AgentScenario
import java.time.Instant

/**
 * @param runConfig what the Agent resolved this session to, or null from an Agent
 *   that does not report it. Kept rather than dropped because it — not the
 *   request — is what makes a finished run attributable: the request carries
 *   aliases ("newest prompt", "vision auto") whose meaning is settled on the
 *   Agent side and changes over time.
 */
data class QaAgentSession(val sessionId: String, val runConfig: JsonNode? = null)

/** 런 안의 시나리오 하나 = 자기 qa_try_id + 실행 본문([AgentScenario], cases 포함). ids는 문자열(FE 64bit 정밀도 관례). */
data class QaAgentScenario(
    val qaTryId: String,
    val testScenarioId: String,
    val scenario: AgentScenario
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
    // Agent 가 런 시작에 이 빌드의 scene context 를 한 번 조회하는 데 쓰는 두 값 (ARTEL-676).
    // 둘 다 있어야 조회가 성립한다 — 경로가 `/internal/projects/{id}/game-builds/{id}/scene-context`
    // 라서 한쪽만으로는 부를 수 없다. `gameBuildId` 는 인스턴스가 마지막 등록에서 보고한 빌드라
    // 아직 SDK 가 붙은 적 없는 인스턴스에서는 비고, 그때는 조회 없이 런이 그대로 돈다.
    val projectId: String? = null,
    val gameBuildId: String? = null,
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
