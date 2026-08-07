package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.entity.IssueSeverity
import kr.artel.orchestration.issue.service.IssueService
import kr.artel.orchestration.knowledge.dto.KnowledgeExpandRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeLinkRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeMutationRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeSearchRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeUnlinkRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeMode
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.knowledge.service.KnowledgeGraphMutation
import kr.artel.orchestration.knowledge.service.KnowledgeGraphService
import kr.artel.orchestration.knowledge.service.KnowledgeMutation
import kr.artel.orchestration.knowledge.service.KnowledgeRetrieval
import kr.artel.orchestration.knowledge.service.KnowledgeSearchService
import kr.artel.orchestration.knowledge.service.KnowledgeService
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * knowledge 항목 하나를 다루는 인입 타입(ARTEL-188). 배치 인입인 `KNOWLEDGE`와 공존한다 —
 * 배치는 한 출처의 관측을 통째로 넣고, 이쪽은 이미 있는 지식창고를 항목 단위로 고치고 지운다.
 */
private val KNOWLEDGE_MUTATION_TYPES = setOf("KNOWLEDGE_CREATE", "KNOWLEDGE_UPDATE", "KNOWLEDGE_DELETE")

/**
 * 지식 **그래프**를 다루는 인입 타입(ARTEL-274). 항목이 아니라 항목 **사이의 관계**를 만들고 거둔다.
 *
 * [KNOWLEDGE_MUTATION_TYPES]에 넣지 않는 것이 중요하다 — 그 집합은 `KnowledgeMutationRequest`를
 * 파싱하는 단일 디스패치를 몰고 다니는데, 링크의 payload는 그 스키마와 필드가 겹치지 않는다.
 * 대신 아래 [KNOWLEDGE_WRITE_TYPES]에는 들어가야 `knowledge_mode` 게이트가 따라온다.
 */
private val KNOWLEDGE_GRAPH_TYPES = setOf("KNOWLEDGE_LINK", "KNOWLEDGE_UNLINK")

/**
 * 지식창고에 **쓰는** 인입 타입 전부(ARTEL-256). `knowledge_mode`가 `learning`이 아닌 런에서는
 * 이 타입들이 거부된다. 새 쓰기 타입이 생기면 여기에 넣어야 게이트가 따라온다.
 */
private val KNOWLEDGE_WRITE_TYPES = KNOWLEDGE_MUTATION_TYPES + KNOWLEDGE_GRAPH_TYPES + "KNOWLEDGE"

private val SUPPORTED_TYPES =
    setOf("LOG", "ACTION", "STATUS", "ERROR", "CHAT", "ISSUE", "KNOWLEDGE_SEARCH", "KNOWLEDGE_EXPAND") +
        KNOWLEDGE_WRITE_TYPES

@Service
class QaAgentInboundRouter(
    private val tryRepository: QaTryRepository,
    private val logService: QaLogService,
    private val actionDispatch: QaActionDispatchService,
    private val streamManager: QaLogStreamManager,
    private val issueService: IssueService,
    private val knowledgeService: KnowledgeService,
    private val knowledgeGraphService: KnowledgeGraphService,
    private val knowledgeSearchService: KnowledgeSearchService,
    private val agentPort: QaAgentPort,
    private val gameInstanceRepository: GameInstanceRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(QaAgentInboundRouter::class.java)

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
        // 지식창고에 쓰는 타입은 모두 여기 하나로 모인다. 배치 인입(KNOWLEDGE)은 game_context
        // 리스트를, 개별 생성·수정·삭제는 knowledge 필드를 싣기 때문에 둘 다 표시용 message가
        // 없다 — 아래 message 필수 가드보다 앞서야 하는 이유가 그것이다.
        //
        // knowledge_mode 게이트를 분기 **전에** 한 번만 두는 것이 중요하다(ARTEL-256). 타입마다
        // 따로 걸면 새 쓰기 타입이 생겼을 때 빠뜨리기 쉽고, 빠뜨린 타입만 조용히 지식창고를 바꿔
        // frozen으로 돌린 arm이 사실은 학습을 한 셈이 된다.
        if (envelope.type in KNOWLEDGE_WRITE_TYPES) {
            val qaTry = activeTry(qaTryId) ?: return
            if (!allowKnowledgeWrite(qaTryId, qaTry, envelope)) return
            when {
                envelope.type == "KNOWLEDGE" -> routeKnowledge(qaTryId, qaTry, envelope)
                envelope.type in KNOWLEDGE_GRAPH_TYPES -> routeKnowledgeGraph(qaTryId, qaTry, envelope)
                else -> routeKnowledgeMutation(qaTryId, qaTry, envelope)
            }
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
        // 확장도 payload에 표시용 message가 없고 응답을 기다린다 — 검색과 같은 자리에 둔다.
        if (envelope.type == "KNOWLEDGE_EXPAND") {
            val qaTry = activeTry(qaTryId) ?: return
            routeKnowledgeExpand(qaTryId, qaTry, envelope)
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

    /**
     * 이 런이 읽고 쓰는 지식 스코프(ARTEL-256).
     *
     * 스코프는 **payload가 아니라 런에서 나온다.** Agent가 스코프를 지목할 수 있으면 프레임 하나로
     * 격리를 통과해 다른 arm의 지식을 읽거나 운영 지식창고에 쓸 수 있고, 그렇게 뚫린 실험은
     * 결과가 그럴듯해서 아무도 못 알아챈다. projectId를 런에서 도출하는 것과 같은 판단이다.
     */
    private fun scopeOf(qaTry: QaTryEntity) = KnowledgeScope.of(qaTry.knowledgeScopeId)

    /**
     * 이 런의 지식 모드. `run_config.knowledge_mode`가 진실이고, 없으면 [KnowledgeMode.DEFAULT]다.
     *
     * 값이 없는 런은 이 기능 이전의 런과 구버전 Agent가 붙은 런이다. 둘 다 지금까지처럼 읽고 써야
     * 한다 — 모드를 모르는 런이 실패하면 그것은 실험의 공백이 아니라 장애다(V25의 판단과 같다).
     *
     * 파싱 실패도 같은 이유로 기본값으로 떨어뜨린다. run_config는 Agent 응답이 섞이는 자리라
     * 여기서 throw하면 프레임 하나가 WS 수신 체인을 끊어 런 전체를 죽인다. 다만 **알 수 없는 값은
     * 로그로 남긴다** — 오타 하나로 `frozen`이 조용히 `learning`이 되면 그 arm의 결과가 통째로
     * 잘못 해석된다. (API가 이미 값을 검증하므로 여기 걸리는 것은 DB를 손으로 고친 경우다.)
     */
    private fun knowledgeModeOf(qaTry: QaTryEntity): KnowledgeMode {
        val raw = try {
            objectMapper.readTree(qaTry.runConfig.asString()).path(KNOWLEDGE_MODE_FIELD)
        } catch (error: Exception) {
            logger.warn("qa_try {} run_config 파싱 실패 — knowledge_mode 기본값 사용: {}", qaTry.id, error.message)
            return KnowledgeMode.DEFAULT
        }
        if (raw.isMissingNode || raw.isNull) return KnowledgeMode.DEFAULT
        return KnowledgeMode.fromWire(raw.asText())
            ?: KnowledgeMode.DEFAULT.also {
                logger.warn(
                    "qa_try {} run_config.knowledge_mode={} 를 해석할 수 없어 {}로 처리한다",
                    qaTry.id, raw.asText(), it.wire
                )
            }
    }

    /**
     * 지식창고 쓰기 프레임을 이 런이 보내도 되는가(ARTEL-256).
     *
     * `learning`이 아니면 거부하고 ERROR 로그만 남긴다. **throw하지 않는 것이 이 함수의 요점이다** —
     * 거부는 정상 동작이고, 여기서 예외가 WS 수신 체인 밖으로 나가면 소켓이 닫혀 런 전체가 실패한다
     * (파일 상단 [handle]의 판단과 같다). 쓰기 프레임은 애초에 응답을 기다리지 않는 단방향이라
     * Agent에 따로 알릴 것도 없다.
     */
    private suspend fun allowKnowledgeWrite(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope
    ): Boolean {
        val mode = knowledgeModeOf(qaTry)
        if (mode.writable) return true
        appendError(
            qaTryId,
            envelope,
            "${envelope.type} rejected: knowledge_mode=${mode.wire} does not allow writing to the knowledge base"
        )
        return false
    }

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
     * 고정하고, source_id=qa_try.id, project_id는 게임 인스턴스에서, 지식 스코프는 런에서 도출한다.
     * 파싱/빈 배열/저장 실패는 throw하지 않고 ORCHE_INTERNAL 오류 로그로 떨어뜨려(런은 실패 처리
     * 안 함) receive 체인을 끊지 않는다.
     */
    private suspend fun routeKnowledge(
        qaTryId: Long,
        qaTry: QaTryEntity,
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
            val instance = gameInstanceRepository.findById(qaTry.gameInstanceId) ?: return
            knowledgeService.store(
                projectId = instance.projectId,
                scope = scopeOf(qaTry),
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
     * **프로젝트 격리도 스코프 격리도 payload가 아니라 런에서 나온다.** projectId를 Agent가 보낸
     * 값으로 받으면 잘못된 값 하나로 다른 프로젝트의 지식창고를 깎을 수 있다. `qaTryId →
     * game_instance → project_id`로 도출해 서비스에 넘기고, 서비스는 그 프로젝트 안에서만 대상을
     * 찾는다. 지식 스코프도 같은 이유로 `qa_try.knowledge_scope_id`에서만 온다(ARTEL-256).
     *
     * 스코프 런이 운영 지식(baseline)을 고치거나 지우려 하면 서비스가 원본 대신 그림자 행을 만든다.
     * 라우터는 그 분기를 알지 않는다 — 어디에 쓸지는 스코프를 아는 쪽이 정한다.
     *
     * 검증 실패는 전부 값([KnowledgeMutation.Rejected])으로 돌아와 ERROR 로그가 되고, 저장 중 난
     * 예외도 마찬가지로 삼킨다 — 프레임 하나가 receive 체인을 끊어 QA 런을 실패시키지 못하게 한다.
     * `CancellationException`은 예외가 아니라 취소 신호라 반드시 다시 던진다.
     */
    private suspend fun routeKnowledgeMutation(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope
    ) {
        val request = try {
            objectMapper.treeToValue(envelope.payload, KnowledgeMutationRequest::class.java)
        } catch (error: Exception) {
            appendError(qaTryId, envelope, "${envelope.type} payload parse failed: ${error.message}")
            return
        }
        val result = try {
            val instance = gameInstanceRepository.findById(qaTry.gameInstanceId) ?: return
            val projectId = instance.projectId
            val scope = scopeOf(qaTry)
            when (envelope.type) {
                "KNOWLEDGE_CREATE" -> knowledgeService.createFromQaTry(projectId, scope, qaTryId, request)
                "KNOWLEDGE_UPDATE" -> knowledgeService.updateFromQaTry(projectId, scope, qaTryId, request)
                else -> knowledgeService.softDeleteFromQaTry(projectId, scope, qaTryId, request)
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
     * Agent가 주장하거나 거두는 지식 **관계**를 처리한다(ARTEL-274).
     *
     * [routeKnowledgeMutation]과 같은 모양이고 같은 이유를 진다: 프로젝트와 스코프는 payload가
     * 아니라 `qaTryId → game_instance → project_id` / `qa_try.knowledge_scope_id`에서 나오고,
     * 검증 실패는 값([KnowledgeGraphMutation.Rejected])으로 돌아와 ERROR 로그가 되며, 저장 중
     * 예외도 삼킨다 — 프레임 하나가 receive 체인을 끊어 QA 런을 실패시키지 못하게 한다.
     *
     * 링크 프레임은 **단방향이라 응답이 없다.** 거절도 Agent에게 내려가지 않으므로, Agent 쪽은
     * 보낼 수 있는 것만 보내도록 자기 손에서 먼저 검증한다. 여기 남는 ERROR 로그가 그 검증이
     * 뚫렸을 때 사람이 볼 유일한 흔적이다.
     */
    private suspend fun routeKnowledgeGraph(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope
    ) {
        val result = try {
            val instance = gameInstanceRepository.findById(qaTry.gameInstanceId) ?: return
            val projectId = instance.projectId
            val scope = scopeOf(qaTry)
            if (envelope.type == "KNOWLEDGE_LINK") {
                val request = objectMapper.treeToValue(envelope.payload, KnowledgeLinkRequest::class.java)
                knowledgeGraphService.link(projectId, scope, qaTryId, request)
            } else {
                val request = objectMapper.treeToValue(envelope.payload, KnowledgeUnlinkRequest::class.java)
                knowledgeGraphService.unlink(projectId, scope, qaTryId, request)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendError(qaTryId, envelope, "${envelope.type} failed: ${error.message}")
            return
        }
        if (result is KnowledgeGraphMutation.Rejected) {
            appendError(qaTryId, envelope, "${envelope.type} rejected: ${result.reason}")
        }
    }

    /**
     * Agent의 지식 검색 요청을 처리하고 결과를 WS로 돌려준다(ARTEL-186).
     *
     * 검색 범위는 payload가 아니라 `qaTryId → gameInstanceId → projectId`로 해석한다. Agent가
     * 프로젝트를 지목할 수 있으면 프레임 하나로 남의 프로젝트 지식을 읽게 된다. 지식 스코프와
     * 모드도 같은 이유로 런에서만 온다(ARTEL-256).
     *
     * `knowledge_mode=off`인 런에서는 검색 서비스가 빈 결과로 답한다. 그것도 정상 `..._RESULT`
     * 프레임이다 — ERROR로 답하면 Agent가 도구 실패로 보고 재시도해, 없애려던 변수가 다시 든다.
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
                scope = scopeOf(qaTry),
                mode = knowledgeModeOf(qaTry),
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
     * Agent가 지목한 항목에서 그래프를 더 편다(ARTEL-275).
     *
     * [routeKnowledgeSearch]와 같은 골격이고 같은 이유를 진다: 세션을 가장 먼저 보고(답할 곳이
     * 없으면 일을 시작하지 않는다), 이후 모든 실패는 [failSearch]로 ERROR 프레임까지 보내
     * 기다리는 도구를 풀어 주며, 사용 기록은 **응답 전에** 남긴다.
     *
     * `knowledge_mode=off`면 빈 결과로 답한다. 오류가 아니라 정상 `..._RESULT`인 것도 검색과 같은
     * 판단이다 — ERROR로 답하면 Agent가 도구 실패로 보고 재시도해, 없애려던 변수가 다시 든다.
     *
     * 성공 응답은 qa_log에 남기지 않는다. 지식 본문이 타임라인에 통째로 실리면 안 된다.
     */
    private suspend fun routeKnowledgeExpand(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope
    ) {
        val sessionId = qaTry.agentSessionId
        if (sessionId == null) {
            appendError(qaTryId, envelope, "KNOWLEDGE_EXPAND has no Agent session to answer")
            return
        }
        val request = try {
            objectMapper.treeToValue(envelope.payload, KnowledgeExpandRequest::class.java)
        } catch (error: Exception) {
            failSearch(qaTryId, sessionId, envelope, "KNOWLEDGE_EXPAND payload parse failed: ${error.message}")
            return
        }
        val knowledgeId = request.knowledgeId?.trim()?.toLongOrNull()
        if (knowledgeId == null) {
            failSearch(qaTryId, sessionId, envelope, "KNOWLEDGE_EXPAND payload.knowledge_id must be a numeric id")
            return
        }
        val instance = gameInstanceRepository.findById(qaTry.gameInstanceId)
        if (instance == null) {
            failSearch(qaTryId, sessionId, envelope, "KNOWLEDGE_EXPAND cannot resolve the project of this run")
            return
        }
        val outcome = try {
            knowledgeSearchService.expand(
                projectId = instance.projectId,
                scope = scopeOf(qaTry),
                mode = knowledgeModeOf(qaTry),
                knowledgeId = knowledgeId,
                depth = request.depth,
                includeSimilar = request.includeSimilar
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failSearch(qaTryId, sessionId, envelope, "KNOWLEDGE_EXPAND failed: ${error.message}")
            return
        }
        recordSearchUsage(qaTryId, envelope, outcome.retrievals)
        sendToAgent(
            qaTryId,
            sessionId,
            "KNOWLEDGE_EXPAND_RESULT",
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
