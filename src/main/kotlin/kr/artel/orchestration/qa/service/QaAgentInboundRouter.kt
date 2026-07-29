package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.entity.IssueSeverity
import kr.artel.orchestration.issue.service.IssueService
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.service.KnowledgeService
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

private val SUPPORTED_TYPES = setOf("LOG", "ACTION", "STATUS", "ERROR", "CHAT", "ISSUE", "KNOWLEDGE")

@Service
class QaAgentInboundRouter(
    private val tryRepository: QaTryRepository,
    private val logService: QaLogService,
    private val actionDispatch: QaActionDispatchService,
    private val streamManager: QaLogStreamManager,
    private val issueService: IssueService,
    private val knowledgeService: KnowledgeService,
    private val gameInstanceRepository: GameInstanceRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    suspend fun handle(envelope: QaAgentEnvelope) {
        // 파싱은 동기 throw 대신 값으로 검증한다. 프레임 하나가 throw하면 receive 파이프라인이
        // onError로 끊겨 WS가 닫히고, 그게 onDisconnect로 이어져 try 전체가 fail 처리된다.
        val qaTryId = parseId(envelope.qaTryId) ?: return
        if (!isUuid(envelope.messageId)) {
            appendError(qaTryId, envelope, "Agent messageId must be a UUID")
            return
        }
        if (envelope.type !in SUPPORTED_TYPES) {
            appendError(qaTryId, envelope, "Unsupported Agent message type: ${envelope.type}")
            return
        }
        // KNOWLEDGE carries a game_context list, not a single display message, so it is
        // routed before the message-required guard below (which every other type shares).
        if (envelope.type == "KNOWLEDGE") {
            val qaTry = activeTry(qaTryId) ?: return
            routeKnowledge(qaTryId, qaTry.gameInstanceId, envelope)
            return
        }
        // 이슈는 표시용 `message` 대신 `title`을 담는다. 나머지 타입은 모두 타임라인에 뜨는
        // 문구를 message에 싣는다. 아래 non-blank 가드가 곧 "이슈는 title 필수" 역할을 겸한다.
        val field = if (envelope.type == "ISSUE") "title" else "message"
        val message = envelope.payload.path(field).takeIf { it.isTextual }?.asText()
        if (message.isNullOrBlank()) {
            appendError(qaTryId, envelope, "${envelope.type} payload.$field is required")
            return
        }
        // A frame for an unknown/already-finished try is dropped, not raised: an error
        // here propagates out of the WebSocket receive chain, which closes the socket
        // and fails the whole run via onDisconnect.
        val qaTry = activeTry(qaTryId) ?: return
        when (envelope.type) {
            "ACTION" -> actionDispatch.dispatch(
                qaTryId,
                envelope.messageId,
                message,
                envelope.payload
            )
            "STATUS" -> routeStatus(qaTry.status, qaTryId, envelope, message)
            "ISSUE" -> routeIssue(qaTryId, envelope, message)
            else -> {
                val log = logService.append(
                    qaTryId = qaTryId,
                    direction = "AGENT_TO_ORCHE",
                    type = envelope.type,
                    messageId = envelope.messageId,
                    correlationId = envelope.correlationId,
                    message = message,
                    payload = envelope.payload
                )
                logService.publish(log)
            }
        }
    }

    private suspend fun activeTry(qaTryId: Long) =
        tryRepository.findById(qaTryId)?.takeIf { it.status == "STARTING" || it.status == "RUNNING" }

    private suspend fun routeStatus(
        currentStatus: String,
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        message: String
    ) {
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
        if (resolved == null) {
            val log = logService.append(
                qaTryId = qaTryId,
                direction = "AGENT_TO_ORCHE",
                type = "STATUS",
                messageId = envelope.messageId,
                correlationId = envelope.correlationId,
                message = message,
                payload = envelope.payload
            )
            logService.publish(log)
            return
        }
        val completedAt = Instant.now(clock)
        if (tryRepository.transition(qaTryId, currentStatus, resolved, completedAt, completedAt) != 1) {
            throw IllegalStateException("Illegal QA status transition")
        }
        val log = logService.append(
            qaTryId = qaTryId,
            direction = "AGENT_TO_ORCHE",
            type = "STATUS",
            messageId = envelope.messageId,
            correlationId = envelope.correlationId,
            message = message,
            // Keep the agent's rich terminal payload (result, summary) but stamp
            // the resolved try status + completion time for downstream readers.
            payload = (envelope.payload.deepCopy() as ObjectNode)
                .put("status", resolved)
                .put("completedAt", completedAt.toString())
        )
        logService.publish(log)
        streamManager.complete(qaTryId)
    }

    /**
     * QA 실행 중 Agent가 보낸 knowledge 배치를 knowledge 도메인에 저장한다(qa_log 아님).
     *
     * payload는 인입 구조({source, metadata, game_context[]})다. source는 런에서 왔으므로 QA로
     * 고정하고, source_id=qa_try.id, project_id는 게임 인스턴스에서 도출한다. 파싱/빈 배열/저장 실패는
     * throw하지 않고 ORCHE_INTERNAL 오류 로그로 떨어뜨려(런은 실패 처리 안 함) receive 체인을 끊지 않는다.
     */
    private suspend fun routeKnowledge(
        qaTryId: Long,
        gameInstanceId: Long,
        envelope: QaAgentEnvelope
    ) {
        val request = try {
            objectMapper.treeToValue(envelope.payload, KnowledgeIngestRequest::class.java)
        } catch (error: Exception) {
            appendError(qaTryId, envelope, "KNOWLEDGE payload parse failed: ${error.message}")
            return
        }
        if (request.gameContext.isEmpty()) {
            appendError(qaTryId, envelope, "KNOWLEDGE payload.game_context is required")
            return
        }
        try {
            val instance = gameInstanceRepository.findById(gameInstanceId) ?: return
            knowledgeService.store(
                projectId = instance.projectId,
                source = KnowledgeSource.QA,
                sourceId = qaTryId,
                contentHash = request.metadata?.hash,
                items = request.gameContext
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendError(qaTryId, envelope, "KNOWLEDGE store failed: ${error.message}")
        }
    }

    /**
     * Agent가 보고한 이슈를 issue 도메인에 저장한다(qa_log가 아니다).
     *
     * severity는 다른 모든 envelope 필드와 똑같이 여기서 값으로 검증한다: 잘못된 값은 throw
     * 대신 ORCHE_INTERNAL 에러로 드롭해, 프레임 하나가 receive 체인을 끊어 실행을 실패시키지
     * 못하게 한다. `title`은 [handle]의 non-blank 가드에서 이미 필수로 걸렀다.
     */
    private suspend fun routeIssue(
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        title: String
    ) {
        val severity = envelope.payload.path("severity").takeIf { it.isTextual }?.asText()
        if (severity == null || severity !in IssueSeverity.NAMES) {
            appendError(
                qaTryId,
                envelope,
                "ISSUE payload.severity must be one of ${IssueSeverity.NAMES}"
            )
            return
        }
        issueService.recordAgentIssue(
            qaTryId = qaTryId,
            messageId = envelope.messageId,
            correlationId = envelope.correlationId,
            severity = severity,
            title = title,
            reportedAt = envelope.timestamp,
            payload = envelope.payload
        )
    }

    private suspend fun appendError(
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        reason: String
    ) {
        val log = logService.append(
            qaTryId = qaTryId,
            direction = "ORCHE_INTERNAL",
            type = "ERROR",
            correlationId = envelope.messageId,
            message = reason,
            payload = objectMapper.createObjectNode().put("reason", reason)
        )
        logService.publish(log)
    }

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
