package kr.artel.orchestration.knowledge.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.knowledge.entity.KnowledgeUsageEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 검색 사용 로그의 쓰기 경로(ARTEL-255).
 *
 * 이력과 마찬가지로 덧붙이기만 한다. 부피는 런당 (검색 횟수 상한 × 결과 상한)으로 유계라
 * 정리 잡이 필요 없다.
 */
interface KnowledgeUsageRepository : CoroutineCrudRepository<KnowledgeUsageEntity, Long> {

    /** 한 런이 검색으로 읽은 항목 전부. 검증과 운영 조회용이다. */
    fun findByQaTryIdOrderByIdAsc(qaTryId: Long): Flow<KnowledgeUsageEntity>
}
