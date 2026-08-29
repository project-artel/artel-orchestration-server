package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.dto.ContentMapSceneEdgeRow
import kr.artel.orchestration.contentmap.dto.ContentMapScreenTransitionRow
import kr.artel.orchestration.contentmap.dto.ScreenObservationRow
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.entity.ScreenTransitionEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/**
 * 화면. QA 런 전에는 0행이고 그게 정상이다 — 정적 분석은 화면을 모른다.
 */
interface ScreenRepository : CoroutineCrudRepository<ScreenEntity, Long> {

    fun findBySceneIdOrderByIdAsc(sceneId: Long): Flow<ScreenEntity>

    /** 이 씬이 이미 몇 개의 화면으로 갈렸나. 화면 폭발의 안전판이 이 값을 읽는다. */
    suspend fun countBySceneId(sceneId: Long): Long

    /**
     * 이 `discriminator` 의 화면을 앉히거나, 이미 있으면 방문 수를 올리고 그 행의 id 를 돌려준다 (ARTEL-453).
     *
     * 멱등을 코드가 아니라 `uk_screen_discriminator`(V59) 가 강제한다. `fold` 상태는 프로세스
     * 메모리라 재시작하면 사라지고, 같은 빌드를 두 서버가 관측하면 각자 자기 메모리를 본다 —
     * 코드가 "처음 보는 `discriminator` 인가"를 판정하면 그때마다 같은 화면이 행 둘로 갈리고
     * `observed_count` 가 둘에 쪼개져 양쪽 다 틀린 값이 된다.
     *
     * `first_seen_qa_run_id` 는 `DO UPDATE` 에 없다. 어느 런에서 **처음** 봤나가 이 칸의 뜻이라,
     * 재방문이 덮으면 뜻이 사라진다.
     *
     * `observed_count` 는 `pulse` 수가 아니라 **방문 수**다. 부르는 쪽이 화면이 굳는 순간에만
     * 부르므로(`ScreenFold.settle`), 한 화면에 오래 머물러도 1 이다.
     *
     * `@Modifying` 을 붙이지 않는다. 붙이면 Spring Data 가 반환값을 영향받은 행 수로 읽어
     * `RETURNING id` 대신 늘 1 이 돌아온다([SceneEdgeRepository.upsertStatic] 과 같은 함정이다).
     *
     * ## `xmax = 0` 이 무엇을 말하나 (ARTEL-456)
     *
     * upsert 는 새로 앉힌 것과 다시 본 것을 구분하지 못한다. 그런데 화면 `screen capture` 는 **처음 앉히는
     * 그 순간에만** 요청해야 한다 — 관측마다 요청하면 같은 화면을 볼 때마다 다시 찍혀서
     * "처음 것만 남긴다" 가 그 자리에서 무너진다.
     *
     * Postgres 는 그 둘을 구분할 수 있다. `RETURNING` 이 내주는 행의 `xmax` 는 INSERT 로 앉은
     * 튜플에서는 0 이고, `DO UPDATE` 로 간 튜플에서는 그 행을 잠근 트랜잭션 id 라 0 이 아니다.
     * Postgres 16 에서 같은 문장을 반복해 돌려 확인했다 — 첫 번째만 `t`, 이후는 `f` 이며 한
     * 트랜잭션 안에서 두 번 돌려도 같다.
     *
     * `observed_count = 1` 로 대신 판정하지 않는 이유: 그 값은 `V60` 같은 병합 마이그레이션이
     * 합산해 다시 쓰는 칸이라, 행이 언제 생겼는가를 그 칸으로 되짚으면 마이그레이션이 답을 바꾼다.
     */
    @Query(
        """
        INSERT INTO screen (scene_id, discriminator, first_seen_qa_run_id, observed_count)
        VALUES (:sceneId, CAST(:discriminator AS jsonb), :qaRunId, 1)
        ON CONFLICT (scene_id, discriminator) DO UPDATE SET
            observed_count = screen.observed_count + 1
        RETURNING id, (xmax = 0) AS inserted
        """
    )
    suspend fun observe(sceneId: Long, discriminator: String, qaRunId: Long?): ScreenObservationRow

    /**
     * 이 화면에 `screen capture` 이미지를 묶는다. **이미 그림이 있으면 아무것도 안 한다** (ARTEL-456).
     *
     * `WHERE image_object_key IS NULL` 이 "처음 것만 남긴다" 를 SQL 로 강제한다. 코드가 먼저 읽고
     * 판단하는 형태로 두면, 늦게 도착한 두 번째 결과가 그 사이에 끼어 첫 그림을 덮는다.
     *
     * `image_captured_at` 은 `image_object_key` 와 같은 문장에서만 움직인다. 둘은 한 `screen capture` 의
     * 두 칸이라 따로 쓰면 이미지와 시각이 어긋난다(`V60` 이 병합할 때 든 것과 같은 규율이다).
     *
     * @return 실제로 묶은 행 수. 0 이면 이미 그림이 있었거나 그 화면이 사라진 것이다.
     */
    @Modifying
    @Query(
        """
        UPDATE screen SET
            image_object_key = :objectKey,
            image_captured_at = :capturedAt
        WHERE id = :screenId AND image_object_key IS NULL
        """
    )
    suspend fun attachImageIfAbsent(screenId: Long, objectKey: String, capturedAt: Instant): Long

    /**
     * 이 `discriminator` 의 화면이 이미 있나 (ARTEL-453).
     *
     * 화면 폭발 안전판이 걸린 뒤에만 쓴다. 안전판은 **새 화면**을 막자는 것이지 이미 아는 화면의
     * 재방문까지 얼릴 이유는 없어서, 상한을 넘은 씬에서는 이 조회로 기존 행만 갱신한다.
     */
    @Query(
        """
        SELECT id FROM screen
        WHERE scene_id = :sceneId AND discriminator = CAST(:discriminator AS jsonb)
        """
    )
    suspend fun findIdBySceneIdAndDiscriminator(sceneId: Long, discriminator: String): Long?

    /**
     * 지도 한 장의 화면 전부. 조회가 쓰는 유일한 화면 질의다.
     *
     * 씬마다 [findBySceneIdOrderByIdAsc] 를 부르지 않는 이유: 씬 수만큼 왕복이 생기고 그 수는 지도가
     * 커질수록 는다. `content_map_id` 하나로 좁힌 질의 한 번이 같은 답을 낸다 —
     * `ContentMapViewService` 가 섹션마다 질의 하나를 두는 것과 같은 규칙이다.
     *
     * `screen.id` 오름차순, 즉 **처음 관측한 순서**로 낸다. [ScreenEntity.name] 으로 정렬하지 않는
     * 것은 그 칸이 nullable 이고 LLM 이 짓는 표시용 값이라, 이름을 다시 지으면 화면 순서가 통째로
     * 흔들리기 때문이다.
     */
    @Query(
        """
        SELECT sc.* FROM screen sc
        JOIN scene s ON s.id = sc.scene_id
        WHERE s.content_map_id = :contentMapId
        ORDER BY sc.scene_id ASC, sc.id ASC
        """
    )
    fun findByContentMapId(contentMapId: Long): Flow<ScreenEntity>
}

/** 화면 전이. 관측으로만 생긴다. */
interface ScreenTransitionRepository : CoroutineCrudRepository<ScreenTransitionEntity, Long> {

    fun findByFromScreenIdOrderByIdAsc(fromScreenId: Long): Flow<ScreenTransitionEntity>

    /**
     * 관측한 전이를 앉히거나 관측 수를 올리고 그 행의 id 를 돌려준다 (ARTEL-453).
     *
     * `capability_id` 를 받지 않는다. 무엇이 이 전이를 일으켰는지는 액션과 `pulse` 를 시간축으로
     * 붙이는 ARTEL-450 이 알려 주고, 그 전에는 **정직하게 귀속할 방법이 없다.** 추측을 넣으면
     * "실제로 어떻게 흘렀나"가 오염되고, 그것은 이 표가 정적으로 만들어지지 않는 이유와 같은
     * 이유로 하면 안 되는 일이다.
     *
     * 그래서 충돌 판정도 `uk_screen_transition_auto`(부분 유니크, `capability_id IS NULL`) 쪽으로
     * 건다. 전체 유니크는 NULL 을 서로 다른 값으로 보아 걸리지 않고, 그러면 같은 전이가 관측마다
     * 새 행이 된다.
     *
     * `kind` 와 `crosses_scene` 은 갱신하지 않는다. 두 값은 이 전이를 같은 전이로 볼지와 함께 정해지고,
     * 재관측이 다르게 말한다면 그것은 갱신이 아니라 다른 전이라는 신호다.
     *
     * `@Modifying` 을 붙이지 않는 이유는 [ScreenRepository.observe] 와 같다.
     */
    @Query(
        """
        INSERT INTO screen_transition (
            from_screen_id, to_screen_id, capability_id, kind, crosses_scene,
            observed_count, first_seen_qa_run_id
        )
        VALUES (:fromScreenId, :toScreenId, NULL, :kind, :crossesScene, 1, :qaRunId)
        ON CONFLICT (from_screen_id, to_screen_id) WHERE capability_id IS NULL DO UPDATE SET
            observed_count = screen_transition.observed_count + 1
        RETURNING id
        """
    )
    suspend fun observeUnattributed(
        fromScreenId: Long,
        toScreenId: Long,
        kind: String,
        crossesScene: Boolean,
        qaRunId: Long?,
    ): Long

    /**
     * 지도 한 장의 화면 전이 전부. 조회가 쓰는 유일한 전이 질의다.
     *
     * **출발 화면 기준으로 모은다.** 씬 경계를 넘는 전이는 도착 화면이 다른 씬에 있고 그 씬도 같은
     * 지도에 속한다 — QA 런 하나가 지도 한 장 안에서 움직이기 때문이다. 도착 쪽까지 조인해 걸러내면
     * 그 전제가 깨진 날 행이 조용히 사라지는데, 사라진 전이는 화면에서 없던 것과 구분되지 않는다.
     * 그대로 내고, 화면이 모르는 화면 id 를 만나면 그것이 신호다.
     *
     * `capability` 를 `LEFT JOIN` 해도 **행이 곱해지지 않는다.** `screen_transition.capability_id`
     * 는 단일 FK 라 전이 하나에 기능이 많아야 하나다 — 효과(`capability_effect`)를 접지 않는 것과는
     * 사정이 다르고, [SceneEdgeRepository.findByContentMapId] 가 이미 같은 판단을 했다.
     */
    @Query(
        """
        SELECT t.id, t.from_screen_id, t.to_screen_id, t.capability_id,
               c.summary AS capability_summary,
               t.kind, t.crosses_scene, t.observed_count, t.first_seen_qa_run_id
        FROM screen_transition t
        JOIN screen fs ON fs.id = t.from_screen_id
        JOIN scene s ON s.id = fs.scene_id
        LEFT JOIN capability c ON c.id = t.capability_id
        WHERE s.content_map_id = :contentMapId
        ORDER BY t.id ASC
        """
    )
    fun findByContentMapId(contentMapId: Long): Flow<ContentMapScreenTransitionRow>
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
     * `evidence` 가 말한 정적 전이를 넣거나 되맞추고 그 행의 id 를 돌려준다.
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
               ce.condition_tree,
               e.source,
               e.verified_at
        FROM scene_edge e
        JOIN scene s ON s.id = e.from_scene_id
        LEFT JOIN capability c ON c.id = e.capability_id
        LEFT JOIN capability_evidence ce ON ce.capability_id = e.capability_id
        WHERE s.content_map_id = :contentMapId
        ORDER BY e.from_scene_id ASC, e.id ASC
        """
    )
    fun findByContentMapId(contentMapId: Long): Flow<ContentMapSceneEdgeRow>

    /**
     * 관측한 씬 전이로 정적 후보를 검증됨으로 올리고, 올린 행 수를 돌려준다 (ARTEL-453).
     *
     * **기능 단위가 아니라 씬 쌍 단위로 올린다.** 같은 씬 쌍으로 가는 정적 간선이 여럿이면
     * (실측: `Player.Death` → `GameOverScene` 이 진입점 넷) 그 전부가 검증됨이 된다. 어느
     * 기능이 실제로 눌렸는지는 ARTEL-450 이 붙기 전에는 알 수 없고, 여기서 하나를 골라 집으면
     * "안다"와 "여럿 중 하나를 골랐다"가 구분되지 않는다.
     *
     * 과다 주장인 것은 맞다 — 눌린 적 없는 기능이 커버리지에서 덮인 것으로 읽힌다. 반대쪽,
     * 즉 아무것도 안 올리는 선택은 `verified_at IS NULL` 이 곧 커버리지 구멍이라는 이 표의
     * 존재 이유를 영영 틀리게 둔다. 관측할 수 있는 단위(씬 쌍)에서 참인 쪽을 골랐다.
     *
     * `verified_at` 과 `first_observed_transition_id` 는 **처음 것만 남긴다**(`COALESCE`).
     * 언제 처음 갔나가 두 칸의 뜻이다.
     */
    @Modifying
    @Query(
        """
        UPDATE scene_edge SET
            verified_at = COALESCE(verified_at, :observedAt),
            observed_count = observed_count + 1,
            first_observed_transition_id = COALESCE(first_observed_transition_id, :transitionId)
        WHERE from_scene_id = :fromSceneId AND to_scene_name = :toSceneName
        """
    )
    suspend fun verifyByScenePair(
        fromSceneId: Long,
        toSceneName: String,
        transitionId: Long,
        observedAt: Instant,
    ): Long

    /**
     * 정적 후보에 없던 전이를 `source='runtime'` 으로 남긴다 (ARTEL-453).
     *
     * **이것은 오류가 아니라 발견이다** — 정적 분석이 놓친 씬 전이이고, `evidence` 수집을 어디서
     * 고칠지 알려주는 신호다.
     *
     * `capability_id` 는 null 이다. 무엇으로 갔는지는 ARTEL-450 이 붙기 전에는 모르고, 갔다는
     * 사실은 그것과 무관하게 참이다 — `scene_edge` 의 `ON DELETE SET NULL` 이 든 것과 같은 판단이다.
     * 따라서 충돌도 `uk_scene_edge_auto`(부분 유니크) 로 건다.
     *
     * `to_scene_id` 를 하위 질의로 푸는 것은 [upsertStatic] 과 같은 이유다. 다만 여기서는 값이
     * 있는 쪽이 보통이다 — 관측한 전이의 도착 씬은 방금 `pulse` 가 이름을 댄 씬이고, 이름을 댄 씬은
     * 대개 순회된 씬이다. 순회 안 된 씬으로 갔다면 그 자체가 또 하나의 발견이라 null 로 남긴다.
     */
    @Query(
        """
        INSERT INTO scene_edge (
            from_scene_id, to_scene_name, to_scene_id, capability_id, source,
            verified_at, observed_count, first_observed_transition_id
        )
        VALUES (
            :fromSceneId,
            :toSceneName,
            (SELECT s.id FROM scene s WHERE s.content_map_id = :contentMapId AND s.name = :toSceneName),
            NULL,
            'runtime',
            :observedAt,
            1,
            :transitionId
        )
        ON CONFLICT (from_scene_id, to_scene_name) WHERE capability_id IS NULL DO UPDATE SET
            observed_count = scene_edge.observed_count + 1,
            verified_at = COALESCE(scene_edge.verified_at, EXCLUDED.verified_at),
            first_observed_transition_id =
                COALESCE(scene_edge.first_observed_transition_id, EXCLUDED.first_observed_transition_id)
        RETURNING id
        """
    )
    suspend fun observeRuntime(
        fromSceneId: Long,
        toSceneName: String,
        contentMapId: Long,
        transitionId: Long,
        observedAt: Instant,
    ): Long
}
