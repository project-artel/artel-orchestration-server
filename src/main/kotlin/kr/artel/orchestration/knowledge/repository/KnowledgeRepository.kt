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

    /**
     * 수정·소프트삭제의 대상 조회(ARTEL-188).
     *
     * `projectId`가 조건에 함께 들어가는 것이 프로젝트 격리의 실체다. id로 먼저 읽고 서비스에서
     * 프로젝트를 비교하면 그 비교를 빠뜨릴 수 있지만, 여기서는 다른 프로젝트의 id를 넣으면
     * 애초에 행이 없다. 이미 소프트삭제된 항목도 없는 것으로 본다 — 지워진 것을 다시 지우거나
     * 고치려는 요청은 거절되어야 원래 언제 누가 지웠는지를 덮어쓰지 않는다.
     */
    suspend fun findByIdAndProjectIdAndDeletedAtIsNull(id: Long, projectId: Long): KnowledgeEntity?
}
