package kr.artel.orchestration.knowledge.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.knowledge.entity.KnowledgeEventEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * knowledge 버전 이력의 쓰기 경로(ARTEL-255).
 *
 * 이력은 **덧붙이기만** 한다 — 수정도 삭제도 없다. 그것이 이 테이블이 성립하는 조건이다. 이벤트
 * 행이 불변이라야 `(knowledge_id, version)` 단건 조회가 항상 같은 답을 주고, 그래야 최신 content를
 * `knowledge` 행에서 읽는 대신 여기서 읽는 선택이 의미를 가진다(V26 주석).
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
}
