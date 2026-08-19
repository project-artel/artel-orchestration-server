package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.ScreenCapabilityEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 복합 PK 테이블은 `CoroutineCrudRepository.save()` 로 쓸 수 없다 — 단일 `@Id` 가 없으면 Spring Data
 * 가 신규/기존을 판별하지 못한다. 명시 upsert 로 쓴다.
 *
 * 조회만 쓰는 [CoroutineCrudRepository] 의 id 타입은 형식상 `Long` 이고 id 기반 메서드는 쓰지 않는다.
 */
interface ScreenCapabilityRepository : CoroutineCrudRepository<ScreenCapabilityEntity, Long> {

    fun findByScreenId(screenId: Long): Flow<ScreenCapabilityEntity>

    /**
     * 화면이 이 기능을 제공하더라는 관측을 누적한다.
     *
     * [firedCount] 와 [ScreenCapabilityEntity.observedCount] 의 차이가 결함 신호다 — 눌렀는데
     * 아무것도 안 변한 횟수.
     */
    @Modifying
    @Query(
        """
        INSERT INTO screen_capability (screen_id, capability_id, observed_count, fired_count)
        VALUES (:screenId, :capabilityId, 1, :firedIncrement)
        ON CONFLICT (screen_id, capability_id) DO UPDATE SET
            observed_count = screen_capability.observed_count + 1,
            fired_count = screen_capability.fired_count + :firedIncrement
        """
    )
    suspend fun observe(screenId: Long, capabilityId: Long, firedIncrement: Int): Long
}
