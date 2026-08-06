package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.entity.IssueSeverity
import kr.artel.orchestration.issue.service.IssueService
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeMutationRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeSearchRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.knowledge.service.KnowledgeMutation
import kr.artel.orchestration.knowledge.service.KnowledgeRetrieval
import kr.artel.orchestration.knowledge.service.KnowledgeSearchService
import kr.artel.orchestration.knowledge.service.KnowledgeService
import kr.artel.orchestration.qa.dto.QaStatusPayload
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * knowledge 항목 하나를 다루는 인입 타입(ARTEL-188). 배치 인입인 `KNOWLEDGE`와 공존한다 —
 * 배치는 한 출처의 관측을 통째로 넣고, 이쪽은 이미 있는 지식창고를 항목 단위로 고치고 지운다.
 */
private val KNOWLEDGE_MUTATION_TYPES = setOf("KNOWLEDGE_CREATE", "KNOWLEDGE_UPDATE", "KNOWLEDGE_DELETE")

private val SUPPORTED_TYPES =
    setOf("LOG", "ACTION", "STATUS", "ERROR", "CHAT", "ISSUE", "KNOWLEDGE", "KNOWLEDGE_SEARCH") +
        KNOWLEDGE_MUTATION_TYPES

/** qa_try의 종단 상태들. 런의 모든 try가 여기 들면 그 런은 실행이 끝난 것이다. */
private val TERMINAL_TRY_STATUSES = setOf("COMPLETED", "FAILED", "CANCELLED")

@Service
class QaAgentInboundRouter(
    private val tryRepository: QaTryRepository,
    private val runRepository: QaRunRepository,
    private val logService: QaLogService,
    private val actionDispatch: QaActionDispatchService,
    private val streamManager: QaLogStreamManager,
    private val issueService: IssueService,
    private val knowledgeService: KnowledgeService,
    private val knowledgeSearchService: KnowledgeSearchService,
    private val agentPort: QaAgentPort,
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
        // 개별 생성·수정·삭제도 표시용 message 없이 knowledge 필드만 싣는다.
        if (envelope.type in KNOWLEDGE_MUTATION_TYPES) {
            val qaTry = activeTry(qaTryId) ?: return
            routeKnowledgeMutation(qaTryId, qaTry.gameInstanceId, envelope)
            return
        }
        // KNOWLEDGE_SEARCH carries the search term in payload.query, not a display
        // message, so it splits before the message-required guard for the same
        // reason KNOWLEDGE does.
        if (envelope.type == "KNOWLEDGE_SEARCH") {
            val qaTry = activeTry(qaTryId) ?: return
            routeKnowledgeSearch(qaTryId, qaTry, envelope)
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
            "STATUS" -> routeStatus(qaTry, qaTryId, envelope, message)
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

    private suspend fun activeTry(qaTryId: Long): QaTryEntity? {
        val qaTry = tryRepository.findById(qaTryId) ?: return null
        return when (qaTry.status) {
            "STARTING", "RUNNING" -> qaTry
            // 런의 다음 시나리오가 보낸 첫 프레임 — 이제 그 차례다. 런이 아직 살아 있으면
            // PENDING→RUNNING으로 활성해 그 프레임부터 정상 라우팅한다(ARTEL-259 시나리오 전환).
            "PENDING" -> activatePending(qaTry)
            else -> null
        }
    }

    /**
     * PENDING qa_try를 그 부모 런의 세션 공통 설정으로 활성한다. 런이 RUNNING이 아니면(끝났거나
     * 실패) 활성하지 않고 프레임을 버린다. 경합으로 이미 활성됐으면 다시 읽어 RUNNING이면 태운다.
     */
    private suspend fun activatePending(qaTry: QaTryEntity): QaTryEntity? {
        val runId = qaTry.qaRunId ?: return null
        val run = runRepository.findById(runId)?.takeIf { it.status == "RUNNING" } ?: return null
        val sessionId = run.agentSessionId ?: return null
        val id = requireNotNull(qaTry.id)
        val configJson = run.runConfig.asString()
        val config = objectMapper.readTree(configJson)
        val activated = tryRepository.activatePending(
            id = id,
            agentSessionId = sessionId,
            model = config.textAt("model"),
            reasoningEffort = config.textAt("reasoning", "effort"),
            promptVersion = config.textAt("prompt_version"),
            agentArch = config.textAt("agent_arch"),
            agentFingerprint = config.textAt("agent_fingerprint"),
            runConfig = configJson,
            updatedAt = Instant.now(clock)
        )
        if (activated != 1) return tryRepository.findById(id)?.takeIf { it.status == "RUNNING" }
        val log = logService.append(
            qaTryId = id,
            direction = "ORCHE_INTERNAL",
            type = "STATUS",
            message = "QA execution is running.",
            payload = objectMapper.valueToTree(QaStatusPayload("RUNNING", null))
        )
        logService.publish(log)
        return tryRepository.findById(id)
    }

    private fun JsonNode?.textAt(vararg path: String): String? {
        var node: JsonNode = this ?: return null
        for (name in path) {
            node = node.get(name) ?: return null
        }
        return node.takeIf { it.isTextual }?.asText()
    }

    private suspend fun routeStatus(
        qaTry: QaTryEntity,
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        message: String
    ) {
        val currentStatus = qaTry.status
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
        // 방금 이 시나리오 try가 종단됐다. 런의 모든 시나리오 try가 종단이면 부모 qa_run도 완료로
        // 닫는다 — 안 그러면 qa_run이 RUNNING으로 남아 그 게임 인스턴스의 다음 런을 영구 차단한다.
        completeRunIfAllTriesDone(qaTry.qaRunId, completedAt)
    }

    /**
     * 런의 모든 qa_try가 종단(COMPLETED/FAILED/CANCELLED)이면 qa_run을 RUNNING→COMPLETED로 닫는다.
     * 개별 시나리오의 합격/불합격은 각 try에 남고, 런의 COMPLETED는 "모든 시나리오 실행을 마쳤다"는
     * 뜻이다(하나가 FAILED여도 런은 끝난 것). RUNNING이 아닐 땐 no-op이라 취소/실패로 이미 닫힌 런을
     * 되돌리지 않는다.
     */
    private suspend fun completeRunIfAllTriesDone(qaRunId: Long?, completedAt: Instant) {
        if (qaRunId == null) return
        val tries = tryRepository.findByQaRunId(qaRunId).toList()
        if (tries.isNotEmpty() && tries.all { it.status in TERMINAL_TRY_STATUSES }) {
            runRepository.transition(qaRunId, "RUNNING", "COMPLETED", completedAt, completedAt)
        }
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
     * knowledge 항목 하나를 생성·수정·소프트삭제한다(ARTEL-188).
     *
     * **프로젝트 격리는 payload가 아니라 런에서 나온다.** projectId를 Agent가 보낸 값으로 받으면
     * 잘못된 값 하나로 다른 프로젝트의 지식창고를 깎을 수 있다. `qaTryId → game_instance →
     * project_id`로 도출해 서비스에 넘기고, 서비스는 그 프로젝트 안에서만 대상을 찾는다.
     *
     * 검증 실패는 전부 값([KnowledgeMutation.Rejected])으로 돌아와 ERROR 로그가 되고, 저장 중 난
     * 예외도 마찬가지로 삼킨다 — 프레임 하나가 receive 체인을 끊어 QA 런을 실패시키지 못하게 한다.
     * `CancellationException`은 예외가 아니라 취소 신호라 반드시 다시 던진다.
     */
    private suspend fun routeKnowledgeMutation(
        qaTryId: Long,
        gameInstanceId: Long,
        envelope: QaAgentEnvelope
    ) {
        val request = try {
            objectMapper.treeToValue(envelope.payload, KnowledgeMutationRequest::class.java)
        } catch (error: Exception) {
            appendError(qaTryId, envelope, "${envelope.type} payload parse failed: ${error.message}")
            return
        }
        val result = try {
            val instance = gameInstanceRepository.findById(gameInstanceId) ?: return
            val projectId = instance.projectId
            when (envelope.type) {
                "KNOWLEDGE_CREATE" -> knowledgeService.createFromQaTry(projectId, qaTryId, request)
                "KNOWLEDGE_UPDATE" -> knowledgeService.updateFromQaTry(projectId, qaTryId, request)
                else -> knowledgeService.softDeleteFromQaTry(projectId, qaTryId, request)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendError(qaTryId, envelope, "${envelope.type} failed: ${error.message}")
            return
        }
        if (result is KnowledgeMutation.Rejected) {
            appendError(qaTryId, envelope, "${envelope.type} rejected: ${result.reason}")
        }
    }

    /**
     * Agent의 지식 검색 요청을 처리하고 결과를 WS로 돌려준다(ARTEL-186).
     *
     * 검색 범위는 payload가 아니라 `qaTryId → gameInstanceId → projectId`로 해석한다. Agent가
     * 프로젝트를 지목할 수 있으면 프레임 하나로 남의 프로젝트 지식을 읽게 된다.
     *
     * **성공 응답은 qa_log에 남기지 않는다.** 지식 본문이 타임라인에 통째로 실리면 안 된다(쓰기 쪽
     * `KNOWLEDGE`도 같은 이유로 남기지 않는다). 실패만 ORCHE_INTERNAL 로그로 남고, 어느 쪽이든
     * throw하지 않아 receive 체인이 끊기지 않는다.
     *
     * 결과가 비는 것은 오류가 아니다 — 백필이 비동기라 벡터가 아직 없는 것이 정상 상태다.
     */
    private suspend fun routeKnowledgeSearch(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope
    ) {
        // 세션을 가장 먼저 확인한다. 답할 곳이 없으면 검색을 돌려 봐야 결과를 버리게 되고,
        // 그 사이 임베딩 호출 비용만 나간다. (프레임을 보낸 Agent가 곧 그 세션이라 실제로는
        // null이 아니지만, 그 가정을 코드가 기대는 대신 확인한다.)
        val sessionId = qaTry.agentSessionId
        if (sessionId == null) {
            appendError(qaTryId, envelope, "KNOWLEDGE_SEARCH has no Agent session to answer")
            return
        }
        // 이후 모든 실패는 감사 로그만이 아니라 ERROR 프레임으로도 답한다 — Agent 도구가 응답을
        // 기다리고 있어 로그만 남기면 그 도구가 매달린다.
        val request = try {
            objectMapper.treeToValue(envelope.payload, KnowledgeSearchRequest::class.java)
        } catch (error: Exception) {
            failSearch(qaTryId, sessionId, envelope, "KNOWLEDGE_SEARCH payload parse failed: ${error.message}")
            return
        }
        val query = request.query?.trim()
        if (query.isNullOrEmpty()) {
            failSearch(qaTryId, sessionId, envelope, "KNOWLEDGE_SEARCH payload.query is required")
            return
        }
        // tag는 단수/복수 둘 다 받는다(KnowledgeSearchRequest 주석 참조). 알 수 없는 토큰을 조용히
        // 버리면 필터가 걸린 줄 알고 넓은 결과를 읽게 되므로, 하나라도 모르면 요청을 거절한다.
        val requestedTags = request.tags + listOfNotNull(request.tag)
        val tags = requestedTags.map { KnowledgeTag.fromWire(it) }
        if (tags.any { it == null }) {
            failSearch(
                qaTryId,
                sessionId,
                envelope,
                "KNOWLEDGE_SEARCH payload tags must be one of ${KnowledgeTag.NAMES}: $requestedTags"
            )
            return
        }
        val source = request.source?.let { KnowledgeSource.fromWire(it) }
        if (request.source != null && source == null) {
            failSearch(
                qaTryId,
                sessionId,
                envelope,
                "KNOWLEDGE_SEARCH payload.source must be one of ${KnowledgeSource.NAMES}: ${request.source}"
            )
            return
        }
        val instance = gameInstanceRepository.findById(qaTry.gameInstanceId)
        if (instance == null) {
            failSearch(qaTryId, sessionId, envelope, "KNOWLEDGE_SEARCH cannot resolve the project of this run")
            return
        }
        val outcome = try {
            knowledgeSearchService.search(
                projectId = instance.projectId,
                query = query,
                tags = tags.filterNotNull(),
                source = source,
                limit = request.limit
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Agent 호출 실패·모델 불일치·DB 오류가 여기로 온다. 런 전체를 죽이지 않는다.
            failSearch(qaTryId, sessionId, envelope, "KNOWLEDGE_SEARCH failed: ${error.message}")
            return
        }
        recordSearchUsage(qaTryId, envelope, outcome.retrievals)
        sendToAgent(
            qaTryId,
            sessionId,
            "KNOWLEDGE_SEARCH_RESULT",
            envelope.messageId,
            objectMapper.valueToTree(outcome.response)
        )
    }

    /**
     * 검색이 무엇을 내보냈는지 남긴다(ARTEL-255). 이 로그가 "이 런이 만든 지식이 쓸모 있었나"의
     * 분모이고, 소급이 안 되므로 검색이 도는 지금 남기지 않으면 영영 없다.
     *
     * **응답 전송보다 먼저 부른다.** 뒤로 미루면 Agent에는 갔는데 기록은 안 된 창이 생긴다.
     * 추가 비용은 INSERT 한 문장이고, 이미 임베딩 왕복(네트워크)을 마친 뒤라 무시할 수준이다.
     *
     * **실패는 삼킨다.** 이 시점에 검색 결과는 이미 만들어졌고 Agent 도구는 그것을 기다리고 있다.
     * 기록이 안 됐다고 [failSearch]로 답하면 멀쩡한 검색이 실패로 뒤집힌다. ERROR 프레임을 보내지
     * 않고 감사 로그만 남기는 것이 이 경로가 다른 실패들과 다른 점이다.
     * `CancellationException`은 오류가 아니라 취소 신호라 반드시 다시 던진다.
     */
    private suspend fun recordSearchUsage(
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        retrievals: List<KnowledgeRetrieval>
    ) {
        try {
            knowledgeSearchService.recordRetrievals(qaTryId, retrievals)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendError(qaTryId, envelope, "KNOWLEDGE_SEARCH usage logging failed: ${error.message}")
        }
    }

    /**
     * 검색 실패를 타임라인에 남기고 Agent에도 ERROR 프레임으로 알린다.
     *
     * 두 곳 모두에 남기는 이유가 다르다: qa_log는 나중에 왜 실패했는지 읽기 위한 것이고, ERROR
     * 프레임은 기다리고 있는 Agent 도구를 풀어 주기 위한 것이다.
     */
    private suspend fun failSearch(
        qaTryId: Long,
        sessionId: String,
        envelope: QaAgentEnvelope,
        reason: String
    ) {
        appendError(qaTryId, envelope, reason)
        sendToAgent(
            qaTryId,
            sessionId,
            "ERROR",
            envelope.messageId,
            objectMapper.createObjectNode().put("message", reason)
        )
    }

    /**
     * 응답 프레임을 Agent로 보낸다. `correlationId`에 요청 messageId를 실어 Agent가 자기 도구 호출과
     * 맞출 수 있게 한다.
     *
     * 전송 실패는 여기서 멈춘다. 위로 던지면 receive 체인이 끊겨 WS가 닫히는데, 그것은 "응답 하나를
     * 못 보냈다"에 대한 대가로 지나치다.
     */
    private suspend fun sendToAgent(
        qaTryId: Long,
        sessionId: String,
        type: String,
        correlationId: String,
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
                    timestamp = Instant.now(clock),
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
                message = "QA Agent $type delivery failed.",
                payload = objectMapper.createObjectNode().put("error", error.message)
            )
            logService.publish(log)
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
