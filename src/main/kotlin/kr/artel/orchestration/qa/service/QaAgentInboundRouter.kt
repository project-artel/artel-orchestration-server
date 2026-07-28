package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.artel.orchestration.issue.entity.IssueSeverity
import kr.artel.orchestration.issue.service.IssueService
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.service.SessionManager
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val SUPPORTED_TYPES = setOf("LOG", "ACTION", "STATUS", "ERROR", "CHAT", "REQUEST_GAME_STATE", "ISSUE")

/** The SDK answers this with a GAME_STATE message on the same socket. */
private val GET_GAME_STATE = mapOf("type" to "GET_GAME_STATE")

/** A wait the Agent asks for is bounded here; it is a hint, not a lease. */
private val MAX_SCENE_WAIT: Duration = Duration.ofSeconds(60)

@Service
class QaAgentInboundRouter(
    private val tryRepository: QaTryRepository,
    private val logService: QaLogService,
    private val actionDispatch: QaActionDispatchService,
    private val streamManager: QaLogStreamManager,
    private val sessionManager: SessionManager,
    private val issueService: IssueService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    fun handle(envelope: QaAgentEnvelope): Mono<Void> {
        // 파싱은 동기 throw 대신 값으로 검증한다. 프레임 하나가 throw하면 receive 파이프라인이
        // onError로 끊겨 WS가 닫히고, 그게 onDisconnect로 이어져 try 전체가 fail 처리된다.
        val qaTryId = parseId(envelope.qaTryId) ?: return Mono.empty()
        if (!isUuid(envelope.messageId)) {
            return appendError(qaTryId, envelope, "Agent messageId must be a UUID")
        }
        if (envelope.type !in SUPPORTED_TYPES) {
            return appendError(qaTryId, envelope, "Unsupported Agent message type: ${envelope.type}")
        }
        // 씬 요청은 표시용 `message` 대신 `reason`을, 이슈는 `title`을 담는다. 나머지 타입은
        // 모두 타임라인에 뜨는 문구를 message에 싣는다. 아래 non-blank 가드가 곧 "이슈는 title
        // 필수" 역할을 겸한다.
        val field = when (envelope.type) {
            "REQUEST_GAME_STATE" -> "reason"
            "ISSUE" -> "title"
            else -> "message"
        }
        val message = envelope.payload.path(field).takeIf { it.isTextual }?.asText()
        if (message.isNullOrBlank()) {
            return appendError(qaTryId, envelope, "${envelope.type} payload.$field is required")
        }
        // A frame for an unknown/already-finished try is dropped, not raised: an error
        // here propagates out of the WebSocket receive chain, which closes the socket
        // and fails the whole run via onDisconnect.
        return tryRepository.findById(qaTryId)
            .filter { it.status == "STARTING" || it.status == "RUNNING" }
            .flatMap { qaTry ->
                when (envelope.type) {
                    "ACTION" -> actionDispatch.dispatch(
                        qaTryId,
                        envelope.messageId,
                        message,
                        envelope.payload
                    ).then()
                    "STATUS" -> routeStatus(qaTry.status, qaTryId, envelope, message)
                    "REQUEST_GAME_STATE" -> requestGameState(
                        qaTryId,
                        qaTry.gameInstanceId,
                        envelope,
                        message
                    )
                    "ISSUE" -> routeIssue(qaTryId, envelope, message)
                    else -> logService.append(
                        qaTryId = qaTryId,
                        direction = "AGENT_TO_ORCHE",
                        type = envelope.type,
                        messageId = envelope.messageId,
                        correlationId = envelope.correlationId,
                        message = message,
                        payload = envelope.payload
                    ).doOnNext(logService::publish).then()
                }
            }
    }

    /**
     * Asks the game for its current scene on the Agent's behalf, after the wait
     * it asked for.
     *
     * The delay is scheduled here rather than slept in the Agent: the Agent runs
     * one session on one receive loop, so sleeping there would stall the
     * operator's messages too. Delivery is best effort — a disconnected SDK must
     * not fail the run, and the Agent will ask again on its next turn.
     */
    private fun requestGameState(
        qaTryId: Long,
        gameInstanceId: Long,
        envelope: QaAgentEnvelope,
        reason: String
    ): Mono<Void> {
        val requested = envelope.payload.path("after_seconds").takeIf { it.isNumber }?.asDouble() ?: 0.0
        val wait = Duration.ofMillis((requested.coerceAtLeast(0.0) * 1000).toLong())
            .coerceAtMost(MAX_SCENE_WAIT)
        return logService.append(
            qaTryId = qaTryId,
            direction = "ORCHE_TO_SDK",
            type = "GAME_STATE",
            messageId = envelope.messageId,
            message = reason,
            payload = envelope.payload
        ).doOnNext(logService::publish)
            .then(
                Mono.delay(wait)
                    .then(sessionManager.send(gameInstanceId.toString(), GET_GAME_STATE))
                    .onErrorResume { error ->
                        logService.append(
                            qaTryId = qaTryId,
                            direction = "ORCHE_INTERNAL",
                            type = "ERROR",
                            correlationId = envelope.messageId,
                            message = "Game state request could not be delivered.",
                            payload = objectMapper.createObjectNode().put("error", error.message)
                        ).doOnNext(logService::publish).then()
                    }
                    // Detached: the caller is the Agent's receive loop, and holding
                    // it for the wait would block every other frame of this run.
                    .subscribe()
                    .let { Mono.empty() }
            )
    }

    private fun routeStatus(
        currentStatus: String,
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        message: String
    ): Mono<Void> {
        // Agent STATUS is 2-scope: per-step frames reuse COMPLETED/FAILED for the step's
        // own verdict and carry result=null — they must NOT end the run. Only a
        // run-terminal frame carries result PASSED|FAILED, and CANCELLED is always
        // terminal. Key on result, never on the status word alone.
        val status = envelope.payload.path("status").takeIf { it.isTextual }?.asText()
        val result = envelope.payload.path("result").takeIf { it.isTextual }?.asText()
        val resolved = when {
            status == "CANCELLED" -> "CANCELLED"
            result == "PASSED" -> "COMPLETED"
            result == "FAILED" -> "FAILED"
            else -> null
        }
        val completedAt = if (resolved != null) Instant.now(clock) else null
        val transition = if (resolved != null) {
            tryRepository.transition(qaTryId, currentStatus, resolved, completedAt, completedAt!!)
                .filter { it == 1 }
                .switchIfEmpty(Mono.error(IllegalStateException("Illegal QA status transition")))
                .then()
        } else Mono.empty()
        return transition.then(
            logService.append(
                qaTryId = qaTryId,
                direction = "AGENT_TO_ORCHE",
                type = "STATUS",
                messageId = envelope.messageId,
                correlationId = envelope.correlationId,
                message = message,
                payload = if (resolved != null) {
                    // Keep the agent's rich terminal payload (result, summary) but stamp
                    // the resolved try status + completion time for downstream readers.
                    (envelope.payload.deepCopy() as ObjectNode)
                        .put("status", resolved)
                        .put("completedAt", completedAt.toString())
                } else envelope.payload
            )
        ).doOnNext {
            logService.publish(it)
            if (resolved != null) streamManager.complete(qaTryId)
        }.then()
    }

    /**
     * Agent가 보고한 이슈를 issue 도메인에 저장한다(qa_log가 아니다).
     *
     * severity는 다른 모든 envelope 필드와 똑같이 여기서 값으로 검증한다: 잘못된 값은 throw
     * 대신 ORCHE_INTERNAL 에러로 드롭해, 프레임 하나가 receive 체인을 끊어 실행을 실패시키지
     * 못하게 한다. `title`은 [handle]의 non-blank 가드에서 이미 필수로 걸렀다.
     */
    private fun routeIssue(
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        title: String
    ): Mono<Void> {
        val severity = envelope.payload.path("severity").takeIf { it.isTextual }?.asText()
        if (severity == null || severity !in IssueSeverity.NAMES) {
            return appendError(
                qaTryId,
                envelope,
                "ISSUE payload.severity must be one of ${IssueSeverity.NAMES}"
            )
        }
        return issueService.recordAgentIssue(
            qaTryId = qaTryId,
            messageId = envelope.messageId,
            correlationId = envelope.correlationId,
            severity = severity,
            title = title,
            reportedAt = envelope.timestamp,
            payload = envelope.payload
        )
    }

    private fun appendError(
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        reason: String
    ): Mono<Void> =
        logService.append(
            qaTryId = qaTryId,
            direction = "ORCHE_INTERNAL",
            type = "ERROR",
            correlationId = envelope.messageId,
            message = reason,
            payload = objectMapper.createObjectNode().put("reason", reason)
        ).doOnNext(logService::publish).then()

    private fun parseId(value: String): Long? =
        value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()

    private fun isUuid(value: String): Boolean =
        try {
            UUID.fromString(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
}
