package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.SceneScreenSelectorEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 씬 조회. 갱신 단위가 씬이므로 (content_map, name) 조회가 적재의 멱등 키다.
 */
interface SceneRepository : CoroutineCrudRepository<SceneEntity, Long> {

    suspend fun findByContentMapIdAndName(contentMapId: Long, name: String): SceneEntity?

    fun findByContentMapIdOrderByNameAsc(contentMapId: Long): Flow<SceneEntity>
}

/**
 * 이 씬에서 화면을 식별하는 selector 목록 (ARTEL-654).
 *
 * `screen` 옆이 아니라 `scene` 옆에 사는 이유: 어떤 selector 가 화면을 식별하는가는 **씬의 구조적
 * 사실**이고, 그 씬의 어느 화면에서 보든 같은 답이어야 한다. 화면마다 따로 들면 같은 selector 가
 * 화면에 따라 다르게 판정되어 `discriminator` 규칙이 화면마다 갈린다.
 *
 * 프로세스 메모리에 캐시하지 않는다. `screen` 의 식별 키(`uk_screen_discriminator`)가 런과
 * 프로세스를 넘어 사는 값이므로 그 값을 만드는 규칙도 그래야 하고, 사람이나 agent 가 목록을 고친
 * 것이 서버 두 대에 서로 다른 시점에 보이면 같은 화면이 다른 `discriminator` 로 앉는다.
 */
interface SceneScreenSelectorRepository : CoroutineCrudRepository<SceneScreenSelectorEntity, Long> {

    fun findBySceneIdOrderByIdAsc(sceneId: Long): Flow<SceneScreenSelectorEntity>

    /**
     * `capability.control_selector` 를 목록의 씨앗으로 심는다. 이미 있으면 아무 일도 하지 않는다.
     *
     * 멱등을 코드가 아니라 `uk_scene_screen_selector` 가 강제한다 — 같은 빌드를 두 서버가 관측하면
     * 둘 다 같은 씨앗을 동시에 처음 심으려 하므로, "이미 있나" 를 코드가 판정하면 그 검사는 경합에
     * 진다.
     *
     * `match_kind` 는 `selector` 다. `control_selector` 는 `PulseObject.selector` 와 같은 표기이고,
     * `path` 로 넓히면 이름이 같은 형제 컨트롤(확인 버튼과 취소 버튼)이 한 항목에 맞는다.
     *
     * `screen_defining` 은 `TRUE` 로 심는다. 씨앗은 **정의상 조작할 수 있는 것**이라 화면을
     * 식별하지 못한다고 볼 근거가 없다. 아니라고 판단되면 `agent` 나 `human` 출처의 항목이 이긴다.
     */
    @Modifying
    @Query(
        """
        INSERT INTO scene_screen_selector (scene_id, match_kind, pattern, source, screen_defining)
        VALUES (:sceneId, 'selector', :pattern, 'static-analysis', TRUE)
        ON CONFLICT (scene_id, match_kind, pattern, source) DO NOTHING
        """
    )
    suspend fun seedFromControlSelector(sceneId: Long, pattern: String): Long
}
