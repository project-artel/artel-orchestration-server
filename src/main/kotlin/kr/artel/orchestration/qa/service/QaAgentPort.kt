package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.annotation.JsonProperty
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
 * 사용자 key 로 도는 run 에서 agent-server 에 실어 보내는 key. 세션 개설 본문의 `llm_key` 다.
 *
 * agent-server 는 이 key 를 그 세션 동안만 쥐고 `run_config` · 로그 · 응답에 넣지 않는다. 이쪽도 같다 —
 * [toString] 이 [apiKey] 를 빼는 이유는 WebFlux 의 Jackson encoder 가 DEBUG 로그에 보낸 객체의 `toString` 을
 * 남기기 때문이다.
 *
 * @property service `anthropic`, `openai`, `openrouter`.
 */
data class QaSessionLlmKey(
    val service: String,
    @JsonProperty("api_key") val apiKey: String
) {
    override fun toString(): String = "QaSessionLlmKey(service=$service)"
}

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
    // 사용자 key 로 도는 run 에만 있다. 없으면 agent-server 는 공용 key 를 쓴다.
    val llmKey: QaSessionLlmKey? = null,
    // 이 런에 열어 준 cheat · behavior 항목(ARTEL-1053). 카탈로그에서 run 옵션으로 이미 거른 것이다. 비어 있으면 세션 개설
    // 본문에 싣지 않는다.
    val cheats: List<JsonNode>? = null,
    val behaviors: List<JsonNode>? = null,
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
