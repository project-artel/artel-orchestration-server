package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.dto.AgentGameState
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Routes SDK-originated messages only when the instance has an active QA try.
 * `false` means a successful lookup found no active QA and legacy routing may
 * run. Errors are deliberately propagated and must never trigger legacy.
 */
@Service
class QaSdkBridgeService(
    private val tryRepository: QaTryRepository,
    private val logRepository: QaLogRepository,
    private val logService: QaLogService,
    private val agentPort: QaAgentPort,
    private val objectMapper: ObjectMapper
) {
    suspend fun routeGameState(gameInstanceId: Long, sdkMessageId: String, state: AgentGameState): Boolean {
        val qaTry = tryRepository.findActiveByGameInstanceId(gameInstanceId) ?: return false
        val qaTryId = requireNotNull(qaTry.id)
        // STARTING 상태의 try도 findActiveByGameInstanceId에 잡힌다. 그때 agentSessionId는
        // 아직 null이므로 evidence만 남기고 Agent 전달은 건너뛴다(붙기 전에는 보낼 곳이 없다).
        val sessionId = qaTry.agentSessionId
        val payload = objectMapper.valueToTree<JsonNode>(state)
        val inbound = logService.append(
            qaTryId = qaTryId,
            direction = "SDK_TO_ORCHE",
            type = "GAME_STATE",
            messageId = sdkMessageId,
            message = "Game state received.",
            payload = payload
        )
        logService.publish(inbound)
        if (!inbound.inserted) return true
        if (sessionId == null) return true
        val outbound = logService.append(
            qaTryId = qaTryId,
            direction = "ORCHE_TO_AGENT",
            type = "GAME_STATE",
            messageId = sdkMessageId,
            message = "Game state updated.",
            payload = payload
        )
        logService.publish(outbound)
        sendAgent(qaTryId, sessionId, "GAME_STATE", sdkMessageId, payload)
        return true
    }

    /**
     * 판독을 받은 그대로 agent 로 흘린다.
     *
     * **이 메서드의 전부는 옮기는 것이다.** 파싱은 `jsonb` 컬럼에 넣고 봉투에 실으려는 것뿐이고,
     * 필드를 읽어 판단하지 않는다 — 그래서 문서의 모양이 바뀌어도 여기는 따라 움직이지 않는다.
     * `coding-style.md` 의 `Data Shapes` 가 DTO 대신 raw JSON 을 허용하는 첫 번째 경우다:
     * 필드를 읽지 않고 저장하거나 전달하기만 하는 통과 경로.
     *
     * [routeGameState] 와 달리 정제기를 거치지 않는다. 이유는 [PulseMessageHandler] 에 적었다.
     *
     * `messageId` 는 있으면 쓰고 없으면 비운다. 그것이 채워지면 같은 판독의 재전송이 유니크에
     * 걸려 한 번만 적재되고, 비면 그 보호가 없을 뿐 나머지는 그대로 동작한다 — 어느 쪽을 보낼지는
     * 보내는 쪽(ARTEL-399)이 정한다.
     */
    suspend fun routePulse(gameInstanceId: Long, payloadText: String): Boolean {
        val qaTry = tryRepository.findActiveByGameInstanceId(gameInstanceId) ?: return false
        val payload = objectMapper.readTree(payloadText)
        val qaTryId = requireNotNull(qaTry.id)
        // STARTING 상태의 try 도 findActiveByGameInstanceId 에 잡힌다. 그때 agentSessionId 는
        // 아직 null 이므로 로그만 남기고 전달은 건너뛴다(붙기 전에는 보낼 곳이 없다).
        val sessionId = qaTry.agentSessionId
        val messageId = payload.path("id").takeIf { it.isIntegralNumber }?.longValue()?.toString()
        val inbound = logService.append(
            qaTryId = qaTryId,
            direction = "SDK_TO_ORCHE",
            type = "PULSE",
            messageId = messageId,
            message = "Pulse received.",
            payload = payload
        )
        logService.publish(inbound)
        if (!inbound.inserted) return true
        if (sessionId == null) return true
        val outbound = logService.append(
            qaTryId = qaTryId,
            direction = "ORCHE_TO_AGENT",
            type = "PULSE",
            messageId = messageId,
            message = "Pulse relayed.",
            payload = payload
        )
        logService.publish(outbound)
        sendAgent(qaTryId, sessionId, "PULSE", messageId, payload)
        return true
    }

    suspend fun routeActionResult(gameInstanceId: Long, payloadText: String): Boolean {
        val qaTry = tryRepository.findActiveByGameInstanceId(gameInstanceId) ?: return false
        val payload = objectMapper.readTree(payloadText)
        // `requestId` echoes the ACTION this answers. `id` is the SDK's own
        // outgoing message number and shares no sequence with our ids, so
        // matching on it found nothing — every result read as unknown.
        // Still read as a fallback for SDKs built before the echo existed.
        val outerId = payload.path("requestId").takeIf { it.isIntegralNumber }?.longValue()
            ?: payload.path("id").takeIf { it.isIntegralNumber }?.longValue()
            ?: throw IllegalArgumentException("ACTION_RESULT needs an integer requestId or id")
        val outerIdString = outerId.toString()
        val qaTryId = requireNotNull(qaTry.id)
        val sessionId = qaTry.agentSessionId
        val action = logRepository.findByQaTryIdAndDirectionAndMessageId(
            qaTryId,
            "ORCHE_TO_SDK",
            outerIdString
        ) ?: return appendUnknownResult(qaTryId, outerIdString, payload)
        if (action.type != "ACTION") {
            return appendUnknownResult(qaTryId, outerIdString, payload)
        }
        val correlationId = action.correlationId
        val inbound = logService.append(
            qaTryId = qaTryId,
            direction = "SDK_TO_ORCHE",
            type = "ACTION_RESULT",
            messageId = outerIdString,
            correlationId = correlationId,
            message = "Action result received.",
            payload = payload
        )
        logService.publish(inbound)
        if (!inbound.inserted) return true
        if (sessionId == null) return true
        val outbound = logService.append(
            qaTryId = qaTryId,
            direction = "ORCHE_TO_AGENT",
            type = "ACTION_RESULT",
            messageId = outerIdString,
            correlationId = correlationId,
            message = "Action result is available.",
            payload = payload
        )
        logService.publish(outbound)
        sendAgent(qaTryId, sessionId, "ACTION_RESULT", correlationId, payload)
        return true
    }

    private suspend fun appendUnknownResult(
        qaTryId: Long,
        outerId: String,
        payload: JsonNode
    ): Boolean {
        val log = logService.append(
            qaTryId = qaTryId,
            direction = "ORCHE_INTERNAL",
            type = "ERROR",
            messageId = "unknown-action-result-$outerId",
            correlationId = outerId,
            message = "ACTION_RESULT references an unknown action.",
            payload = payload
        )
        logService.publish(log)
        return true
    }

    private suspend fun sendAgent(
        qaTryId: Long,
        sessionId: String,
        type: String,
        correlationId: String?,
        payload: JsonNode
    ) {
        try {
            agentPort.send(
                sessionId,
                QaAgentEnvelope(
                    messageId = UUID.randomUUID().toString(),
                    type = type,
                    qaTryId = qaTryId.toString(),
                    correlationId = correlationId,
                    timestamp = Instant.now(),
                    payload = payload
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val log = logService.append(
                qaTryId = qaTryId,
                direction = "ORCHE_INTERNAL",
                type = "ERROR",
                correlationId = correlationId,
                message = "QA Agent message delivery failed.",
                payload = objectMapper.createObjectNode().put("error", error.message)
            )
            logService.publish(log)
            throw error
        }
    }
}
