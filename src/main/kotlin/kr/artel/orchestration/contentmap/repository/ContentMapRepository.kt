package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.dto.ContentMapCapabilityRow
import kr.artel.orchestration.contentmap.dto.SpecGapRow
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * content_map 루트 조회.
 *
 * **키가 게임 빌드 하나**인 것이 이 도메인의 전제다(ARTEL-642, `uk_content_map_build`). 근거가
 * 먼저 오든 QA 런이 먼저 돌든 같은 행에 쌓이므로, "어느 지도를 볼까" 를 고르는 자리가 없다.
 */
interface ContentMapRepository : CoroutineCrudRepository<ContentMapEntity, Long> {

    /** 유일 제약이 하나를 보장한다. 없으면 아직 이 빌드에 대해 알아낸 것이 없다는 뜻이다. */
    suspend fun findByGameBuildId(gameBuildId: Long): ContentMapEntity?

    /**
     * TC 생성기가 읽는 유일한 창구(`v_content_map_capability`).
     *
     * 뷰가 `not-a-step` 과 접힌 행을 이미 걸렀다. 효과는 여기 없다 — 행이 곱해지므로
     * [CapabilityEffectRepository] 로 따로 읽는다.
     *
     * `ORDER BY` 를 씬 이름과 기능 id 로 고정하는 것은 취향이 아니다. 이 목록은 agent 프롬프트에
     * 실려 프롬프트 캐시를 타므로, 줄 순서가 조회마다 흔들리면 캐시가 통째로 깨진다.
     */
    @Query(
        """
        SELECT * FROM v_content_map_capability
        WHERE content_map_id = :contentMapId
        ORDER BY scene_name ASC, capability_id ASC
        """
    )
    fun findCapabilityRows(contentMapId: Long): Flow<ContentMapCapabilityRow>

    @Query(
        """
        SELECT * FROM v_content_map_capability
        WHERE content_map_id = :contentMapId AND scene_name = :sceneName
        ORDER BY capability_id ASC
        """
    )
    fun findCapabilityRowsByScene(contentMapId: Long, sceneName: String): Flow<ContentMapCapabilityRow>

    /**
     * 명세가 못 된 이유(`v_spec_gap`). 사유가 없는(세 칸이 다 찬) 행은 뺀다.
     *
     * 이 분포가 다음 스프린트에 무엇을 고칠지 정한다.
     */
    @Query(
        """
        SELECT * FROM v_spec_gap
        WHERE content_map_id = :contentMapId AND reason IS NOT NULL
        ORDER BY capability_id ASC
        """
    )
    fun findSpecGaps(contentMapId: Long): Flow<SpecGapRow>
}
