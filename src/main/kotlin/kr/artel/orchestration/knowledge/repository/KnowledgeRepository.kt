package kr.artel.orchestration.knowledge.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeScopeSql
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * knowledge 조회 리포지토리.
 *
 * **모든 조회는 `deleted_at IS NULL`과 스코프 술어를 함께 건다.** 소프트삭제(ARTEL-188)와
 * 스코프 격리(ARTEL-256)는 성질이 같다 — 읽기 경로가 하나라도 빠지면 삭제가 삭제가 아니게 되고
 * 격리가 격리가 아니게 되며, 그렇게 뚫린 결과는 그럴듯해서 아무도 못 알아챈다. 그래서 필터를
 * 서비스가 아니라 쿼리에 붙여 빠뜨릴 수 없게 한다.
 *
 * 파생 쿼리(`findByProjectIdAndTagAndDeletedAtIsNull...`)를 쓰지 않게 된 이유가 여기 있다. 스코프
 * 술어에는 "이 스코프의 그림자에 가려진 baseline 제외"라는 `NOT EXISTS`가 들어가는데 파생
 * 쿼리로는 그것을 표현할 수 없다. 술어는 [KnowledgeScopeSql.VISIBLE] 한 곳에만 있고, 이 파일의
 * 질의들과 [KnowledgeVectorSearchRepository]가 그것을 그대로 끼워 넣는다.
 *
 * ⚠️ 상속받은 `findById`/`findAllById`에는 스코프도 프로젝트도 걸리지 않는다. **그게 맞는 자리는
 * 임베딩 백필뿐이다**([kr.artel.orchestration.knowledge.service.KnowledgeEmbeddingSource]) —
 * 그림자 행도 자기 벡터가 필요하므로 백필은 스코프를 가리지 않고 모든 행을 봐야 한다.
 * 프로젝트 목록/검색 경로에서는 절대 쓰지 않는다.
 */
interface KnowledgeRepository : CoroutineCrudRepository<KnowledgeEntity, Long> {

    /**
     * 프로젝트 스코프 조회(최신순). `source`/`tag`는 null이면 걸지 않는 선택 필터다.
     *
     * 필터 조합마다 메서드를 만들지 않고 하나로 합쳤다. 조합마다 두면 스코프 술어가 네 벌로
     * 복사되고, 복사본 하나가 뒤처지는 순간 그 조합에서만 격리가 조용히 뚫린다.
     */
    @Query(
        """
        SELECT k.* FROM knowledge k
         WHERE k.project_id = :projectId
           AND k.deleted_at IS NULL
           AND (:source IS NULL OR k.source = :source)
           AND (:tag IS NULL OR k.tag = :tag)
           AND ${KnowledgeScopeSql.VISIBLE}
         ORDER BY k.id DESC
        """
    )
    fun findVisible(projectId: Long, scopeId: Long?, source: String?, tag: String?): Flow<KnowledgeEntity>

    /**
     * 수정·소프트삭제의 대상 조회(ARTEL-188, ARTEL-256).
     *
     * `projectId`가 조건에 함께 들어가는 것이 프로젝트 격리의 실체다. id로 먼저 읽고 서비스에서
     * 프로젝트를 비교하면 그 비교를 빠뜨릴 수 있지만, 여기서는 다른 프로젝트의 id를 넣으면
     * 애초에 행이 없다. 스코프도 같은 이유로 여기 들어온다 — 다른 스코프의 지식은 존재하지
     * 않는 것으로 보여야 고칠 수도 지울 수도 없다.
     *
     * 이미 소프트삭제된 항목도 없는 것으로 본다: 지워진 것을 다시 지우거나 고치려는 요청이
     * 통하면 원래 언제 누가 지웠는지를 덮어쓴다. 스코프 런이 baseline을 지운 뒤 그 baseline을
     * 다시 건드리려는 경우도 여기서 걸린다 — 툼스톤이 baseline을 가려 조회되지 않는다.
     */
    @Query(
        """
        SELECT k.* FROM knowledge k
         WHERE k.id = :id
           AND k.project_id = :projectId
           AND k.deleted_at IS NULL
           AND ${KnowledgeScopeSql.VISIBLE}
        """
    )
    suspend fun findVisibleById(id: Long, projectId: Long, scopeId: Long?): KnowledgeEntity?

    /**
     * 한 스코프가 [shadowsId] baseline을 가리려고 이미 만들어 둔 그림자.
     *
     * 소프트삭제된 그림자(툼스톤)도 돌려준다. 툼스톤을 못 보면 이미 지운 baseline에 그림자를
     * 하나 더 만들게 되고, 그러면 그 baseline이 그 스코프에서 두 번 가려진다. DB의
     * `uq_knowledge_scope_shadow`가 그 상태를 애초에 막지만, 여기서 먼저 걸러야 유일 제약 위반이
     * 예외가 아니라 정상 분기로 처리된다.
     */
    @Query("SELECT * FROM knowledge WHERE scope_id = :scopeId AND shadows_id = :shadowsId")
    suspend fun findShadow(scopeId: Long, shadowsId: Long): KnowledgeEntity?
}
