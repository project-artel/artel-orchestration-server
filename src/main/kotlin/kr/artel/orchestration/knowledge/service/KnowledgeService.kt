package kr.artel.orchestration.knowledge.service

import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.dto.KnowledgeListResponse
import kr.artel.orchestration.knowledge.dto.KnowledgeMutationRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.knowledge.repository.KnowledgeEmbeddingRepository
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
 */
@Service
class KnowledgeService(
    private val knowledgeRepository: KnowledgeRepository,
    private val embeddingRepository: KnowledgeEmbeddingRepository,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(KnowledgeService::class.java)

    /**
     * 한 출처(문서/QA 런)에서 온 knowledge 항목 배치를 저장한다.
     * 유효 항목이 하나도 없으면 아무것도 저장하지 않는다.
     *
     * @param scope 이 배치가 들어갈 스코프. 문서 추출 경로는 언제나 [KnowledgeScope.PRODUCTION]이고
     *   (사람이 올린 문서는 실험의 산물이 아니다), QA 경로는 그 런의 스코프다.
     */
    suspend fun store(
        projectId: Long,
        scope: KnowledgeScope,
        source: KnowledgeSource,
        sourceId: Long?,
        contentHash: String?,
        items: List<KnowledgeIngestItem>
    ) {
        val rows = items.mapNotNull { toEntity(projectId, scope, source, sourceId, contentHash, it) }
        if (rows.isEmpty()) {
            logger.warn(
                "knowledge 저장 스킵: 유효 항목 없음 (project={}, scope={}, source={}, sourceId={}, 받은수={})",
                projectId, scope, source, sourceId, items.size
            )
            return
        }
        // saveAll은 콜드 Flow라 반드시 소비해야 실제 저장이 일어난다.
        knowledgeRepository.saveAll(rows).toList()
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
        val saved = knowledgeRepository.save(
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
        return KnowledgeMutation.Applied(requireNotNull(saved.id))
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

        if (shadowRequired(current, scope)) {
            val shadow = knowledgeRepository.save(
                next.copy(
                    id = null,
                    scopeId = scope.id,
                    shadowsId = current.id,
                    // 그림자는 이 스코프에서 새로 생긴 행이다. 생성·수정 시각을 원본에서 물려받으면
                    // 언제 갈라져 나왔는지를 잃는다.
                    createdAt = null,
                    updatedAt = null
                )
            )
            logger.info(
                "knowledge 스코프 수정(그림자 생성): baseline={}, shadow={}, project={}, scope={}, qaTry={}",
                knowledgeId, shadow.id, projectId, scope, qaTryId
            )
            return KnowledgeMutation.Applied(requireNotNull(shadow.id))
        }

        transactionalOperator.executeAndAwait {
            knowledgeRepository.save(next)
            if (embeddedTextChanged) embeddingRepository.discardFor(knowledgeId)
        }
        logger.info(
            "knowledge 수정: id={}, project={}, scope={}, qaTry={}, 임베딩 무효화={}",
            knowledgeId, projectId, scope, qaTryId, embeddedTextChanged
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
            val tombstone = knowledgeRepository.save(
                current.copy(
                    id = null,
                    scopeId = scope.id,
                    shadowsId = current.id,
                    deletedAt = deletedAt,
                    deletedByQaTryId = qaTryId,
                    createdAt = null,
                    updatedAt = null
                )
            )
            logger.info(
                "knowledge 스코프 삭제(툼스톤 생성): baseline={}, tombstone={}, project={}, scope={}, qaTry={}",
                knowledgeId, tombstone.id, projectId, scope, qaTryId
            )
            return KnowledgeMutation.Applied(requireNotNull(tombstone.id))
        }

        transactionalOperator.executeAndAwait {
            knowledgeRepository.save(current.copy(deletedAt = deletedAt, deletedByQaTryId = qaTryId))
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
