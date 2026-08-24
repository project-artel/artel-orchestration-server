package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
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
import kr.artel.orchestration.knowledge.service.KnowledgeCitationService
import kr.artel.orchestration.knowledge.service.KnowledgeGraphMutation
import kr.artel.orchestration.knowledge.service.KnowledgeGraphService
import kr.artel.orchestration.knowledge.service.KnowledgeMutation
import kr.artel.orchestration.knowledge.service.KnowledgeRetrieval
import kr.artel.orchestration.knowledge.service.KnowledgeSearchService
import kr.artel.orchestration.knowledge.service.KnowledgeService
import kr.artel.orchestration.qa.dto.QaStatusPayload
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
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

/**
 * 쓰기 중 **응답을 받는** 타입(ARTEL-331). Agent의 도구가 이 프레임들의 답을 기다린다.
 *
 * 배치 인입 `KNOWLEDGE`가 빠진 것이 이 집합의 요점이다. 그것은 도구 호출이 아니라 런 초기의 일괄
 * 적재라 기다리는 호출부가 없고, 답한다면 id 하나가 아니라 N개를 실어야 해서 payload 모양도 다르다.
 * 나중에 Agent가 배치 결과를 쓰게 되면 그때 `knowledge_ids` 배열로 확장하고 여기 넣는다.
 */
private val ANSWERED_WRITE_TYPES = KNOWLEDGE_MUTATION_TYPES + KNOWLEDGE_GRAPH_TYPES

/**
 * 쓰기 응답 프레임의 타입. 다섯 쓰기가 **하나의 타입**으로 답한다(ARTEL-331).
 *
 * `KNOWLEDGE_SEARCH_RESULT`/`KNOWLEDGE_EXPAND_RESULT`처럼 요청마다 쪼개지 않는다. 저 둘은 1:1
 * 요청-응답이지만 쓰기는 다섯이 한 가족이고 응답이 id 필드 하나만 다르다. 타입을 하나로 두면 다음
 * 쓰기 타입이 계약을 자동으로 물려받는다 — KNOWLEDGE_UPDATE(ARTEL-257)와 LINK/UNLINK(ARTEL-274)가
 * 각각 "답이 있나 없나"를 다시 정했던 것이 이 이슈의 원인이다.
 *
 * 무엇의 답인지는 payload의 `type`이 말한다. correlation은 messageId 기준이라 매칭에는 쓰이지
 * 않고, 로그를 읽는 사람과 소비자의 분기를 위한 것이다.
 */
private const val KNOWLEDGE_WRITE_RESULT_TYPE = "KNOWLEDGE_WRITE_RESULT"

/**
 * 이슈 보고의 응답 프레임 타입(ARTEL-366).
 *
 * [KNOWLEDGE_WRITE_RESULT_TYPE]을 일반화해 같이 쓰지 않는다. payload 모양은 똑같지만(요청 타입
 * echo + id 하나) 그 이름은 지식 쓰기 한 가족을 뜻하고, 이슈는 다른 도메인이다 — 저장하는 테이블도
 * 수명도 소비자도 다르다. 이름을 넓히면 "지식 쓰기의 답"이라는 뜻이 사라지고, 그 뜻이 사라진 뒤에는
 * 다음 사람이 아무 프레임의 답이나 그 타입으로 보내게 된다.
 */
private const val ISSUE_RESULT_TYPE = "ISSUE_RESULT"

private val SUPPORTED_TYPES =
    setOf("LOG", "ACTION", "STATUS", "ERROR", "CHAT", "ISSUE", "KNOWLEDGE_SEARCH", "KNOWLEDGE_EXPAND") +
        KNOWLEDGE_WRITE_TYPES

/**
 * 스텝 판정 STATUS가 인용을 싣는 필드(ARTEL-293). Agent의 `report_step`이 채운다.
 *
 * 판정 프레임에만 있고 액션 프레임에는 없다. 지식은 스텝 판단에 작용하지 개별 클릭에 작용하지
 * 않으며, 클릭마다 인용하게 하면 10클릭짜리 스텝의 항목이 1클릭짜리보다 10배 유용해 보인다 —
 * 지표가 유용성이 아니라 액션 수 가중치가 된다.
 */
private const val USED_KNOWLEDGE_IDS_FIELD = "used_knowledge_ids"

@Service
class QaAgentInboundRouter(
    private val tryRepository: QaTryRepository,
    private val runRepository: QaRunRepository,
    private val runRollupService: QaRunRollupService,
    private val readings: QaReadingsService,
    private val logService: QaLogService,
    private val actionDispatch: QaActionDispatchService,
    private val streamManager: QaLogStreamManager,
    private val issueService: IssueService,
    private val knowledgeService: KnowledgeService,
    private val knowledgeGraphService: KnowledgeGraphService,
    private val knowledgeSearchService: KnowledgeSearchService,
    private val knowledgeCitationService: KnowledgeCitationService,
    private val agentPort: QaAgentPort,
    private val gameInstanceRepository: GameInstanceRepository,
    private val grader: ExpectedStepsGrader,
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
            "STATUS" -> routeStatus(qaTry, qaTryId, envelope, message)
            "ISSUE" -> routeIssue(qaTryId, qaTry, envelope, message)
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
     * `learning`이 아니면 거부한다. **throw하지 않는 것이 이 함수의 요점이다** — 거부는 정상
     * 동작이고, 여기서 예외가 WS 수신 체인 밖으로 나가면 소켓이 닫혀 런 전체가 실패한다
     * (파일 상단 [handle]의 판단과 같다).
     *
     * 거부도 [ANSWERED_WRITE_TYPES]이면 Agent에 답한다(ARTEL-331). 여기서 답하지 않으면
     * `frozen`/`off` 런의 **모든** 쓰기가 Agent 쪽 타임아웃을 통째로 태운다 — 실험용 arm이 가장
     * 느려지는, 지표에는 실패로 남지 않는 종류의 회귀다.
     */
    private suspend fun allowKnowledgeWrite(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope
    ): Boolean {
        val mode = knowledgeModeOf(qaTry)
        if (mode.writable) return true
        rejectWrite(
            qaTryId,
            qaTry,
            envelope,
            "${envelope.type} rejected: knowledge_mode=${mode.wire} does not allow writing to the knowledge base"
        )
        return false
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
            // 스텝 판정이 인용을 실어 온다(ARTEL-293). 표시를 **로그보다 먼저** 한다: 뒤로
            // 미루면 판정은 타임라인에 남았는데 인용은 안 찍힌 창이 생긴다.
            recordCitations(qaTryId, envelope)
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
        // 판정 승격은 **이 분기 안에서만** 일어난다 — 위 resolved == null 갈래(스텝 판정 프레임)는
        // 런을 끝내지도, 판정을 싣지도 않는다. 전이 **뒤**인 것도 의도다: 앞에 두면 종단되지 않은
        // 런에 판정이 새겨질 수 있다.
        promoteVerdict(qaTryId, envelope, completedAt)
        // 자기채점된 판정을 사람이 단 기대 라벨과 대조한다(ARTEL-301). 승격과 같은 자리인 것이
        // 자연스럽다 — 둘 다 "이 런이 끝났고, 그 결과를 기록한다"의 일부다. 채점자는 스스로
        // 삼키므로 여기서 감싸지 않는다.
        grader.grade(qaTryId)
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
        // 이 시나리오 try가 방금 끝났으므로 미인용 행을 여기서 확정한다(ARTEL-293). **런이 아니라
        // try 단위인 것이 요점이다** — 세션 하나가 시나리오들을 순차 실행하므로, 런이나 세션이
        // 끝날 때까지 미루면 앞선 시나리오들의 확정이 늦거나 다음 시나리오의 검색과 뒤섞인다.
        knowledgeCitationService.finalizeTry(qaTryId)
        // 방금 이 시나리오 try가 종단됐다. 모두 끝났으면 FAILED > CANCELLED > COMPLETED 우선순위로
        // 부모 run에 올린다 — 안 그러면 RUNNING으로 남아 다음 런을 영구 차단한다.
        runRollupService.rollUpIfAllTriesDone(qaTry.qaRunId, completedAt)
        // 런이 정말 끝났을 때만 판독이 멈춘다 — 시나리오가 여럿이면 이 시도가 끝나도 다음이 남아
        // 있고, 그 판단은 `stopIfIdle` 안에 있다 (ARTEL-507).
        readings.stopIfIdle(qaTry.gameInstanceId)
    }

    /**
     * 종단 STATUS가 실어 온 2단 요약을 qa_try 컬럼으로 승격한다(ARTEL-299).
     *
     * **컬럼은 GROUP BY용 사본이고 진실은 방금 qa_log에 들어간 payload다**(V25의 run_config와
     * 축 컬럼의 관계와 같다). 여기 쓰는 목적은 여러 런을 축으로 접는 집계가 qa_log 전체 스캔 +
     * JSONB 경로 필터를 하지 않게 하는 것뿐이다.
     *
     * **요약이 없으면 아무것도 쓰지 않는다.** 소켓 사망·취소·state 없이 끝나는 경로는 요약을
     * 싣지 못하고, 그런 런은 판정이 0인 것이 아니라 **모르는** 것이다. 0으로 채우면 잘 죽는
     * 모델이 전부 0점으로 보이고 그 오류는 조용히 지나간다.
     *
     * 네 값은 각각 독립으로 읽는다. 하나가 없으면 그 컬럼만 NULL이고 나머지는 채운다 — "요약이
     * 온전할 때만 쓴다"로 하면 필드 하나가 빠졌다는 이유로 나머지 셋까지 미지가 되어 커버리지가
     * 실제보다 낮게 보고된다.
     *
     * **실패는 삼킨다.** 여기서 나간 예외는 WS 수신 체인 밖으로 나가 소켓을 닫고, 그것이
     * onDisconnect로 이어져 이미 정상 종료한 런을 실패로 뒤집는다. 판정 사본 하나를 못 쓴 대가로는
     * 지나치다 — 원본은 qa_log에 남아 있어 나중에 다시 낼 수 있다. `CancellationException`은
     * 오류가 아니라 취소 신호라 반드시 먼저 다시 던진다([recordSearchUsage]와 같은 규율).
     */
    private suspend fun promoteVerdict(
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        updatedAt: Instant
    ) {
        val summary = envelope.payload.path("summary").takeIf { it.isObject } ?: return
        val stepsTotal = summary.longAt("steps", "total")
        val stepsPassed = summary.longAt("steps", "passed")
        val casesTotal = summary.longAt("cases", "total")
        val casesPassed = summary.longAt("cases", "passed")
        if (stepsTotal == null && stepsPassed == null && casesTotal == null && casesPassed == null) return
        try {
            tryRepository.promoteVerdict(
                id = qaTryId,
                stepsTotal = stepsTotal,
                stepsPassed = stepsPassed,
                casesTotal = casesTotal,
                casesPassed = casesPassed,
                updatedAt = updatedAt
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendError(qaTryId, envelope, "STATUS verdict promotion failed: ${error.message}")
        }
    }

    /**
     * 스텝 판정이 보고한 인용을 표시한다(ARTEL-293).
     *
     * **실패를 삼킨다.** 여기서 나간 예외는 WS 수신 체인 밖으로 나가 소켓을 닫고, 그것이
     * onDisconnect로 이어져 런 전체를 실패시킨다 — 기록 하나가 런을 죽여서는 안 된다는 이 파일의
     * 규칙 그대로다(`recordSearchUsage`도 같다). 감사 로그만 남긴다.
     *
     * **거부된 id는 조용히 버리지 않는다.** 환각 인용률 자체가 모델 비교 지표라, 개수를 세어
     * 타임라인에 남긴다. 이 로그가 그 신호의 유일한 기록이다 — 인용은 성공하면 usage 행에
     * 남지만, 실패한 인용은 남을 행이 없다.
     */
    private suspend fun recordCitations(qaTryId: Long, envelope: QaAgentEnvelope) {
        val ids = envelope.payload.path(USED_KNOWLEDGE_IDS_FIELD)
            .takeIf { it.isArray }
            ?.mapNotNull { node -> node.takeIf { it.isTextual || it.isNumber }?.asText() }
            .orEmpty()
        if (ids.isEmpty()) return
        try {
            val outcome = knowledgeCitationService.recordCitations(qaTryId, ids)
            if (outcome.rejected.isNotEmpty()) {
                appendError(
                    qaTryId,
                    envelope,
                    "STATUS cited ${outcome.rejected.size} knowledge id(s) this run never retrieved: " +
                        "${outcome.rejected}"
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendError(qaTryId, envelope, "STATUS citation recording failed: ${error.message}")
        }
    }

    /**
     * 정수 값을 **좁히지 않고** 읽는다. `asInt()`로 접으면 INT를 넘는 수가 조용히 다른 수로
     * 저장되는데, 판정 지표에서 그것은 못 읽은 것보다 나쁘다. 안 들어가는 값은 컬럼 타입이
     * 거절하고 [promoteVerdict]의 삼킴이 그것을 로그로 떨어뜨린다.
     */
    private fun JsonNode.longAt(vararg path: String): Long? {
        var node: JsonNode = this
        for (name in path) {
            node = node.get(name) ?: return null
        }
        return node.takeIf { it.isIntegralNumber }?.asLong()
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
     * 검증 실패는 전부 값([KnowledgeMutation.Rejected])으로 돌아와 ERROR가 되고, 저장 중 난 예외도
     * 마찬가지로 삼킨다 — 프레임 하나가 receive 체인을 끊어 QA 런을 실패시키지 못하게 한다.
     * `CancellationException`은 예외가 아니라 취소 신호라 반드시 다시 던진다.
     *
     * 성공하면 [KNOWLEDGE_WRITE_RESULT_TYPE]으로 답하고 만들어진 항목의 id를 싣는다(ARTEL-331).
     * 그 id는 [KnowledgeMutation.Applied]가 지는 값 그대로다 — 스코프 런에서 그림자나 툼스톤이
     * 만들어졌으면 **그 행의** id다. baseline id를 돌려주면 그 런에서 다시 지목할 수 없는 id를
     * 주게 된다.
     */
    private suspend fun routeKnowledgeMutation(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope
    ) {
        val request = try {
            objectMapper.treeToValue(envelope.payload, KnowledgeMutationRequest::class.java)
        } catch (error: Exception) {
            rejectWrite(qaTryId, qaTry, envelope, "${envelope.type} payload parse failed: ${error.message}")
            return
        }
        val result = try {
            val instance = gameInstanceRepository.findById(qaTry.gameInstanceId)
                ?: return rejectWrite(
                    qaTryId,
                    qaTry,
                    envelope,
                    "${envelope.type} cannot resolve the project of this run"
                )
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
            rejectWrite(qaTryId, qaTry, envelope, "${envelope.type} failed: ${error.message}")
            return
        }
        when (result) {
            is KnowledgeMutation.Rejected ->
                rejectWrite(qaTryId, qaTry, envelope, "${envelope.type} rejected: ${result.reason}")
            is KnowledgeMutation.Applied ->
                answerWrite(qaTryId, qaTry, envelope, "knowledge_id", result.knowledgeId)
        }
    }

    /**
     * Agent가 주장하거나 거두는 지식 **관계**를 처리한다(ARTEL-274).
     *
     * [routeKnowledgeMutation]과 같은 모양이고 같은 이유를 진다: 프로젝트와 스코프는 payload가
     * 아니라 `qaTryId → game_instance → project_id` / `qa_try.knowledge_scope_id`에서 나오고,
     * 검증 실패는 값([KnowledgeGraphMutation.Rejected])으로 돌아와 ERROR가 되며, 저장 중 예외도
     * 삼킨다 — 프레임 하나가 receive 체인을 끊어 QA 런을 실패시키지 못하게 한다.
     *
     * 성공은 [routeKnowledgeMutation]과 같은 프레임으로 답하되 id 필드가 `edge_id`다(ARTEL-331).
     * 거두기(UNLINK)의 id는 지워진 간선의 것이고, 스코프 런이 baseline 간선을 거둔 경우에만 그것을
     * 가린 툼스톤 행의 id다 — 어느 쪽이든 "그 런에서 이 사실을 지고 있는 행"이라는 점은 같다.
     *
     * Agent 쪽 로컬 검증(관계 이름, 양 끝 id)은 응답이 생겨도 그대로 둔다. 왕복 한 번을 아끼는
     * 값어치가 남고, 보낼 수 없는 프레임을 보내지 않는 것이 여전히 더 싸다.
     */
    private suspend fun routeKnowledgeGraph(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope
    ) {
        val result = try {
            val instance = gameInstanceRepository.findById(qaTry.gameInstanceId)
                ?: return rejectWrite(
                    qaTryId,
                    qaTry,
                    envelope,
                    "${envelope.type} cannot resolve the project of this run"
                )
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
            rejectWrite(qaTryId, qaTry, envelope, "${envelope.type} failed: ${error.message}")
            return
        }
        when (result) {
            is KnowledgeGraphMutation.Rejected ->
                rejectWrite(qaTryId, qaTry, envelope, "${envelope.type} rejected: ${result.reason}")
            is KnowledgeGraphMutation.Applied ->
                answerWrite(qaTryId, qaTry, envelope, "edge_id", result.edgeId)
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
            answerWithError(qaTryId, sessionId, envelope, "KNOWLEDGE_SEARCH payload parse failed: ${error.message}")
            return
        }
        val query = request.query?.trim()
        if (query.isNullOrEmpty()) {
            answerWithError(qaTryId, sessionId, envelope, "KNOWLEDGE_SEARCH payload.query is required")
            return
        }
        // tag는 단수/복수 둘 다 받는다(KnowledgeSearchRequest 주석 참조). 알 수 없는 토큰을 조용히
        // 버리면 필터가 걸린 줄 알고 넓은 결과를 읽게 되므로, 하나라도 모르면 요청을 거절한다.
        val requestedTags = request.tags + listOfNotNull(request.tag)
        val tags = requestedTags.map { KnowledgeTag.fromWire(it) }
        if (tags.any { it == null }) {
            answerWithError(
                qaTryId,
                sessionId,
                envelope,
                "KNOWLEDGE_SEARCH payload tags must be one of ${KnowledgeTag.NAMES}: $requestedTags"
            )
            return
        }
        val source = request.source?.let { KnowledgeSource.fromWire(it) }
        if (request.source != null && source == null) {
            answerWithError(
                qaTryId,
                sessionId,
                envelope,
                "KNOWLEDGE_SEARCH payload.source must be one of ${KnowledgeSource.NAMES}: ${request.source}"
            )
            return
        }
        val instance = gameInstanceRepository.findById(qaTry.gameInstanceId)
        if (instance == null) {
            answerWithError(qaTryId, sessionId, envelope, "KNOWLEDGE_SEARCH cannot resolve the project of this run")
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
            answerWithError(qaTryId, sessionId, envelope, "KNOWLEDGE_SEARCH failed: ${error.message}")
            return
        }
        recordSearchUsage(qaTryId, envelope, outcome.retrievals, request.step)
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
     * 없으면 일을 시작하지 않는다), 이후 모든 실패는 [answerWithError]로 ERROR 프레임까지 보내
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
            answerWithError(qaTryId, sessionId, envelope, "KNOWLEDGE_EXPAND payload parse failed: ${error.message}")
            return
        }
        val knowledgeId = request.knowledgeId?.trim()?.toLongOrNull()
        if (knowledgeId == null) {
            answerWithError(qaTryId, sessionId, envelope, "KNOWLEDGE_EXPAND payload.knowledge_id must be a numeric id")
            return
        }
        val instance = gameInstanceRepository.findById(qaTry.gameInstanceId)
        if (instance == null) {
            answerWithError(qaTryId, sessionId, envelope, "KNOWLEDGE_EXPAND cannot resolve the project of this run")
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
            answerWithError(qaTryId, sessionId, envelope, "KNOWLEDGE_EXPAND failed: ${error.message}")
            return
        }
        recordSearchUsage(qaTryId, envelope, outcome.retrievals, request.step)
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
     * 기록이 안 됐다고 [answerWithError]로 답하면 멀쩡한 검색이 실패로 뒤집힌다. ERROR 프레임을 보내지
     * 않고 감사 로그만 남기는 것이 이 경로가 다른 실패들과 다른 점이다.
     * `CancellationException`은 오류가 아니라 취소 신호라 반드시 다시 던진다.
     */
    private suspend fun recordSearchUsage(
        qaTryId: Long,
        envelope: QaAgentEnvelope,
        retrievals: List<KnowledgeRetrieval>,
        step: Int?
    ) {
        try {
            knowledgeSearchService.recordRetrievals(qaTryId, retrievals, step)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendError(qaTryId, envelope, "KNOWLEDGE_SEARCH usage logging failed: ${error.message}")
        }
    }

    /**
     * 요청 실패를 타임라인에 남기고 Agent에도 ERROR 프레임으로 알린다.
     *
     * 두 곳 모두에 남기는 이유가 다르다: qa_log는 나중에 왜 실패했는지 읽기 위한 것이고, ERROR
     * 프레임은 기다리고 있는 Agent 도구를 풀어 주기 위한 것이다.
     *
     * 검색·확장과 쓰기(ARTEL-331)가 이 하나를 공유한다. 실패를 알리는 방법이 요청 종류마다 다르면
     * 소비자가 그만큼 분기해야 하는데, Agent 쪽은 correlation 하나로 대기를 푸는 것이 전부다.
     */
    private suspend fun answerWithError(
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
     * 지식 쓰기가 성공했음을 Agent에 알린다(ARTEL-331). [idField]는 `knowledge_id` 또는 `edge_id`다.
     *
     * id를 문자열로 싣는다. 조회 응답이 같은 이유로 그렇게 한다 — 64비트 id가 JSON 숫자로 나가면
     * 자바스크립트 소비자에서 정밀도가 깎인다.
     *
     * 성공 응답은 qa_log에 남기지 않는다. 변이 사실은 이미 `knowledge_event`에 남고 이 프레임은
     * id만 진 파생물이다(확장 응답을 남기지 않는 것과 같은 판단).
     */
    private suspend fun answerWrite(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope,
        idField: String,
        id: Long
    ) {
        val sessionId = qaTry.agentSessionId ?: return
        sendToAgent(
            qaTryId,
            sessionId,
            KNOWLEDGE_WRITE_RESULT_TYPE,
            envelope.messageId,
            objectMapper.createObjectNode()
                .put("type", envelope.type)
                .put(idField, id.toString())
        )
    }

    /**
     * 지식 쓰기의 거절을 타임라인에 남기고, 답을 기다리는 타입이면 Agent에도 알린다(ARTEL-331).
     *
     * **세션이 없어도 쓰기 자체는 이미 수행됐다.** 검색·확장은 답할 곳이 없으면 일을 시작조차 하지
     * 않지만(그쪽은 결과가 곧 목적이다) 쓰기가 그러면 지식이 저장되지 않는다. 그래서 여기서는
     * 세션 없음이 "답을 못 보낸다"일 뿐이고, 그때도 감사 로그는 남는다.
     *
     * 배치 인입(`KNOWLEDGE`)은 [ANSWERED_WRITE_TYPES]에 없어 로그만 남는다. 기다리는 호출부가
     * 없는 프레임에 ERROR를 내려보내면 Agent 쪽에서 짝 없는 응답이 되어 경고만 쌓인다.
     */
    private suspend fun rejectWrite(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope,
        reason: String
    ) {
        val sessionId = qaTry.agentSessionId
        if (sessionId == null || envelope.type !in ANSWERED_WRITE_TYPES) {
            appendError(qaTryId, envelope, reason)
            return
        }
        answerWithError(qaTryId, sessionId, envelope, reason)
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
     * 대신 값으로 처리해, 프레임 하나가 receive 체인을 끊어 실행을 실패시키지 못하게 한다.
     * `title`은 [handle]의 non-blank 가드에서 이미 필수로 걸렀다.
     *
     * **성공과 거절 모두 Agent에 답한다**(ARTEL-366). 그 전에는 성공이 침묵이고 거절도 운영자
     * 타임라인의 ERROR 행뿐이라, severity 오타 하나면 버그 보고가 조용히 사라지고 모델은 보고했다고
     * 믿었다. 잃는 것이 지식보다 크다 — 지식은 다음 런이 다시 배울 수 있지만 이 런이 본 버그는
     * 이 런에서만 볼 수 있었다.
     *
     * 계약은 지식 쓰기의 것을 그대로 쓴다(ARTEL-331): 성공은 RESULT, 거절은 요청의 correlation을
     * 문 ERROR. 세션이 없으면 저장은 하고 답만 못 한다 — 쓰기와 같은 판단이고 이유도 같다.
     */
    private suspend fun routeIssue(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope,
        title: String
    ) {
        val severity = envelope.payload.path("severity").takeIf { it.isTextual }?.asText()
        if (severity == null || severity !in IssueSeverity.NAMES) {
            rejectIssue(
                qaTryId,
                qaTry,
                envelope,
                "ISSUE payload.severity must be one of ${IssueSeverity.NAMES}"
            )
            return
        }
        val issueId = try {
            issueService.recordAgentIssue(
                qaTryId = qaTryId,
                messageId = envelope.messageId,
                correlationId = envelope.correlationId,
                severity = severity,
                title = title,
                reportedAt = envelope.timestamp,
                payload = envelope.payload
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 1 MiB 상한 위반이 여기로 온다. 그 전에는 예외가 receive 체인 밖으로 나가 런을
            // 죽였다 — 보고 하나가 실행을 끝내는 것은 이 파일의 다른 어떤 경로도 하지 않는 일이다.
            rejectIssue(qaTryId, qaTry, envelope, "ISSUE failed: ${error.message}")
            return
        }
        val sessionId = qaTry.agentSessionId ?: return
        sendToAgent(
            qaTryId,
            sessionId,
            ISSUE_RESULT_TYPE,
            envelope.messageId,
            objectMapper.createObjectNode()
                .put("type", "ISSUE")
                // 조회 응답과 같은 이유로 문자열이다 — 64비트 id가 JSON 숫자로 나가면 깎인다.
                .put("issue_id", issueId.toString())
        )
    }

    /**
     * 이슈 보고의 거절을 타임라인에 남기고 Agent에도 알린다(ARTEL-366).
     *
     * [rejectWrite]와 같은 모양이되 응답 대상 판정이 없다 — 이슈는 타입이 하나뿐이고 그것은
     * 언제나 답을 기다린다.
     */
    private suspend fun rejectIssue(
        qaTryId: Long,
        qaTry: QaTryEntity,
        envelope: QaAgentEnvelope,
        reason: String
    ) {
        val sessionId = qaTry.agentSessionId
        if (sessionId == null) {
            appendError(qaTryId, envelope, reason)
            return
        }
        answerWithError(qaTryId, sessionId, envelope, reason)
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
