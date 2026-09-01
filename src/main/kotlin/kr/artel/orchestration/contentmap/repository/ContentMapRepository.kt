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
     * **`status <> 'not-a-step'` 이 여기 있다.** V72 전에는 뷰가 들고 있었는데, 그 필터가 뷰에
     * 있는 동안은 agent 도 같은 것만 받았다 — 실측 472 행 중 54 행이다(ARTEL-680). 뷰를 넓히고
     * 필터를 이 질의로 내려, 두 소비자가 서로 다른 것을 원한다는 사실을 이름으로 남긴다.
     * agent 쪽은 [findAllCapabilityRows] 다.
     *
     * TC 생성기에서 이 필터를 빼면 안 된다. `not-a-step` 은 조작이 없어 단독 명세가 될 수 없고,
     * 누를 수 없는 것으로 실행 가능한 테스트 케이스를 만들 수 없다.
     *
     * 접힌(`merged_into`) 행은 뷰가 계속 거른다. 효과는 여기 없다 — 행이 곱해지므로
     * [CapabilityEffectRepository] 로 따로 읽는다.
     *
     * `ORDER BY` 를 씬 이름과 기능 id 로 고정하는 것은 취향이 아니다. 이 목록은 agent 프롬프트에
     * 실려 프롬프트 캐시를 타므로, 줄 순서가 조회마다 흔들리면 캐시가 통째로 깨진다.
     */
    @Query(
        """
        SELECT * FROM v_content_map_capability
        WHERE content_map_id = :contentMapId AND status <> 'not-a-step'
        ORDER BY scene_name ASC, capability_id ASC
        """
    )
    fun findStepCapabilityRows(contentMapId: Long): Flow<ContentMapCapabilityRow>

    /** [findStepCapabilityRows] 와 같은 필터를 씬 하나로 좁힌 것. 둘의 필터는 함께 손댄다. */
    @Query(
        """
        SELECT * FROM v_content_map_capability
        WHERE content_map_id = :contentMapId AND scene_name = :sceneName AND status <> 'not-a-step'
        ORDER BY capability_id ASC
        """
    )
    fun findStepCapabilityRowsByScene(contentMapId: Long, sceneName: String): Flow<ContentMapCapabilityRow>

    /**
     * QA agent 가 런 시작에 받는 것. **이 빌드의 capability 전부다**(ARTEL-680).
     *
     * `not-a-step` 418 행을 [findStepCapabilityRows] 가 거르는 이유는 TC 생성기의 이유이지 agent 의
     * 이유가 아니다. 그 418 행은 `interaction = 'none'` — "적을 처치하면 보상을 받는다" 처럼 누르는
     * 것이 아니라 일어나는 일이고, 일어났는지는 `screen` 을 본 agent 가 안다(V71). 목록에서 빼면
     * agent 는 적을 대상을 모른 채 tool 만 들고 있게 된다.
     *
     * 쏟아 놓기만 하면 안 된다는 것이 [findStepCapabilityRows] 를 남긴 이유다.
     * `SceneContextService` 가 이 결과를 `status` 로 갈라 두 목록으로 낸다.
     */
    @Query(
        """
        SELECT * FROM v_content_map_capability
        WHERE content_map_id = :contentMapId
        ORDER BY scene_name ASC, capability_id ASC
        """
    )
    fun findAllCapabilityRows(contentMapId: Long): Flow<ContentMapCapabilityRow>

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
