package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.dto.ContentMapSceneEdgeRow
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.entity.ScreenTransitionEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 화면. QA 런 전에는 0행이고 그게 정상이다 — 정적 분석은 화면을 모른다.
 */
interface ScreenRepository : CoroutineCrudRepository<ScreenEntity, Long> {

    fun findBySceneIdOrderByIdAsc(sceneId: Long): Flow<ScreenEntity>
}

/** 화면 전이. 관측으로만 생긴다. */
interface ScreenTransitionRepository : CoroutineCrudRepository<ScreenTransitionEntity, Long> {

    fun findByFromScreenIdOrderByIdAsc(fromScreenId: Long): Flow<ScreenTransitionEntity>
}

/**
 * 씬 전이. 정적 후보로 출발해 QA 런이 검증한다.
 *
 * [findByFromSceneIdAndVerifiedAtIsNullOrderByIdAsc] 가 커버리지 구멍의 목록이고, QA agent 에게 다음에 무엇을 시도할지 알려주는
 * 유일한 신호다.
 */
interface SceneEdgeRepository : CoroutineCrudRepository<SceneEdgeEntity, Long> {

    fun findByFromSceneIdOrderByIdAsc(fromSceneId: Long): Flow<SceneEdgeEntity>

    fun findByFromSceneIdAndVerifiedAtIsNullOrderByIdAsc(fromSceneId: Long): Flow<SceneEdgeEntity>

    fun findByFromSceneIdAndToSceneName(fromSceneId: Long, toSceneName: String): Flow<SceneEdgeEntity>

    /**
     * 근거가 말한 정적 전이를 넣거나 되맞추고 그 행의 id 를 돌려준다.
     *
     * **지웠다 넣지 않는다.** `capability_effect` 는 안정 키가 없어 그 길밖에 없었지만, 여기는
     * `uk_scene_edge (from_scene_id, to_scene_name, capability_id)` 가 이미 안정 키다. 그리고 이 행은
     * `verified_at` · `observed_count` · `first_observed_transition_id` 라는 **런타임이 벌어 온 지식**을
     * 든다 — 지웠다 넣으면 그 셋이 재적재마다 0 으로 돌아가고, `verified_at IS NULL` 이 곧 커버리지
     * 구멍이라는 이 표의 존재 이유가 매 스캔마다 거짓이 된다. `scene.walked` 를 보존하는 것과 같은
     * 규칙이다.
     *
     * 그래서 UPDATE 절에 정적 칸만 둔다. 실제로 남는 것은 `to_scene_id` 하나다 — 문서가 다시 말한
     * 이름은 같고, 달라질 수 있는 것은 "그 이름의 씬을 이제 순회했나"뿐이다.
     *
     * `to_scene_id` 를 인자로 받지 않고 하위 질의로 푸는 이유: 적재기가 든 씬 맵은 **이번 문서**가 말한
     * 씬만 담는다. 같은 지도의 앞선 문서가 만든 씬이 빠져, 표에 뻔히 있는 씬을 가리키는 간선이 null 로
     * 남는다.
     *
     * `source = 'runtime'` 행은 건드리지 않는다. 같은 셋 칸에 관측 행이 있으면 정적 분석이 뒤늦게
     * 따라잡은 것이고, 관측이 더 강한 근거다. 그때는 갱신이 0행이라 **null 이 돌아온다.**
     *
     * `@Modifying` 을 붙이지 않는다. 붙이면 Spring Data 가 반환값을 "영향받은 행 수"로 읽어
     * `RETURNING id` 대신 늘 1 이 돌아오고, 그 1 이 id 로 쓰여 [retireStaleStaticEdges] 가 살아 있는
     * 간선을 전부 지운다([CapabilityRepository.upsertByKey] 와 같은 함정이다).
     */
    @Query(
        """
        INSERT INTO scene_edge (from_scene_id, to_scene_name, to_scene_id, capability_id, source)
        VALUES (
            :fromSceneId,
            :toSceneName,
            (SELECT s.id FROM scene s WHERE s.content_map_id = :contentMapId AND s.name = :toSceneName),
            :capabilityId,
            'static'
        )
        ON CONFLICT (from_scene_id, to_scene_name, capability_id) DO UPDATE SET
            to_scene_id = EXCLUDED.to_scene_id
        WHERE scene_edge.source = 'static'
        RETURNING id
        """
    )
    suspend fun upsertStatic(
        fromSceneId: Long,
        toSceneName: String,
        capabilityId: Long,
        contentMapId: Long,
    ): Long?

    /**
     * 이번 문서가 더 이상 말하지 않는 정적 간선을 내린다. [keptIds] 는 이번에 [upsertStatic] 이 돌려준 id 다.
     *
     * 두 가지가 여기로 온다 — 기능은 그대로인데 도착 씬이 바뀐 간선, 그리고 기능째 사라진 간선.
     * 뒤엣것이 더 급하다: `CapabilityRepository.hasRuntimeReferences` 가 `scene_edge` 를 참조로 세기
     * 때문에, 정적 간선을 쓰기 시작하면 씬 효과를 든 기능은 **영원히 삭제 불가**가 되어 재적재가 늘
     * `not-applicable` 로만 내린다. 런타임 지식이 하나도 없는 정적 파생물이 기능 행을 살려 두는 셈이고,
     * 그 검사의 취지와 정반대다. 그래서 이 쓸어 내기는 `retireVanished` **앞에서** 돌아야 한다.
     *
     * 부작용으로 `ON DELETE SET NULL` 충돌도 막는다. 한 씬에서 같은 씬으로 가는 정적 간선 둘의 기능이
     * 한 번에 지워지면 `capability_id` 가 둘 다 NULL 이 되어 `uk_scene_edge_auto` 를 어기고, 그 거절이
     * 문서 하나를 통째로 되돌린다. 실측 픽스처에 그런 쌍이 있다(`Player.Death` → `GameOverScene` 이
     * 진입점 넷).
     *
     * 런타임 칸 셋이 **전부 비었을 때만** 지운다. QA 런이 한 번이라도 지나간 간선은 지식을 든 행이라,
     * 문서가 말을 바꿨다고 그것까지 버리지 않는다. `source = 'runtime'` 행도 마찬가지로 남는다 —
     * 정적 분석이 놓친 전이가 그것이다.
     *
     * [keptIds] 가 비면 `<> ALL ('{}')` 이 참이라 지도의 지식 없는 정적 간선이 전부 지워진다. 문서가
     * 씬 효과를 하나도 말하지 않게 된 경우의 정답이다.
     */
    @Modifying
    @Query(
        """
        DELETE FROM scene_edge e
        USING scene s
        WHERE e.from_scene_id = s.id
          AND s.content_map_id = :contentMapId
          AND e.source = 'static'
          AND e.verified_at IS NULL
          AND e.observed_count = 0
          AND e.first_observed_transition_id IS NULL
          AND e.id <> ALL (:keptIds)
        """
    )
    suspend fun retireStaleStaticEdges(contentMapId: Long, keptIds: Array<Long>): Long

    /**
     * 지도 한 장의 간선 전부. 화면이 씬 그래프를 한 번에 그린다.
     *
     * 씬마다 [findByFromSceneIdOrderByIdAsc] 를 부르지 않는 이유: 씬 수만큼 왕복이 생기고, 씬이
     * 없는 지도와 간선이 없는 지도가 같은 비용을 낸다. `scene` 조인 한 번이 같은 답을 준다.
     *
     * `capability` 를 `LEFT JOIN` 하는 것은 간선에 붙일 글자를 얻기 위해서다. `capability_id` 가
     * 단일 FK 라 **행이 곱해지지 않는다** — 효과(`capability_effect`)를 접지 않는 것과는 사정이
     * 다르다. `LEFT` 인 이유는 자동 전이의 `capability_id` 가 null 이고, 재적재가 기능을 지우면
     * `ON DELETE SET NULL` 로도 null 이 되기 때문이다. 그때도 "갔다는 사실"은 남아야 한다.
     */
    @Query(
        """
        SELECT e.from_scene_id,
               e.to_scene_name,
               e.to_scene_id,
               e.capability_id,
               c.summary AS capability_summary,
               e.given_text,
               e.source,
               e.verified_at
        FROM scene_edge e
        JOIN scene s ON s.id = e.from_scene_id
        LEFT JOIN capability c ON c.id = e.capability_id
        WHERE s.content_map_id = :contentMapId
        ORDER BY e.from_scene_id ASC, e.id ASC
        """
    )
    fun findByContentMapId(contentMapId: Long): Flow<ContentMapSceneEdgeRow>
}
