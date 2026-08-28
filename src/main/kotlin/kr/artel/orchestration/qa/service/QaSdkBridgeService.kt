package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.contentmap.observe.ScreenSelectorFrames
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
    private val actionObservations: QaActionObservationPort,
    private val objectMapper: ObjectMapper
) : QaScreenFramePort {
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
        // 관측 타임라인은 **성공한 액션만** 받는다 (ARTEL-450). 여기가 그 성공/거절을 아는 유일한
        // 자리다 — 아래 중계는 결과를 읽지 않고 옮기기만 한다. 답을 못 읽으면 아무 말도 하지
        // 않는다: 못 읽은 것을 거절로 세면 그 컨트롤의 `attempts` 가 근거 없이 오른다.
        succeededIn(payload)?.let { actionObservations.settled(gameInstanceId, outerId, it) }
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

    /**
     * 이 `ACTION_RESULT` 가 액션이 **전부** 성공했다고 말하나 (ARTEL-450). 모르면 null 이다.
     *
     * SDK 는 결과를 배열로 싣는다 — `{"requestId":10,"results":[{"id":1,"success":true,"error":""}]}`.
     * 하나라도 거절당했으면 그 조작은 게임에 닿지 못한 것이므로 묶음 전체를 실패로 읽는다.
     *
     * 배열이 아니거나 비어 있으면 **판단하지 않는다.** 답의 모양을 모르는 채로 성공이라고 읽으면
     * 닿지도 못한 조작의 관측이 생기고, 실패라고 읽으면 멀쩡한 조작의 `attempts` 가 오른다.
     */
    private fun succeededIn(payload: JsonNode): Boolean? {
        val results = payload.path("results")
        if (!results.isArray || results.isEmpty) return null
        return results.all { it.path("success").asBoolean(false) }
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

    /**
     * 화면 판정 목록 제안을 agent 로 보낸다 (ARTEL-655).
     *
     * SDK 가 보낸 프레임을 옮기는 위 셋과 달리 **이쪽은 orchestration 이 만든 프레임**이다. 그래도
     * 같은 자리에 두는 것은 계기가 같기 때문이다 — 이 제안은 방금 도착한 `pulse` 를 보고 나가고,
     * 로그 방향(`ORCHE_TO_AGENT`)도 봉투 모양도 위 셋과 같다. 배관을 따로 내면 그 셋만 아는
     * 규칙(세션이 아직 없을 때 건너뛰기, 전달 실패를 ERROR 로그로 남기기)이 두 벌이 된다.
     *
     * [sendAgent] 와 달리 전달 실패를 **다시 던지지 않는다.** 저쪽은 `pulse` 중계 경로라 실패가
     * 곧 중계 실패이지만, 제안은 관측의 곁가지라 실패해도 런은 그대로 흘러야 한다.
     */
    override suspend fun sendScreenSelectorProposal(
        gameInstanceId: Long,
        messageId: String,
        summary: String,
        payload: JsonNode
    ): Boolean = sendScreenFrame(gameInstanceId, ScreenSelectorFrames.PROPOSAL, messageId, summary, payload)

    /**
     * 관측이 확정한 화면을 agent 로 알린다 (ARTEL-668).
     *
     * 제안과 같은 배관을 타지만 **`correlationId` 를 안 싣는다.** 저쪽에서 답을 기다리는 tool 이
     * 없는데 correlation 을 달면, 마침 같은 값을 기다리던 요청이 이 통보로 풀린다.
     */
    override suspend fun sendScreenSettled(
        gameInstanceId: Long,
        messageId: String,
        summary: String,
        payload: JsonNode
    ): Boolean = sendScreenFrame(
        gameInstanceId, ScreenSelectorFrames.SETTLED, messageId, summary, payload, correlate = false
    )

    /**
     * 화면 프레임 하나를 로그에 남기고 agent 로 보낸다.
     *
     * 활성 try 가 없거나 세션이 아직 안 붙었으면 **로그도 남기지 않고** `false` 다. 보낼 곳이
     * 없는 프레임의 타임라인 행은 "나갔다" 로 읽히고, 그것이 안 나간 이유를 찾는 사람을 속인다.
     */
    private suspend fun sendScreenFrame(
        gameInstanceId: Long,
        type: String,
        messageId: String,
        summary: String,
        payload: JsonNode,
        correlate: Boolean = true
    ): Boolean {
        val qaTry = tryRepository.findActiveByGameInstanceId(gameInstanceId) ?: return false
        val sessionId = qaTry.agentSessionId ?: return false
        val qaTryId = requireNotNull(qaTry.id)
        val outbound = logService.append(
            qaTryId = qaTryId,
            direction = "ORCHE_TO_AGENT",
            type = type,
            messageId = messageId,
            message = summary,
            payload = payload
        )
        logService.publish(outbound)
        sendAgent(qaTryId, sessionId, type, if (correlate) messageId else null, payload, messageId)
        return true
    }

    private suspend fun sendAgent(
        qaTryId: Long,
        sessionId: String,
        type: String,
        correlationId: String?,
        payload: JsonNode,
        messageId: String = UUID.randomUUID().toString()
    ) {
        try {
            agentPort.send(
                sessionId,
                QaAgentEnvelope(
                    messageId = messageId,
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
            if (type in OBSERVATION_FRAMES) return
            throw error
        }
    }

    private companion object {
        /**
         * 전달 실패를 삼키는 타입.
         *
         * SDK 를 중계하는 프레임(`GAME_STATE` · `PULSE` · `ACTION_RESULT`)은 전달 실패가 곧 중계
         * 실패라 던져야 하지만, 이 둘은 관측의 곁가지다. 목록을 물어보거나 확정한 화면을 알리다
         * 실패했다고 `pulse` 중계까지 끊기면, 화면을 못 만드는 게임에서 QA 가 통째로 눈을 잃는다.
         */
        val OBSERVATION_FRAMES = setOf(ScreenSelectorFrames.PROPOSAL, ScreenSelectorFrames.SETTLED)
    }
}
