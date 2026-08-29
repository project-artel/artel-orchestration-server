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

    /**
     * 이번 문서가 더 이상 말하지 않는 **근거 출신 빈 `scene`**을 내린다. 지운 행 수를 돌려준다.
     *
     * 이것이 필요한 이유는 [SceneEntity] 행이 이름으로 upsert 되기 때문이다 — 적재 규칙이 바뀌어
     * 어떤 이름이 더는 나오지 않게 돼도 옛 행은 그 자리에 남는다. `DontDestroyOnLoad` 처럼 `scene` 이
     * 아닌 이름이 한 번 앉으면(ARTEL-460 이전) 그 뒤 어떤 재적재도 그것을 치우지 못한다.
     *
     * **아무도 아무것도 모르는 `scene` 만 지운다.** 조건 하나하나가 "이 `scene` 에 대해 누군가 무언가를 안다"는
     * 뜻이라, 하나라도 걸리면 남긴다:
     *
     * - `origin = 'evidence'` — 관측이 만난 `scene` 은 근거가 말한 적 없어도 실재한다
     * - `NOT walked` · 이미지 없음 — QA 런이 서 봤거나 찍었으면 그 기록이 사실이다
     * - `capability` · `screen` · `scene_edge` · `scene_screen_selector` 없음 — 참조가 있으면
     *   CASCADE 로 그 지식까지 함께 사라진다
     *
     * 이름 목록으로 거르는 이유: id 로 거르면 이번 문서가 만지지 않은 다른 문서의 `scene` 까지 후보가
     * 된다. 한 지도에 문서가 여럿 들어올 수 있고, 각 문서는 자기가 걸은 `scene` 만 안다.
     */
    @Modifying
    @Query(
        """
        DELETE FROM scene s
        WHERE s.content_map_id = :contentMapId
          AND s.name <> ALL (:keptNames)
          AND s.origin = 'evidence'
          AND s.walked = FALSE
          AND s.image_object_key IS NULL
          AND NOT EXISTS (SELECT 1 FROM capability c WHERE c.scene_id = s.id)
          AND NOT EXISTS (SELECT 1 FROM screen sc WHERE sc.scene_id = s.id)
          AND NOT EXISTS (
              SELECT 1 FROM scene_edge e WHERE e.from_scene_id = s.id OR e.to_scene_id = s.id
          )
          AND NOT EXISTS (SELECT 1 FROM scene_screen_selector sel WHERE sel.scene_id = s.id)
        """
    )
    suspend fun retireVanishedScenes(contentMapId: Long, keptNames: Array<String>): Long
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

    /**
     * agent 나 사람이 판단한 항목을 넣거나 덮는다 (ARTEL-655).
     *
     * 키가 (scene, match_kind, pattern, source) 라, 같은 출처가 같은 대상에 대해 말을 바꾸면
     * **덮는다.** 다른 출처의 행은 그대로 남는다 — 사람이 agent 를 덮는 것이 아니라 이기는 것이라,
     * 사람 항목을 지웠을 때 agent 의 판단이 되살아나야 한다(V60 1절).
     *
     * `reason` 을 저장하지 않는다. `scene_screen_selector` 에 그 칸이 없고 이 이슈가 칸을 더하지
     * 않는 것은, 사유가 답이 오간 자리(`qa_log` 의 프레임)에 이미 원문으로 남아 있기 때문이다.
     * 같은 글을 두 곳에 두면 한쪽만 지워졌을 때 어느 쪽이 참인지 가릴 수 없다.
     */
    @Modifying
    @Query(
        """
        INSERT INTO scene_screen_selector (scene_id, match_kind, pattern, source, screen_defining)
        VALUES (:sceneId, :matchKind, :pattern, :source, :screenDefining)
        ON CONFLICT (scene_id, match_kind, pattern, source) DO UPDATE SET
            screen_defining = EXCLUDED.screen_defining
        """
    )
    suspend fun upsertRule(
        sceneId: Long,
        matchKind: String,
        pattern: String,
        source: String,
        screenDefining: Boolean,
    ): Long
}
