package kr.artel.orchestration.knowledge.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.dto.KnowledgeListResponse
import kr.artel.orchestration.knowledge.dto.KnowledgeMutationRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeAnchorEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEdgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEventEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEventType
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.knowledge.entity.PART_OF_RELATION
import kr.artel.orchestration.knowledge.repository.KnowledgeAnchorRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeEmbeddingRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeEventRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

/**
 * 통합 지식창고(knowledge) 저장/조회. docs 추출 경로와 QA WS 경로가 공통으로 이 서비스에 저장한다.
 *
 * 저장은 **배치**다: Agent가 준 항목 리스트를 검증해 유효한 것만 넣는다. 잘못된 항목(무효 tag,
 * 빈 summary/description)은 **throw하지 않고 건너뛰고 로그만 남긴다** — 한 항목의 오류가 문서
 * 추출 파이프라인이나 QA 런 전체를 깨서는 안 되기 때문이다.
 *
 * 항목 하나를 다루는 경로(생성/수정/소프트삭제, ARTEL-188)는 배치와 달리 결과를 [KnowledgeMutation]
 * **값으로** 돌려준다. 호출자가 QA WS 라우터라, 거절을 예외로 알리면 receive 파이프라인이 끊겨
 * 프레임 하나가 QA 런 전체를 실패시킨다.
 *
 * **쓰는 경로는 전부 `knowledge_event`에 이력을 남긴다**(ARTEL-255). 행 갱신과 이벤트 삽입은
 * 반드시 같은 트랜잭션이다 — 쪼개지면 `knowledge.version`과 이력의 최대 content 버전이 어긋난 채
 * 굳고, 그 상태는 아무도 알려 주지 않는다. QA 경로는 `qa_try_id`를 채우고 문서 경로는 null이며,
 * "어떤 런이 만든 지식을 나중 런이 지웠나"라는 결과 지표가 전부 그 구분 위에 선다.
 *
 * ## 스코프 (ARTEL-256)
 *
 * 모든 진입점이 [KnowledgeScope]를 **기본값 없이** 받는다. 읽기 경로를 하나라도 빠뜨리면 격리가
 * 뚫리고 뚫린 격리는 조용하므로, 빠뜨린 호출이 컴파일되지 않게 만든다. 이 서비스가 스코프를
 * `Long?`로 푸는 유일한 자리다 — 리포지토리 아래로는 그 값만 내려간다.
 *
 * 규칙은 두 줄이다.
 * - 읽기: baseline(`scope_id IS NULL`) + 자기 스코프. 그림자에 가려진 baseline은 뺀다.
 * - 쓰기: 항상 자기 스코프. 운영 런은 [KnowledgeScope.PRODUCTION]이라 이 변경 전과 동일하다.
 *
 * 스코프 런이 baseline을 고치거나 지울 때 **그 행을 직접 건드리지 않는다.** 대신 그 baseline을
 * 가리는 그림자 행을 자기 스코프에 만든다. 운영 지식창고가 실험 때문에 깎여나가면 실험이 끝나도
 * 되돌아오지 않기 때문이다.
 *
 * 그림자도 knowledge 행이므로 자기 이력을 남긴다. 다만 **스코프 행은 `knowledge_entry_facts`
 * view에서 빠진다**(V27) — ARTEL-255의 지표는 "후속 런이 앞선 런의 지식을 지웠나"이고, 실험
 * 스코프에는 심판이 될 후속 런이 없다. 넣으면 실험 산물이 운영 지표를 오염시킨다.
 */
@Service
class KnowledgeService(
    private val knowledgeRepository: KnowledgeRepository,
    private val anchorRepository: KnowledgeAnchorRepository,
    private val embeddingRepository: KnowledgeEmbeddingRepository,
    private val eventRepository: KnowledgeEventRepository,
    private val edgeRepository: KnowledgeEdgeRepository,
    private val transactionalOperator: TransactionalOperator,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(KnowledgeService::class.java)

    /**
     * 한 출처(문서/QA 런)에서 온 knowledge 항목 배치를 저장한다.
     * 유효 항목이 하나도 없으면 아무것도 저장하지 않는다.
     *
     * 저장과 CREATE 이벤트 기록은 한 트랜잭션이다. 배치가 부분 저장되는 실패 모드는 원래도
     * 없었고(`saveAll` 한 번), 실패는 호출자가 이미 삼켜 `parse_status=FAILED`로 남긴다.
     *
     * **`source`가 [KnowledgeSource.DOCS]면 항목마다 문서 node로 향하는 `PART_OF` edge를 함께
     * 만든다(ARTEL-748).** 자세한 내용은 [linkItemsToDocumentNode]에 있다. 유효 항목이 하나도
     * 없어 위 early return을 타면 문서 node도 edge도 만들지 않는다 — 항목이 하나도 안 매달린
     * 문서 node는 그래프에 남은 외톨이 점이라 만들 이유가 없다.
     *
     * @param scope 이 배치가 들어갈 스코프. 문서 추출 경로는 언제나 [KnowledgeScope.PRODUCTION]이고
     *   (사람이 올린 문서는 실험의 산물이 아니다), QA 경로는 그 런의 스코프다.
     * @param documentFileName `source`가 [KnowledgeSource.DOCS]일 때만 쓰는 문서 node의 `summary`.
     *   그 경우 필수다 — 없이 문서 node를 만들면 `summary`를 지어내야 하고, 그것은 호출자의
     *   버그이지 사용자 입력 문제가 아니다. QA 경로는 문서 node가 없으므로 그냥 null이다.
     */
    suspend fun store(
        projectId: Long,
        scope: KnowledgeScope,
        source: KnowledgeSource,
        sourceId: Long?,
        contentHash: String?,
        items: List<KnowledgeIngestItem>,
        documentFileName: String? = null
    ) {
        val rows = items.mapNotNull { toEntity(projectId, scope, source, sourceId, contentHash, it) }
        if (rows.isEmpty()) {
            logger.warn(
                "knowledge 저장 스킵: 유효 항목 없음 (project={}, scope={}, source={}, sourceId={}, 받은수={})",
                projectId, scope, source, sourceId, items.size
            )
            return
        }
        // QA 배치의 source_id는 그 런(qa_try.id)이고 문서 배치는 project_document.id다(V13).
        // 이벤트의 qa_try_id는 전자일 때만 채워야 하며, 그 구분이 곧 "런에 귀속되는 지식"의 정의다.
        val qaTryId = sourceId.takeIf { source == KnowledgeSource.QA }
        transactionalOperator.executeAndAwait {
            // saveAll은 콜드 Flow라 반드시 소비해야 실제 저장이 일어난다.
            val saved = knowledgeRepository.saveAll(rows).toList()
            eventRepository.saveAll(saved.map { contentEvent(it, KnowledgeEventType.CREATE, qaTryId) }).toList()
            if (source == KnowledgeSource.DOCS) {
                val documentId = requireNotNull(sourceId) { "DOCS 배치는 sourceId(documentId)가 필요합니다." }
                val fileName = requireNotNull(documentFileName) { "DOCS 배치는 documentFileName이 필요합니다." }
                linkItemsToDocumentNode(projectId, scope, documentId, fileName, contentHash, saved)
            }
        }
    }

    /**
     * DOCS 배치가 저장된 뒤, 문서 node를 찾거나 만들고 방금 저장한 항목마다 `PART_OF` edge를
     * 잇는다(ARTEL-748). 호출자([store])의 트랜잭션 안에서 돈다.
     *
     * edge는 **이번 배치에서 방금 저장된 항목에만** 건다. 앞선 적재의 항목은 이미 자기 edge를
     * 갖고 있고, 다시 걸면 `uq_knowledge_edge_live`에 걸린다(이미 적재된 기존 항목의 소급 연결은
     * 이 이슈의 non-goal이다).
     */
    private suspend fun linkItemsToDocumentNode(
        projectId: Long,
        scope: KnowledgeScope,
        documentId: Long,
        fileName: String,
        contentHash: String?,
        items: List<KnowledgeEntity>
    ) {
        val documentNode = knowledgeRepository.findDocumentNode(projectId, documentId)
            ?: createDocumentNode(projectId, scope, documentId, fileName, contentHash)
        val documentNodeId = requireNotNull(documentNode.id)
        val edges = items.map { item ->
            KnowledgeEdgeEntity(
                projectId = projectId,
                scopeId = scope.id,
                fromKnowledgeId = requireNotNull(item.id),
                toKnowledgeId = documentNodeId,
                relation = PART_OF_RELATION,
                note = DOCUMENT_PART_OF_NOTE,
                createdByQaTryId = null
            )
        }
        // saveAll은 콜드 Flow라 반드시 소비해야 실제 저장이 일어난다(knowledgeRepository.saveAll과 같다).
        edgeRepository.saveAll(edges).toList()
    }

    /**
     * 문서 node를 새로 만든다. `findDocumentNode`가 이미 없다고 확인한 뒤에만 불린다.
     *
     * - `source`/`source_id`는 그 배치의 항목들과 완전히 같다(`DOCS`, `project_document.id`) —
     *   문서 node도 그 문서에서 온 knowledge 행이라는 것이 이 값의 뜻이다.
     * - `summary`는 파일 이름이다(이슈가 정한 값).
     * - `tag`는 기존 다섯 값 중 [KnowledgeTag.MISC]를 쓴다. 이 node는 topic 분류 대상인 게임
     *   지식이 아니라 구조적 표지라 그나마 제일 안 틀린 값이다. [KnowledgeTag]의 KDoc은 "MISC는
     *   검색 대상에 남긴다"고 적어 뒀지만, 이 node의 검색 배제는 tag가 아니라
     *   [kr.artel.orchestration.knowledge.repository.KnowledgeEmbeddingRepository]의 백필 시딩
     *   배제로 걸리므로 그 문서와 충돌하지 않는다.
     * - `description`(이슈가 구현에 맡긴 값)에는 파일 이름·documentId를 반복하지 않는다 — 이미
     *   `summary`/`source_id` 컬럼에 있다. 대신 **이 node의 역할**을 적는다: 문서에서 뽑아낸
     *   사실이 아니라 그 문서의 항목들이 매달리는 구조적 표지라는 것. 그래야 이 node를 단건으로
     *   읽는 사람(항목 단건 조회 API, ARTEL-753)이 게임 지식으로 착각하지 않는다.
     * - `content_hash`는 배치와 같은 값을 쓴다 — 항목들과 같은 취급이다.
     *
     * **문서 node를 새로 만들 때 `knowledge_event` CREATE 이벤트를 같은 트랜잭션에서 함께
     * 남긴다.** 이 클래스의 불변식이 "행 갱신과 이벤트 삽입은 반드시 같은 트랜잭션"이고, 빠뜨리면
     * 이 행만 `knowledge.version = max(event.version)`이 깨진 채 굳는다. `qaTryId`는 null이다 —
     * 문서 경로에는 런이 없다.
     */
    private suspend fun createDocumentNode(
        projectId: Long,
        scope: KnowledgeScope,
        documentId: Long,
        fileName: String,
        contentHash: String?
    ): KnowledgeEntity {
        val saved = knowledgeRepository.save(
            KnowledgeEntity(
                projectId = projectId,
                scopeId = scope.id,
                source = KnowledgeSource.DOCS.name,
                sourceId = documentId,
                contentHash = contentHash,
                tag = KnowledgeTag.MISC.name,
                summary = fileName,
                description = DOCUMENT_NODE_DESCRIPTION
            )
        )
        eventRepository.save(contentEvent(saved, KnowledgeEventType.CREATE, qaTryId = null))
        logger.info(
            "문서 node 생성: id={}, project={}, document={}, fileName={}",
            saved.id, projectId, documentId, fileName
        )
        return saved
    }

    /** 유효하지 않은 항목은 null을 돌려 배치에서 제외한다. */
    private fun toEntity(
        projectId: Long,
        scope: KnowledgeScope,
        source: KnowledgeSource,
        sourceId: Long?,
        contentHash: String?,
        item: KnowledgeIngestItem
    ): KnowledgeEntity? {
        val tag = KnowledgeTag.fromWire(item.tag)
        if (tag == null) {
            logger.warn("knowledge 항목 스킵: 잘못된 tag={} (project={}, source={})", item.tag, projectId, source)
            return null
        }
        val summary = item.summary?.trim()
        val description = item.description?.trim()
        if (summary.isNullOrEmpty() || description.isNullOrEmpty()) {
            logger.warn("knowledge 항목 스킵: summary/description 비어있음 (project={}, tag={})", projectId, tag)
            return null
        }
        return KnowledgeEntity(
            projectId = projectId,
            scopeId = scope.id,
            source = source.name,
            sourceId = sourceId,
            contentHash = contentHash,
            tag = tag.name,
            summary = summary,
            description = description
        )
    }

    /**
     * QA 런이 관측한 지식 한 건을 새로 넣는다.
     *
     * source는 런에서 왔으므로 QA로, source_id는 그 런(qa_try.id)으로 고정한다 — 배치 인입과 같은
     * 규칙이라 나중에 "이 항목은 어느 런이 만들었나"를 두 경로에서 같은 방식으로 읽을 수 있다.
     * 생성의 출처는 그 source_id다. `updated_by_qa_try_id`는 비워 둬야 "만들어진 뒤 누가 손댔나"의
     * 신호로 쓸 수 있다.
     *
     * 새로 만드는 항목은 가릴 baseline이 없으므로 그림자가 아니다 — 스코프 런에서도 `shadows_id`는
     * 비어 있고, 그 스코프 안에서만 보이는 평범한 항목이 된다.
     *
     * **앵커(ARTEL-591)는 같은 트랜잭션에서 만든다.** 쪼개져 지식만 저장되면 그 항목은 화면 지식을
     * 뜻하고 있으면서 게임 전체 지식으로 보이고, 그 상태는 아무도 알려 주지 않는다.
     */
    suspend fun createFromQaTry(
        projectId: Long,
        scope: KnowledgeScope,
        qaTryId: Long,
        request: KnowledgeMutationRequest
    ): KnowledgeMutation {
        val tag = KnowledgeTag.fromWire(request.tag)
            ?: return KnowledgeMutation.Rejected("tag must be one of ${KnowledgeTag.NAMES}")
        val summary = request.summary?.trim()
        val description = request.description?.trim()
        if (summary.isNullOrEmpty() || description.isNullOrEmpty()) {
            return KnowledgeMutation.Rejected("summary and description are required")
        }
        val anchor = when (val parsed = parseAnchor(request)) {
            is AnchorRequest.Invalid -> return KnowledgeMutation.Rejected(parsed.reason)
            is AnchorRequest.Absent -> null
            is AnchorRequest.At -> parsed
        }
        val saved = transactionalOperator.executeAndAwait {
            val row = knowledgeRepository.save(
                KnowledgeEntity(
                    projectId = projectId,
                    scopeId = scope.id,
                    source = KnowledgeSource.QA.name,
                    sourceId = qaTryId,
                    tag = tag.name,
                    summary = summary,
                    description = description
                )
            )
            eventRepository.save(contentEvent(row, KnowledgeEventType.CREATE, qaTryId))
            if (anchor != null) {
                anchorRepository.save(
                    KnowledgeAnchorEntity(
                        knowledgeId = requireNotNull(row.id),
                        sceneName = anchor.sceneName,
                        screenId = anchor.screenId
                    )
                )
            }
            row
        }
        return KnowledgeMutation.Applied(requireNotNull(saved?.id))
    }

    /**
     * 생성 요청이 실은 앵커를 읽는다(ARTEL-591).
     *
     * 세 가지다. 앵커를 안 실었거나([AnchorRequest.Absent]), 실었거나([AnchorRequest.At]),
     * 실었는데 말이 안 되거나([AnchorRequest.Invalid]).
     *
     * - **`scene_name`이 없으면 앵커가 아니다.** 그것이 기본값이고, 그 지식은 게임 전체의 사실이다.
     * - **`screen_id`만 온 요청은 거절한다.** 화면은 씬 안에 살고(V55), 씬을 모르는 화면 앵커는
     *   나중에 어느 씬의 화면이었는지 되짚을 수 없다. 조용히 씬만 비운 채 저장하면 그 앵커는
     *   영영 반쪽인 채로 남는다.
     * - **씬 이름을 content map과 대조하지 않는다.** content map이 없는 프로젝트도 씬 이름은 있고,
     *   검증하면 그 프로젝트에서 오는 앵커가 전부 거절된다(V55).
     */
    private fun parseAnchor(request: KnowledgeMutationRequest): AnchorRequest {
        val sceneName = request.sceneName?.trim()
        val rawScreenId = request.screenId?.trim()?.takeIf { it.isNotEmpty() }
        if (sceneName.isNullOrEmpty()) {
            return if (rawScreenId == null) {
                AnchorRequest.Absent
            } else {
                AnchorRequest.Invalid("scene_name is required when screen_id is given")
            }
        }
        val screenId = rawScreenId?.toLongOrNull()
        if (rawScreenId != null && screenId == null) {
            return AnchorRequest.Invalid("screen_id must be a numeric id")
        }
        return AnchorRequest.At(sceneName, screenId)
    }

    /** [parseAnchor]가 읽어낸 앵커 요청. */
    private sealed interface AnchorRequest {
        /** 앵커를 싣지 않은 요청. 게임 전체의 사실이다. */
        data object Absent : AnchorRequest
        data class At(val sceneName: String, val screenId: Long?) : AnchorRequest
        data class Invalid(val reason: String) : AnchorRequest
    }

    /**
     * 항목 하나를 고친다. 준 필드만 바뀌고 나머지는 그대로다.
     *
     * **본문(summary/description)이 실제로 바뀌면 그 항목의 임베딩을 무효화한다.** 옛 본문에서 만든
     * 검색쿼리 벡터가 남아 있으면 바뀌기 전 내용으로 검색되어 바뀐 내용이 나온다. 저장과 무효화는
     * 한 트랜잭션이어야 한다 — 쪼개져 저장만 성공하면 그 잘못된 상태가 그대로 굳는다.
     * tag만 바뀐 경우에는 무효화하지 않는다: 임베딩 입력은 summary/description뿐이라 벡터가 그대로
     * 유효하고, 무효화하면 값이 같은 벡터를 다시 청구하게 된다.
     *
     * **버전 판정과 임베딩 판정은 일부러 다르다**(ARTEL-255). 버전은 tag를 포함한 content 셋이
     * 바뀌면 오른다 — 이벤트의 `after`가 셋을 통째로 스냅샷하므로, tag 변경에 버전을 안 올리면
     * 그 버전의 스냅샷이 행과 어긋나고 `knowledge.version = max(event.version)` 불변식이 깨진다.
     * 임베딩은 위 이유로 summary/description만 본다.
     *
     * 값이 하나도 실제로 바뀌지 않은 요청은 버전을 올리지 않고 이벤트도 남기지 않는다 — 같은
     * `after`를 가진 이벤트가 쌓이면 이력이 "몇 번 호출됐나"의 기록이 되어 버린다. 행 저장 자체와
     * `updated_by_qa_try_id` 기록은 그대로 한다(누가 손댔는지는 여전히 사실이다).
     *
     * **스코프 런이 baseline을 고치면 그 행 대신 그림자를 만든다**(ARTEL-256). 그림자에는 원본의
     * 모든 필드를 복사한 뒤 요청분을 얹는다 — 그림자가 그 스코프에서 원본을 완전히 대체하므로
     * 일부만 담으면 안 고친 필드가 사라진다. 이때 **원본의 임베딩은 건드리지 않는다**: 그 벡터는
     * 운영과 다른 스코프가 계속 쓰고 있다. 그림자의 벡터는 백필이 새로 채운다.
     */
    suspend fun updateFromQaTry(
        projectId: Long,
        scope: KnowledgeScope,
        qaTryId: Long,
        request: KnowledgeMutationRequest
    ): KnowledgeMutation {
        val knowledgeId = parseKnowledgeId(request.knowledgeId)
            ?: return KnowledgeMutation.Rejected("knowledge_id must be a numeric id")
        if (request.tag == null && request.summary == null && request.description == null) {
            return KnowledgeMutation.Rejected("at least one of tag, summary, description is required")
        }
        val tag = request.tag?.let {
            KnowledgeTag.fromWire(it) ?: return KnowledgeMutation.Rejected("tag must be one of ${KnowledgeTag.NAMES}")
        }
        val summary = request.summary?.trim()
        val description = request.description?.trim()
        if (summary != null && summary.isEmpty()) return KnowledgeMutation.Rejected("summary must not be blank")
        if (description != null && description.isEmpty()) {
            return KnowledgeMutation.Rejected("description must not be blank")
        }

        // 프로젝트·스코프 격리: 다른 프로젝트나 다른 스코프의 id를 넣으면 여기서 행이 잡히지 않는다.
        val current = knowledgeRepository.findVisibleById(knowledgeId, projectId, scope.id)
            ?: return KnowledgeMutation.Rejected(describeMissing(knowledgeId, projectId, scope))

        val next = current.copy(
            tag = tag?.name ?: current.tag,
            summary = summary ?: current.summary,
            description = description ?: current.description,
            updatedByQaTryId = qaTryId
        )
        val embeddedTextChanged = next.summary != current.summary || next.description != current.description
        val contentChanged = embeddedTextChanged || next.tag != current.tag

        if (shadowRequired(current, scope)) {
            val shadow = transactionalOperator.executeAndAwait {
                val row = knowledgeRepository.save(
                    next.copy(
                        id = null,
                        scopeId = scope.id,
                        shadowsId = current.id,
                        // 그림자는 이 스코프에 처음 생긴 행이다. 버전을 원본에서 물려받으면 이벤트가
                        // 없는 채로 version이 2 이상이 되어 `knowledge.version = max(event.version)`
                        // 불변식이 깨진다(ARTEL-255). 생성·수정 시각도 물려받으면 언제 갈라져 나왔는지를
                        // 잃는다.
                        version = 1,
                        createdAt = null,
                        updatedAt = null
                    )
                )
                // 행 수준에서 이것은 CREATE다 — 이 스코프에 없던 행이 이 런에 의해 생겼다.
                // "baseline을 고친 것"이라는 사실은 이벤트가 아니라 `shadows_id`가 진다.
                eventRepository.save(contentEvent(row, KnowledgeEventType.CREATE, qaTryId))
                row
            }
            logger.info(
                "knowledge 스코프 수정(그림자 생성): baseline={}, shadow={}, project={}, scope={}, qaTry={}",
                knowledgeId, shadow?.id, projectId, scope, qaTryId
            )
            return KnowledgeMutation.Applied(requireNotNull(shadow?.id))
        }

        val versioned = if (contentChanged) next.copy(version = current.version + 1) else next
        transactionalOperator.executeAndAwait {
            knowledgeRepository.save(versioned)
            if (contentChanged) {
                eventRepository.save(contentEvent(versioned, KnowledgeEventType.UPDATE, qaTryId))
            }
            if (embeddedTextChanged) embeddingRepository.discardFor(knowledgeId)
        }
        logger.info(
            "knowledge 수정: id={}, project={}, scope={}, qaTry={}, version={}, 임베딩 무효화={}",
            knowledgeId, projectId, scope, qaTryId, versioned.version, embeddedTextChanged
        )
        return KnowledgeMutation.Applied(knowledgeId)
    }

    /**
     * 항목 하나를 소프트삭제한다. 행은 남고 `deleted_at`만 채워지며, 되살리기는 그 값을 NULL로
     * 되돌리는 것으로 끝난다(`deleted_by_qa_try_id`는 감사 기록이라 남겨 둔다).
     *
     * 임베딩 행도 함께 버린다. 읽기 경로가 `deleted_at`을 걸어 이미 빠지지만, 벡터까지 지워 두면
     * 검색이 조인 조건을 빠뜨려도 삭제된 항목이 되살아나지 않는다. 되살리면 백필이 다시 채운다.
     *
     * **스코프 런이 baseline을 지우면 원본은 그대로 두고 툼스톤 그림자를 만든다**(ARTEL-256).
     * 그 런에서는 사라지지만 운영과 다른 스코프에는 남는다. 원본의 임베딩도 그래서 버리지 않는다 —
     * 그 벡터는 아직 쓰이고 있다. 툼스톤 자신은 `deleted_at`이 찍혀 있어 백필 큐에 들어가지 않는다.
     */
    suspend fun softDeleteFromQaTry(
        projectId: Long,
        scope: KnowledgeScope,
        qaTryId: Long,
        request: KnowledgeMutationRequest
    ): KnowledgeMutation {
        val knowledgeId = parseKnowledgeId(request.knowledgeId)
            ?: return KnowledgeMutation.Rejected("knowledge_id must be a numeric id")
        val current = knowledgeRepository.findVisibleById(knowledgeId, projectId, scope.id)
            ?: return KnowledgeMutation.Rejected(describeMissing(knowledgeId, projectId, scope))

        val deletedAt = Instant.now(clock)
        if (shadowRequired(current, scope)) {
            val tombstone = transactionalOperator.executeAndAwait {
                val row = knowledgeRepository.save(
                    current.copy(
                        id = null,
                        scopeId = scope.id,
                        shadowsId = current.id,
                        deletedAt = deletedAt,
                        deletedByQaTryId = qaTryId,
                        // 그림자와 같은 이유로 버전을 물려받지 않는다.
                        version = 1,
                        createdAt = null,
                        updatedAt = null
                    )
                )
                // DELETE 이벤트만 남기고 CREATE는 남기지 않는다 — 이 런은 지식을 만든 것이 아니라
                // 가린 것이고, `after`가 null이라 content 버전을 만들지도 않는다.
                eventRepository.save(
                    KnowledgeEventEntity(
                        knowledgeId = requireNotNull(row.id),
                        projectId = row.projectId,
                        qaTryId = qaTryId,
                        event = KnowledgeEventType.DELETE.name,
                        version = row.version,
                        after = null,
                        createdAt = deletedAt
                    )
                )
                row
            }
            logger.info(
                "knowledge 스코프 삭제(툼스톤 생성): baseline={}, tombstone={}, project={}, scope={}, qaTry={}",
                knowledgeId, tombstone?.id, projectId, scope, qaTryId
            )
            return KnowledgeMutation.Applied(requireNotNull(tombstone?.id))
        }

        transactionalOperator.executeAndAwait {
            knowledgeRepository.save(current.copy(deletedAt = deletedAt, deletedByQaTryId = qaTryId))
            // version을 올리지 않고 현재 값을 그대로 싣는다 — 삭제는 본문을 바꾸지 않는다.
            // `after`가 null이라 부분 유니크 인덱스에 걸리지 않고, 같은 항목을 지웠다 되살리기를
            // 반복해도 이력이 계속 쌓인다.
            eventRepository.save(
                KnowledgeEventEntity(
                    knowledgeId = knowledgeId,
                    projectId = current.projectId,
                    qaTryId = qaTryId,
                    event = KnowledgeEventType.DELETE.name,
                    version = current.version,
                    after = null,
                    createdAt = deletedAt
                )
            )
            embeddingRepository.discardFor(knowledgeId)
        }
        // 지우는 주체가 Agent라 삭제는 반드시 눈에 보여야 한다. 출처는 컬럼에도 남는다.
        logger.info(
            "knowledge 소프트삭제: id={}, project={}, scope={}, qaTry={}",
            knowledgeId, projectId, scope, qaTryId
        )
        return KnowledgeMutation.Applied(knowledgeId)
    }

    /**
     * 문서 한 건이 만든 baseline knowledge를 전부 소프트삭제한다(ARTEL-728, ARTEL-748).
     *
     * [KnowledgeRepository.findBaselineByDocumentId]가 이미 `source = 'DOCS'`,
     * `scope_id IS NULL`, `deleted_at IS NULL`을 걸어 대상만 돌려주므로, 여기서는 그 각 행에
     * [softDeleteFromQaTry]의 스코프 없는(운영) 갈래와 같은 모양으로 `deleted_at`을 찍고 DELETE
     * 이벤트를 남긴다. 스코프 런의 그림자 처리는 이 경로에 없다 — 문서가 지운 것은 baseline
     * 뿐이고, 스코프가 만든 그림자는 그 스코프 자신의 상태라 함께 지우지 않는다(리포지토리
     * KDoc 참조).
     *
     * **문서 node 자신도 이 [rows]에 이미 들어 있다** — 문서 node도 `source`/`source_id`/`scope_id`/
     * `deleted_at` 조건을 항목과 똑같이 만족하는 knowledge 행이라, 위 루프가 별도 분기 없이
     * 함께 지운다. 이 함수가 따로 처리해야 하는 것은 **그 node를 향한 `PART_OF` edge**뿐이다 —
     * knowledge 소프트삭제는 `knowledge_edge` 행을 건드리지 않으므로, 문서 node가 사라진 뒤에도
     * edge는 살아있는 채로 남아 지워진 node를 계속 가리키게 된다.
     *
     * `deletedByQaTryId`는 채우지 않는다. 이 삭제를 일으킨 것은 QA 런이 아니라 문서 삭제
     * 요청이다. [KnowledgeEventEntity.qaTryId]도 같은 이유로 null이다 — 그 컬럼의 KDoc이 이미
     * "사람/문서 경로는 null"이라고 정해 두었다. DELETE 이벤트의 `version`은 현재 값을 그대로
     * 싣는다 — 삭제는 본문을 바꾸지 않으므로 새 content 버전을 만들지 않는다. edge에는 `version`도
     * `knowledge_event`도 없으므로(ARTEL-274) 그 부분은 knowledge와 똑같이 따라 하지 않는다 —
     * `deletedAt`만 채운다([KnowledgeGraphService.unlink]의 운영 갈래와 같은 모양이다).
     *
     * 행마다 [embeddingRepository]에서 임베딩도 버린다 — 벡터가 남아 있으면 검색이 조인 조건을
     * 빠뜨려도 지운 항목이 되살아난다.
     *
     * @return 소프트삭제한 knowledge 행 수(문서 node 포함, edge 수는 포함하지 않는다).
     */
    suspend fun softDeleteForDocument(projectId: Long, documentId: Long): Int {
        val deletedAt = Instant.now(clock)
        val rows = knowledgeRepository.findBaselineByDocumentId(projectId, documentId).toList()
        // findDocumentNode는 findBaselineByDocumentId와 별개의 질의라 이 문서에 문서 node가 없으면
        // (예: 아직 이 브랜치 이전에 적재된 문서, 또는 항목이 하나도 없어 문서 node를 만든 적 없는
        // 문서) null이다 — 그때는 지울 PART_OF edge도 없다.
        val documentNodeId = knowledgeRepository.findDocumentNode(projectId, documentId)?.id
        val edges = documentNodeId?.let { edgeRepository.findBaselinePartOfEdgesTo(projectId, it).toList() }
            .orEmpty()
        transactionalOperator.executeAndAwait {
            rows.forEach { current ->
                knowledgeRepository.save(current.copy(deletedAt = deletedAt))
                eventRepository.save(
                    KnowledgeEventEntity(
                        knowledgeId = requireNotNull(current.id),
                        projectId = current.projectId,
                        qaTryId = null,
                        event = KnowledgeEventType.DELETE.name,
                        version = current.version,
                        after = null,
                        createdAt = deletedAt
                    )
                )
                embeddingRepository.discardFor(requireNotNull(current.id))
            }
            edges.forEach { edge -> edgeRepository.save(edge.copy(deletedAt = deletedAt)) }
        }
        logger.info(
            "문서 삭제로 knowledge 소프트삭제: project={}, document={}, 지운수={}, PART_OF edge 지운수={}",
            projectId, documentId, rows.size, edges.size
        )
        return rows.size
    }

    /**
     * 이 쓰기가 원본 대신 그림자로 가야 하는가.
     *
     * 스코프 런이 baseline(`scope_id IS NULL`)을 건드릴 때만 참이다. 운영 런은 스코프가 없으니
     * 언제나 원본을 직접 고치고(이 변경 전과 동일), 스코프 런이 **자기 스코프의 행**(자기가 만든
     * 항목이든 앞서 만든 그림자든)을 고칠 때도 그 행이 이미 자기 것이라 직접 고친다.
     */
    private fun shadowRequired(target: KnowledgeEntity, scope: KnowledgeScope): Boolean =
        !scope.isProduction && target.scopeId == null

    /**
     * 대상을 못 찾은 이유를 Agent가 다음 행동을 정할 수 있는 문장으로 만든다.
     *
     * 스코프 런에서 "없다"는 세 가지다: 정말 없거나, 이 스코프에서 이미 지웠거나(툼스톤), 이미
     * 고쳐서 그림자가 원본을 대신하고 있거나. 셋을 한 문장으로 뭉개면 Agent가 방금 자기가 고친
     * 항목을 옛 id로 계속 다시 부르며 런을 태운다. 고친 경우에는 새 id를 알려 준다.
     *
     * 다른 스코프에 대해서는 아무것도 말하지 않는다 — 자기 스코프의 그림자만 조회한다.
     */
    private suspend fun describeMissing(knowledgeId: Long, projectId: Long, scope: KnowledgeScope): String {
        val notFound = "knowledge $knowledgeId not found in project $projectId"
        val scopeId = scope.id ?: return notFound
        val shadow = knowledgeRepository.findShadow(scopeId, knowledgeId) ?: return notFound
        return if (shadow.deletedAt != null) {
            "knowledge $knowledgeId is already deleted in this run's knowledge scope"
        } else {
            "knowledge $knowledgeId was modified in this run's knowledge scope; use id ${shadow.id}"
        }
    }

    /**
     * content를 만든 이벤트(CREATE/UPDATE) 한 건을 짓는다.
     *
     * [entity]는 **저장된 뒤의** 행이어야 한다 — `after`가 그 시점의 content 스냅샷이고,
     * `version`도 거기서 온다. 저장 전 값으로 지으면 이력이 행보다 한 발 앞서거나 뒤처진다.
     */
    private fun contentEvent(
        entity: KnowledgeEntity,
        event: KnowledgeEventType,
        qaTryId: Long?
    ) = KnowledgeEventEntity(
        knowledgeId = requireNotNull(entity.id),
        projectId = entity.projectId,
        qaTryId = qaTryId,
        event = event.name,
        version = entity.version,
        after = contentSnapshot(entity),
        createdAt = Instant.now(clock)
    )

    /**
     * 이벤트에 실을 content 스냅샷.
     *
     * 문자열을 손으로 잇지 않고 [ObjectMapper]를 태운다 — summary/description은 Agent가 만든
     * 자유 텍스트라 따옴표와 줄바꿈이 그대로 들어온다.
     */
    private fun contentSnapshot(entity: KnowledgeEntity): Json =
        Json.of(
            objectMapper.writeValueAsString(
                mapOf(
                    "tag" to entity.tag,
                    "summary" to entity.summary,
                    "description" to entity.description
                )
            )
        )

    private fun parseKnowledgeId(raw: String?): Long? = raw?.trim()?.toLongOrNull()

    /**
     * 프로젝트 스코프 조회(최신순). source/tag는 선택 필터.
     * 소프트삭제된 항목과 스코프 밖 항목은 리포지토리 쿼리 단계에서 이미 빠진다.
     */
    suspend fun findForProject(
        projectId: Long,
        scope: KnowledgeScope,
        source: KnowledgeSource?,
        tag: KnowledgeTag?
    ): KnowledgeListResponse =
        KnowledgeListResponse(
            knowledgeRepository.findVisible(projectId, scope.id, source?.name, tag?.name)
                .map(::toResponse)
                .toList()
        )

    private fun toResponse(entity: KnowledgeEntity): KnowledgeResponse =
        KnowledgeResponse(
            id = entity.id.toString(),
            projectId = entity.projectId.toString(),
            source = entity.source,
            sourceId = entity.sourceId?.toString(),
            contentHash = entity.contentHash,
            tag = entity.tag,
            summary = entity.summary,
            description = entity.description,
            createdAt = entity.createdAt ?: Instant.EPOCH
        )

    private companion object {
        /**
         * 문서 node의 `description`(ARTEL-748, 이슈가 구현에 맡긴 값).
         *
         * 파일 이름과 documentId는 이미 `summary`/`source_id` 컬럼에 있으므로 여기서 반복하지
         * 않는다. 대신 이 node를 단건으로 읽는 사람(항목 단건 조회 API, ARTEL-753)이 게임 지식으로
         * 착각하지 않도록 **역할**을 적는다 — 문서에서 뽑아낸 사실이 아니라 그 항목들이 매달리는
         * 구조적 표지라는 것.
         */
        const val DOCUMENT_NODE_DESCRIPTION =
            "이 node는 문서에서 뽑아낸 사실이 아니라, 그 문서에서 추출된 knowledge 항목들이 매달리는" +
                " 구조적 표지다. 항목마다 이 node를 향해 `PART_OF` edge를 건다."

        /**
         * 문서 적재가 만드는 `PART_OF` edge의 `note`(ARTEL-748, 이슈가 구현에 맡긴 값). `note`는
         * NOT NULL이라 적재 경로도 값을 채워야 한다.
         *
         * QA 런의 note와 의도적으로 다르게 쓴다 — [kr.artel.orchestration.knowledge.entity.KnowledgeEdgeEntity.note]의
         * KDoc대로 QA 런의 note는 **런이 주장한 이유**("왜 이 관계인가")를 진다. 문서 적재에는
         * 주장하는 런도 이유도 없다 — 추출 파이프라인이 결정적으로 한 일이다. 그래서 이 문장은
         * 이유가 아니라 **무엇을 했는지**를 진다.
         */
        const val DOCUMENT_PART_OF_NOTE = "문서 추출 파이프라인이 이 항목을 문서 node 아래에 자동으로 배치했다."
    }
}

/**
 * 항목 하나를 다루는 쓰기의 결과(ARTEL-188).
 *
 * 거절을 예외가 아니라 값으로 돌려주는 이유는 호출자가 QA WS 라우터이기 때문이다. throw하면
 * receive 파이프라인이 onError로 끊겨 WS가 닫히고, 그것이 onDisconnect로 이어져 QA 런 전체가
 * 실패한다. 잘못된 프레임 하나는 ERROR 로그로 떨어지고 런은 계속되어야 한다.
 */
sealed interface KnowledgeMutation {
    data class Applied(val knowledgeId: Long) : KnowledgeMutation
    data class Rejected(val reason: String) : KnowledgeMutation
}
