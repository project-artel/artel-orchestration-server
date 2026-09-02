package kr.artel.orchestration.knowledge.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.knowledge.entity.KnowledgeDocumentNodeSql
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeScopeSql
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
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

    /**
     * [findVisibleById]와 같되 **소프트삭제된 행도 돌려준다**(ARTEL-274).
     *
     * ⚠️ 이 파일에서 `DeletedAtIsNull`을 걸지 않는 유일한 조회다. 그래서 왜 있는지를 여기 못박아
     * 둔다 — 이유가 안 적혀 있으면 다음 사람이 "필터가 빠졌다"고 읽고 일반 조회에 갖다 쓴다.
     *
     * **쓰는 곳은 `knowledge_edge`의 끝점 해석 하나뿐이다.**
     * - `REPLACES`의 `to` 끝점은 지워졌을 것이 **정상이다.** 대체된 항목은 소프트삭제되는 것이
     *   그 관계의 뜻이고, 살아 있는 것만 허용하면 REPLACES는 영영 못 만든다.
     * - unlink는 이미 지워진 항목에 걸린 관계도 거둘 수 있어야 한다. 항목이 사라졌다고 그
     *   항목을 가리키던 edge가 저절로 없어지지는 않는다(하드 FK를 걸지 않은 대가다).
     *
     * 스코프 술어는 그대로 걸린다 — 지워진 것을 본다고 남의 스코프까지 보면 안 된다.
     */
    @Query(
        """
        SELECT k.* FROM knowledge k
         WHERE k.id = :id
           AND k.project_id = :projectId
           AND ${KnowledgeScopeSql.VISIBLE}
        """
    )
    suspend fun findVisibleByIdIncludingDeleted(id: Long, projectId: Long, scopeId: Long?): KnowledgeEntity?

    /**
     * 한 문서가 만든 baseline knowledge 행(ARTEL-728). 문서를 지울 때 함께 소프트삭제할 대상이다.
     *
     * `scope_id IS NULL`을 고정으로 건다 — 문서 추출은 언제나 [KnowledgeSource]가 `DOCS`고 스코프는
     * `KnowledgeScope.PRODUCTION`으로 고정이다([kr.artel.orchestration.knowledge.service.DocumentKnowledgeExtractionService]
     * 참조). 그러니 이 조회는 다른 조회처럼 `scopeId` 파라미터로 스코프를 고르지 않는다 — 스코프 런이
     * 이 baseline을 가리려고 만든 그림자 행은 그 스코프 자신의 상태이지 문서의 상태가 아니다. 문서를
     * 지운다고 그림자까지 지우면 그 스코프의 실험 결과가 문서 삭제라는 무관한 사건에 휘둘린다
     * (그림자 정리는 ARTEL-728의 범위 밖이다).
     */
    @Query(
        """
        SELECT * FROM knowledge
         WHERE project_id = :projectId
           AND source = 'DOCS'
           AND source_id = :documentId
           AND scope_id IS NULL
           AND deleted_at IS NULL
        """
    )
    fun findBaselineByDocumentId(projectId: Long, documentId: Long): Flow<KnowledgeEntity>

    /**
     * 이 문서의 baseline 문서 node(ARTEL-748). 있으면 재사용하고 없으면
     * [kr.artel.orchestration.knowledge.service.KnowledgeService]가 새로 만든다 — 재적재해도
     * 문서 node가 하나여야 하기 때문이다.
     *
     * 문서 node와 그 배치의 항목은 `source`/`source_id`가 완전히 같아 그 값만으로는 구분할 수
     * 없다. 술어는 [KnowledgeDocumentNodeSql.IS_DOCUMENT_NODE] 하나에서 온다 — 그 KDoc이 이유를
     * 적어 뒀다.
     *
     * `scope_id IS NULL`을 고정으로 건다. 문서 적재는 언제나 baseline이다
     * ([findBaselineByDocumentId]와 같은 이유).
     *
     * **`LIMIT 1`을 반드시 건다.** 이 질의는 단건 반환 타입([KnowledgeEntity]?)이라 행이 둘이면
     * R2DBC가 예외를 던지고, 그 예외는 적재 경로뿐 아니라 문서 삭제 경로까지 깨뜨린다. 동시에
     * 같은 문서를 두 번 재적재하면 문서 node가 실제로 둘 생길 수 있는데(유일 제약을 두지 않기로
     * 했다 — `KnowledgeService.store` KDoc 참조), 그런 상태에서도 읽기·삭제는 죽지 않아야 한다.
     * `ORDER BY id`로 항상 같은 행(가장 먼저 만들어진 것)을 골라 결과가 호출마다 흔들리지 않게 한다.
     */
    @Query(
        """
        SELECT * FROM knowledge
         WHERE project_id = :projectId
           AND source = 'DOCS'
           AND source_id = :documentId
           AND scope_id IS NULL
           AND deleted_at IS NULL
           AND ${KnowledgeDocumentNodeSql.IS_DOCUMENT_NODE}
         ORDER BY id
         LIMIT 1
        """
    )
    suspend fun findDocumentNode(projectId: Long, documentId: Long): KnowledgeEntity?
}
