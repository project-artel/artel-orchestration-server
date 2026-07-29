package kr.artel.orchestration.knowledge.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * knowledge 조회 리포지토리. 조회는 프로젝트 스코프로 이루어지며, source/tag로 선택 필터한다.
 * (Phase 2에서 하이브리드 검색이 붙기 전까지는 이 파생 쿼리로 기본 조회를 제공한다.)
 */
interface KnowledgeRepository : CoroutineCrudRepository<KnowledgeEntity, Long> {

    fun findByProjectIdOrderByIdDesc(projectId: Long): Flow<KnowledgeEntity>

    fun findByProjectIdAndTagOrderByIdDesc(projectId: Long, tag: String): Flow<KnowledgeEntity>

    fun findByProjectIdAndSourceOrderByIdDesc(projectId: Long, source: String): Flow<KnowledgeEntity>

    fun findByProjectIdAndSourceAndTagOrderByIdDesc(
        projectId: Long,
        source: String,
        tag: String
    ): Flow<KnowledgeEntity>
}
