package kr.artel.orchestration.knowledge.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.knowledge.entity.KnowledgeEventEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * knowledge 버전 이력의 쓰기 경로(ARTEL-255).
 *
 * 이력은 **덧붙이기만** 한다 — 수정도 삭제도 없다. 그것이 이 테이블이 성립하는 조건이다. 이벤트
 * 행이 불변이라야 `(knowledge_id, version)` 단건 조회가 항상 같은 답을 주고, 그래야 최신 content를
 * `knowledge` 행에서 읽는 대신 여기서 읽는 선택이 의미를 가진다(V27 주석).
 *
 * 읽기 쪽 집계는 이 인터페이스가 아니라 `knowledge_entry_facts` view와
 * [KnowledgeStatsRepository]가 맡는다. 여기에 조회 메서드를 늘리면 같은 집계가 두 곳에 생긴다.
 */
interface KnowledgeEventRepository : CoroutineCrudRepository<KnowledgeEventEntity, Long> {

    /**
     * 한 항목의 이력 전체를 오래된 순으로 읽는다. 검증과 운영 조회용이다 —
     * 축별 집계는 view를 쓴다.
     */
    fun findByKnowledgeIdOrderByIdAsc(knowledgeId: Long): Flow<KnowledgeEventEntity>

    // 아래 다섯은 집계가 아니라 항목 하나 단위의 이력 조회다(V100). QA run 이 바꾼 knowledge 를
    // 보여 주고 revert 할 때 쓴다. 축별 집계는 여전히 view 의 몫이다.

    /** QA run 하나의 모든 `qa_try` 가 남긴 event. id 오름차순이 일어난 순서다. */
    @Query(
        """
        SELECT e.* FROM knowledge_event e
          JOIN qa_try t ON t.id = e.qa_try_id
         WHERE t.qa_run_id = :qaRunId
         ORDER BY e.id ASC
        """
    )
    fun findByQaRunId(qaRunId: Long): Flow<KnowledgeEventEntity>

    /**
     * 항목마다 가장 나중의 event 하나. `idx_knowledge_event_target` 을 탄다.
     *
     * ⚠️ 빈 컬렉션을 넘기면 `IN ()` 이 되어 SQL 이 깨진다. 부르는 쪽이 먼저 거른다.
     */
    @Query(
        """
        SELECT DISTINCT ON (knowledge_id) * FROM knowledge_event
         WHERE knowledge_id IN (:knowledgeIds)
         ORDER BY knowledge_id, id DESC
        """
    )
    fun findLatestByKnowledgeIds(knowledgeIds: Collection<Long>): Flow<KnowledgeEventEntity>

    @Query("SELECT * FROM knowledge_event WHERE knowledge_id = :knowledgeId ORDER BY id DESC LIMIT 1")
    suspend fun findLatestByKnowledgeId(knowledgeId: Long): KnowledgeEventEntity?

    /** [eventIds] 가운데 이미 되돌려진 것을 가리키는 revert event. 빈 컬렉션은 넘기지 않는다. */
    @Query("SELECT * FROM knowledge_event WHERE reverts_event_id IN (:eventIds)")
    fun findRevertsOf(eventIds: Collection<Long>): Flow<KnowledgeEventEntity>

    /** 그 버전의 content 스냅샷을 만든 event. `uq_knowledge_event_version` 으로 하나뿐이다. */
    @Query(
        """
        SELECT * FROM knowledge_event
         WHERE knowledge_id = :knowledgeId AND version = :version AND after IS NOT NULL
        """
    )
    suspend fun findContentAt(knowledgeId: Long, version: Int): KnowledgeEventEntity?
}
