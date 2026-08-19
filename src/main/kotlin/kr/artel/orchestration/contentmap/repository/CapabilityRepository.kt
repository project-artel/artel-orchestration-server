package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 기능 조회.
 *
 * [findByOriginAndSceneId] 가 필요한 이유: 스캔 upsert 는 `origin='evidence'` 행만 건드린다.
 * observed / inferred / human 을 함께 지우면 QA 가 배운 것이 매 스캔마다 리셋된다.
 */
interface CapabilityRepository : CoroutineCrudRepository<CapabilityEntity, Long> {

    fun findBySceneIdOrderByIdAsc(sceneId: Long): Flow<CapabilityEntity>

    fun findBySceneIdAndOriginOrderByIdAsc(sceneId: Long, origin: String): Flow<CapabilityEntity>

    /** 적재 멱등 키. 같은 진입점의 같은 조건이면 같은 기능이다. */
    @Query(
        """
        SELECT c.* FROM capability c
        JOIN capability_evidence e ON e.capability_id = c.id
        WHERE c.scene_id = :sceneId AND e.entry_id = :entryId
        ORDER BY c.id ASC
        """
    )
    fun findEvidenceCapabilities(sceneId: Long, entryId: String): Flow<CapabilityEntity>

    /**
     * 커버리지 지표의 분자와 분모.
     *
     * 분모가 우리 정적 분석 성능이고 분자가 agent 성능이라, 둘을 한 화면에 놓으면 시스템 전체가
     * 설명된다.
     */
    @Query(
        """
        SELECT count(*) FILTER (WHERE c.verification <> 'unverified') AS verified,
               count(*)                                               AS total
        FROM capability c
        JOIN scene s ON s.id = c.scene_id
        WHERE s.content_map_id = :contentMapId AND c.origin = 'evidence'
        """
    )
    suspend fun countEvidenceVerification(contentMapId: Long): VerificationCount?
}

/** [CapabilityRepository.countEvidenceVerification] 결과. */
data class VerificationCount(
    val verified: Long,
    val total: Long,
)
