package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.dto.ScreenCapabilityRow
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

    /**
     * 지도 한 장의 `screen` 별 `capability` 전부. 조회가 쓰는 유일한 질의다 (ARTEL-658).
     *
     * `screen` 마다 [findByScreenId] 를 부르지 않는 이유: 한 `scene` 이 `screen` 수십 개를 담아(실측
     * `TurnBattleScene` 이 29 개) 조회 한 번이 왕복 수십 번이 된다. `content_map_id` 하나로 좁힌
     * 질의 한 번이 같은 답을 낸다 — `ContentMapViewService` 가 섹션마다 질의 하나를 두는 것과 같은
     * 규칙이고, [ScreenRepository.findByContentMapId] 가 이미 같은 판단을 했다.
     *
     * **`merged_into IS NULL` 을 `scene` 목록과 똑같이 건다.** `screen_capability` 는 `scene` 목록의
     * 부분집합이라는 것이 이 표의 정의인데, 필터가 갈리면 `screen` 에만 있고 `scene` 에는 없는 행이
     * 서고 인스펙터가 두 목록을 `capability.id` 로 이을 수 없다.
     * `CapabilityRepository.findSceneCapabilities` 의 필터를 손대면 여기도 함께 손댄다.
     *
     * **조인이 행을 곱하지 않는다.** `screen_capability` 의 PK 가 `(screen_id, capability_id)` 이고
     * `capability` 를 단일 FK 로 붙이므로 연결 하나에 줄 하나다.
     *
     * `screen_id` · `capability_id` 오름차순으로 낸다. 한 `screen` 안의 줄 순서가 곧 적재 순서라,
     * 같은 관측을 다시 받아도 응답이 흔들리지 않는다.
     */
    @Query(
        """
        SELECT sc.screen_id, sc.capability_id, sc.observed_count, sc.fired_count,
               c.summary, c.status, c.origin, c.verification
        FROM screen_capability sc
        JOIN screen sr ON sr.id = sc.screen_id
        JOIN scene s ON s.id = sr.scene_id
        JOIN capability c ON c.id = sc.capability_id
        WHERE s.content_map_id = :contentMapId AND c.merged_into IS NULL
        ORDER BY sc.screen_id ASC, sc.capability_id ASC
        """
    )
    fun findByContentMapId(contentMapId: Long): Flow<ScreenCapabilityRow>
}
