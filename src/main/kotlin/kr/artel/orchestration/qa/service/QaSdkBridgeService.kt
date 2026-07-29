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
