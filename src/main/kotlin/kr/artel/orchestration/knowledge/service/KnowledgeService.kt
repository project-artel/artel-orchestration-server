package kr.artel.orchestration.knowledge.service

import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.dto.KnowledgeListResponse
import kr.artel.orchestration.knowledge.dto.KnowledgeResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * 통합 지식창고(knowledge) 저장/조회. docs 추출 경로와 QA WS 경로가 공통으로 이 서비스에 저장한다.
 *
 * 저장은 **배치**다: Agent가 준 항목 리스트를 검증해 유효한 것만 넣는다. 잘못된 항목(무효 tag,
 * 빈 summary/description)은 **throw하지 않고 건너뛰고 로그만 남긴다** — 한 항목의 오류가 문서
 * 추출 파이프라인이나 QA 런 전체를 깨서는 안 되기 때문이다.
 */
@Service
class KnowledgeService(
    private val knowledgeRepository: KnowledgeRepository
) {
    private val logger = LoggerFactory.getLogger(KnowledgeService::class.java)

    /**
     * 한 출처(문서/QA 런)에서 온 knowledge 항목 배치를 저장한다.
     * 유효 항목이 하나도 없으면 아무것도 저장하지 않는다.
     */
    fun store(
        projectId: Long,
        source: KnowledgeSource,
        sourceId: Long?,
        contentHash: String?,
        items: List<KnowledgeIngestItem>
    ): Mono<Void> {
        val rows = items.mapNotNull { toEntity(projectId, source, sourceId, contentHash, it) }
        if (rows.isEmpty()) {
            logger.warn(
                "knowledge 저장 스킵: 유효 항목 없음 (project={}, source={}, sourceId={}, 받은수={})",
                projectId, source, sourceId, items.size
            )
            return Mono.empty()
        }
        return knowledgeRepository.saveAll(rows).then()
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

    /** 프로젝트 스코프 조회(최신순). source/tag는 선택 필터. */
    fun findForProject(
        projectId: Long,
        source: KnowledgeSource?,
        tag: KnowledgeTag?
    ): Mono<KnowledgeListResponse> {
        val rows: Flux<KnowledgeEntity> = when {
            source != null && tag != null ->
                knowledgeRepository.findByProjectIdAndSourceAndTagOrderByIdDesc(projectId, source.name, tag.name)
            source != null ->
                knowledgeRepository.findByProjectIdAndSourceOrderByIdDesc(projectId, source.name)
            tag != null ->
                knowledgeRepository.findByProjectIdAndTagOrderByIdDesc(projectId, tag.name)
            else ->
                knowledgeRepository.findByProjectIdOrderByIdDesc(projectId)
        }
        return rows.map(::toResponse).collectList().map(::KnowledgeListResponse)
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
