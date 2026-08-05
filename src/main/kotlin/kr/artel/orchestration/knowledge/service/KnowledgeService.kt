package kr.artel.orchestration.knowledge.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.dto.KnowledgeListResponse
import kr.artel.orchestration.knowledge.dto.KnowledgeMutationRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEventEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEventType
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.knowledge.repository.KnowledgeEmbeddingRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeEventRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kotlinx.coroutines.flow.Flow
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
 */
@Service
class KnowledgeService(
    private val knowledgeRepository: KnowledgeRepository,
    private val embeddingRepository: KnowledgeEmbeddingRepository,
    private val eventRepository: KnowledgeEventRepository,
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
     */
    suspend fun store(
        projectId: Long,
        source: KnowledgeSource,
        sourceId: Long?,
        contentHash: String?,
        items: List<KnowledgeIngestItem>
    ) {
        val rows = items.mapNotNull { toEntity(projectId, source, sourceId, contentHash, it) }
        if (rows.isEmpty()) {
            logger.warn(
                "knowledge 저장 스킵: 유효 항목 없음 (project={}, source={}, sourceId={}, 받은수={})",
                projectId, source, sourceId, items.size
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
        }
    }

    /** 유효하지 않은 항목은 null을 돌려 배치에서 제외한다. */
    private fun toEntity(
        projectId: Long,
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
     */
    suspend fun createFromQaTry(
        projectId: Long,
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
        val saved = transactionalOperator.executeAndAwait {
            val row = knowledgeRepository.save(
                KnowledgeEntity(
                    projectId = projectId,
                    source = KnowledgeSource.QA.name,
                    sourceId = qaTryId,
                    tag = tag.name,
                    summary = summary,
                    description = description
                )
            )
            eventRepository.save(contentEvent(row, KnowledgeEventType.CREATE, qaTryId))
            row
        }
        return KnowledgeMutation.Applied(requireNotNull(saved?.id))
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
     */
    suspend fun updateFromQaTry(
        projectId: Long,
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

        // 프로젝트 격리: 다른 프로젝트의 id를 넣으면 여기서 행이 잡히지 않는다.
        val current = knowledgeRepository.findByIdAndProjectIdAndDeletedAtIsNull(knowledgeId, projectId)
            ?: return KnowledgeMutation.Rejected("knowledge $knowledgeId not found in project $projectId")

        val updated = current.copy(
            tag = tag?.name ?: current.tag,
            summary = summary ?: current.summary,
            description = description ?: current.description,
            updatedByQaTryId = qaTryId
        )
        val embeddedTextChanged =
            updated.summary != current.summary || updated.description != current.description
        val contentChanged = embeddedTextChanged || updated.tag != current.tag
        val versioned = if (contentChanged) updated.copy(version = current.version + 1) else updated

        transactionalOperator.executeAndAwait {
            knowledgeRepository.save(versioned)
            if (contentChanged) {
                eventRepository.save(contentEvent(versioned, KnowledgeEventType.UPDATE, qaTryId))
            }
            if (embeddedTextChanged) embeddingRepository.discardFor(knowledgeId)
        }
        logger.info(
            "knowledge 수정: id={}, project={}, qaTry={}, version={}, 임베딩 무효화={}",
            knowledgeId, projectId, qaTryId, versioned.version, embeddedTextChanged
        )
        return KnowledgeMutation.Applied(knowledgeId)
    }

    /**
     * 항목 하나를 소프트삭제한다. 행은 남고 `deleted_at`만 채워지며, 되살리기는 그 값을 NULL로
     * 되돌리는 것으로 끝난다(`deleted_by_qa_try_id`는 감사 기록이라 남겨 둔다).
     *
     * 임베딩 행도 함께 버린다. 읽기 경로가 `deleted_at`을 걸어 이미 빠지지만, 벡터까지 지워 두면
     * 검색이 조인 조건을 빠뜨려도 삭제된 항목이 되살아나지 않는다. 되살리면 백필이 다시 채운다.
     */
    suspend fun softDeleteFromQaTry(
        projectId: Long,
        qaTryId: Long,
        request: KnowledgeMutationRequest
    ): KnowledgeMutation {
        val knowledgeId = parseKnowledgeId(request.knowledgeId)
            ?: return KnowledgeMutation.Rejected("knowledge_id must be a numeric id")
        val current = knowledgeRepository.findByIdAndProjectIdAndDeletedAtIsNull(knowledgeId, projectId)
            ?: return KnowledgeMutation.Rejected("knowledge $knowledgeId not found in project $projectId")

        transactionalOperator.executeAndAwait {
            knowledgeRepository.save(
                current.copy(deletedAt = Instant.now(clock), deletedByQaTryId = qaTryId)
            )
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
                    createdAt = Instant.now(clock)
                )
            )
            embeddingRepository.discardFor(knowledgeId)
        }
        // 지우는 주체가 Agent라 삭제는 반드시 눈에 보여야 한다. 출처는 컬럼에도 남는다.
        logger.info("knowledge 소프트삭제: id={}, project={}, qaTry={}", knowledgeId, projectId, qaTryId)
        return KnowledgeMutation.Applied(knowledgeId)
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
     * 소프트삭제된 항목은 리포지토리 쿼리 단계에서 이미 빠진다.
     */
    suspend fun findForProject(
        projectId: Long,
        source: KnowledgeSource?,
        tag: KnowledgeTag?
    ): KnowledgeListResponse {
        val rows: Flow<KnowledgeEntity> = when {
            source != null && tag != null ->
                knowledgeRepository.findByProjectIdAndSourceAndTagAndDeletedAtIsNullOrderByIdDesc(
                    projectId, source.name, tag.name
                )
            source != null ->
                knowledgeRepository.findByProjectIdAndSourceAndDeletedAtIsNullOrderByIdDesc(projectId, source.name)
            tag != null ->
                knowledgeRepository.findByProjectIdAndTagAndDeletedAtIsNullOrderByIdDesc(projectId, tag.name)
            else ->
                knowledgeRepository.findByProjectIdAndDeletedAtIsNullOrderByIdDesc(projectId)
        }
        return KnowledgeListResponse(rows.map(::toResponse).toList())
    }

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
