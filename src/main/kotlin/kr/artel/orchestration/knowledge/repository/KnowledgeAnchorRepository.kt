package kr.artel.orchestration.knowledge.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.knowledge.entity.KnowledgeAnchorEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeScopeSql
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * knowledge_anchor 조회 리포지토리(ARTEL-591).
 *
 * **앵커에는 자기 스코프가 없다.** 앵커는 knowledge 행에 매달린 사실이고 그 행이 이미 스코프를
 * 진다(V28) — 앵커에 scope_id 를 또 두면 같은 사실의 집이 둘이 되어 조용히 어긋난다. 대신 조회가
 * knowledge 를 조인해 [KnowledgeScopeSql.VISIBLE] 를 그대로 지난다. 그 술어를 빠뜨리면 스코프에
 * 가려졌어야 할 지식의 앵커가 새어 나가는데, 앵커만 보면 그것이 어느 지식의 것인지 알 수 없어
 * 새는 것을 알아채기도 어렵다.
 */
interface KnowledgeAnchorRepository : CoroutineCrudRepository<KnowledgeAnchorEntity, Long> {

    /**
     * [knowledgeIds] 가운데 이 스코프에서 **보이는** 지식의 앵커.
     *
     * 검색 히트 묶음에 앵커를 한 번에 붙이는 자리다. 히트마다 따로 부르면 질의가 히트 수만큼
     * 늘고, 그 비용은 앵커가 없는 프로젝트에서도 그대로 난다.
     *
     * 소프트삭제된 지식은 뺀다 — 읽기 경로에서 지식이 사라졌는데 그 앵커만 남아 나오면 화면별
     * 지식 목록이 지워진 지식을 계속 센다.
     *
     * ⚠️ 빈 컬렉션을 넘기면 `IN ()` 이 되어 SQL 이 깨진다. 호출자가 먼저 걸러야 한다.
     */
    @Query(
        """
        SELECT a.* FROM knowledge_anchor a
          JOIN knowledge k ON k.id = a.knowledge_id
         WHERE a.knowledge_id IN (:knowledgeIds)
           AND k.deleted_at IS NULL
           AND ${KnowledgeScopeSql.VISIBLE}
         ORDER BY a.knowledge_id, a.id
        """
    )
    fun findVisibleFor(knowledgeIds: Collection<Long>, scopeId: Long?): Flow<KnowledgeAnchorEntity>
}
