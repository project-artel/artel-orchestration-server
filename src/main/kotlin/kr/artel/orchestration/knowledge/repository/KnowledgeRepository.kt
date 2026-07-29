package kr.artel.orchestration.knowledge.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * knowledge 조회 리포지토리. 조회는 프로젝트 스코프로 이루어지며, source/tag로 선택 필터한다.
 * (Phase 2에서 하이브리드 검색이 붙기 전까지는 이 파생 쿼리로 기본 조회를 제공한다.)
 *
 * **모든 조회는 `DeletedAtIsNull`을 건다.** 소프트삭제(ARTEL-188)는 읽기 경로가 하나라도 빠지면
 * 삭제가 삭제가 아니게 되므로, 필터를 서비스가 아니라 쿼리에 붙여 빠뜨릴 수 없게 한다.
 */
interface KnowledgeRepository : CoroutineCrudRepository<KnowledgeEntity, Long> {

    fun findByProjectIdAndDeletedAtIsNullOrderByIdDesc(projectId: Long): Flow<KnowledgeEntity>

    fun findByProjectIdAndTagAndDeletedAtIsNullOrderByIdDesc(
        projectId: Long,
        tag: String
    ): Flow<KnowledgeEntity>

    fun findByProjectIdAndSourceAndDeletedAtIsNullOrderByIdDesc(
        projectId: Long,
        source: String
    ): Flow<KnowledgeEntity>

    fun findByProjectIdAndSourceAndTagAndDeletedAtIsNullOrderByIdDesc(
        projectId: Long,
        source: String,
        tag: String
    ): Flow<KnowledgeEntity>
}
