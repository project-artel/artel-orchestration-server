package kr.artel.orchestration.knowledge.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.knowledge.entity.KnowledgeEdgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEdgeScopeSql
import kr.artel.orchestration.knowledge.entity.PART_OF_RELATION
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * knowledge_edge 조회·저장 리포지토리(ARTEL-274).
 *
 * **파생 쿼리를 만들지 않는다.** [KnowledgeRepository]가 그것을 버린 이유가 그대로 적용된다 —
 * 스코프 술어에는 "이 스코프의 툼스톤에 가려진 baseline 제외"라는 `NOT EXISTS`가 들어가는데
 * 파생 쿼리로는 표현할 수 없고, 이름에 안 들어가는 필터는 언젠가 빠진다. 술어는
 * [KnowledgeEdgeScopeSql.VISIBLE] 한 곳에만 있다.
 *
 * ⚠️ 상속받은 `findById`는 스코프도 프로젝트도 걸리지 않는다. unlink가 툼스톤을 만들 때 대상
 * baseline 행을 이미 [findVisibleEdge]로 잡아 둔 뒤이므로 이 파일 안에서는 쓰지 않는다.
 */
interface KnowledgeEdgeRepository : CoroutineCrudRepository<KnowledgeEdgeEntity, Long> {

    /**
     * 이 스코프에서 보이는 `(from, to, relation)` 관계 한 줄.
     *
     * link의 중복 사전 검사와 unlink의 대상 조회가 **같은 질의**를 쓴다. 링크할 수 있는 것과
     * 거둘 수 있는 것이 같은 집합이어야 하기 때문이다 — 갈라 두면 "보이지 않는데 중복이라
     * 거절당하는" 관계가 생긴다.
     *
     * 대칭 관계는 호출 전에 [kr.artel.orchestration.knowledge.entity.KnowledgeRelation.normalize]로
     * 정렬해서 넣는다. 정렬하지 않으면 에이전트가 본 방향으로 부른 unlink가 아무것도 못 찾는다.
     *
     * baseline 행이 잡힐 수도 있고 자기 스코프 행이 잡힐 수도 있다. 어느 쪽인지에 따라 unlink가
     * 툼스톤을 만들지 그 행을 지울지 갈리므로, 서비스는 돌아온 행의 `scopeId`를 봐야 한다.
     */
    @Query(
        """
        SELECT e.* FROM knowledge_edge e
         WHERE e.project_id = :projectId
           AND e.from_knowledge_id = :fromKnowledgeId
           AND e.to_knowledge_id = :toKnowledgeId
           AND e.relation = :relation
           AND ${KnowledgeEdgeScopeSql.VISIBLE}
        """
    )
    suspend fun findVisibleEdge(
        projectId: Long,
        scopeId: Long?,
        fromKnowledgeId: Long,
        toKnowledgeId: Long,
        relation: String
    ): KnowledgeEdgeEntity?

    /**
     * 한 스코프가 [shadowsEdgeId] baseline edge를 가리려고 이미 만들어 둔 툼스톤.
     *
     * [KnowledgeRepository.findShadow]와 같은 자리다. 먼저 걸러야 `uq_knowledge_edge_scope_tombstone`
     * 위반이 예외가 아니라 정상 분기로 처리된다.
     */
    @Query("SELECT * FROM knowledge_edge WHERE scope_id = :scopeId AND shadows_edge_id = :shadowsEdgeId")
    suspend fun findTombstone(scopeId: Long, shadowsEdgeId: Long): KnowledgeEdgeEntity?

    /**
     * [documentNodeId]를 향한 살아있는 `PART_OF` edge 전부(ARTEL-748). 문서를 지울 때 문서 node와
     * 함께 소프트삭제할 대상이다([kr.artel.orchestration.knowledge.service.KnowledgeService.softDeleteForDocument]).
     *
     * `project_id`를 조건에 함께 건다 — 이 파일의 다른 질의가 전부 그렇게 한다. 프로젝트 격리는
     * 서비스의 비교가 아니라 질의가 진다.
     *
     * `scope_id IS NULL`을 고정으로 건다 — 문서 적재는 언제나 baseline이다
     * ([KnowledgeRepository.findDocumentNode]와 같은 이유). 스코프 런이 이 edge를 가리려고 만든
     * 툼스톤은 그 스코프 자신의 상태이지 문서의 상태가 아니라, 함께 지우지 않는다.
     */
    @Query(
        """
        SELECT * FROM knowledge_edge
         WHERE project_id = :projectId
           AND to_knowledge_id = :documentNodeId
           AND relation = '$PART_OF_RELATION'
           AND scope_id IS NULL
           AND deleted_at IS NULL
        """
    )
    fun findBaselinePartOfEdgesTo(projectId: Long, documentNodeId: Long): Flow<KnowledgeEdgeEntity>
}
