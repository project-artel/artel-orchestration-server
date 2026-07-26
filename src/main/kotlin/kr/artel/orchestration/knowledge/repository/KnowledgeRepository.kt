package kr.artel.orchestration.knowledge.repository

import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux

/**
 * knowledge 조회 리포지토리. 조회는 프로젝트 스코프로 이루어지며, source/tag로 선택 필터한다.
 * (Phase 2에서 하이브리드 검색이 붙기 전까지는 이 파생 쿼리로 기본 조회를 제공한다.)
 */
interface KnowledgeRepository : ReactiveCrudRepository<KnowledgeEntity, Long> {

    fun findByProjectIdOrderByIdDesc(projectId: Long): Flux<KnowledgeEntity>

    fun findByProjectIdAndTagOrderByIdDesc(projectId: Long, tag: String): Flux<KnowledgeEntity>

    fun findByProjectIdAndSourceOrderByIdDesc(projectId: Long, source: String): Flux<KnowledgeEntity>

    fun findByProjectIdAndSourceAndTagOrderByIdDesc(
        projectId: Long,
        source: String,
        tag: String
    ): Flux<KnowledgeEntity>
}
