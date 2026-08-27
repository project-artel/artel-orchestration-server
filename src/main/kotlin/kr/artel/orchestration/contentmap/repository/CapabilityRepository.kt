package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.dto.SceneCapabilityCountRow
import kr.artel.orchestration.contentmap.dto.SceneCapabilityRow
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import org.springframework.data.r2dbc.repository.Modifying
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

    /**
     * 씬별 상태 분포. **뷰가 아니라 `capability` 를 직접 센다.**
     *
     * `v_content_map_capability` 로는 답할 수 없는 질문이다 — 그 뷰는 `status <> 'not-a-step'` 으로
     * 이미 걸러 내므로 `not_a_step` 이 구조적으로 0 이고, 씬별 합이 지도의 기능 총수와 어긋난다.
     * 뷰가 쓰는 나머지 필터(`merged_into IS NULL`)만 같이 두어, `total - not_a_step` 이 뷰의 행
     * 수와 같아지게 한다.
     *
     * Kotlin 에서 [findEvidenceCapabilitiesOfMap] 을 훑어 세지 않는 이유: 정수 다섯 개를 얻으려고
     * 스키마에서 가장 넓은 표의 수백 행을 조건 트리·힌트 파라미터까지 통째로 실어 나른다. `GROUP BY`
     * 는 씬당 한 줄이다.
     *
     * `status` 로 세는 것은 축 셋(`actionability`·`observability`·`applicability`)으로 각각 세는 것과
     * 다르다. 화면이 묻는 것은 "TC 로 만들 수 있나"이고 그 답이 유도 컬럼인 `status` 다.
     */
    @Query(
        """
        SELECT c.scene_id                                                   AS scene_id,
               count(*)                                                     AS total,
               count(*) FILTER (WHERE c.status = 'runnable')                 AS runnable,
               count(*) FILTER (WHERE c.status = 'needs-probe')              AS needs_probe,
               count(*) FILTER (WHERE c.status = 'not-a-step')               AS not_a_step,
               count(*) FILTER (WHERE c.status = 'unreachable-precondition') AS unreachable_precondition
        FROM capability c
        JOIN scene s ON s.id = c.scene_id
        WHERE s.content_map_id = :contentMapId AND c.merged_into IS NULL
        GROUP BY c.scene_id
        """
    )
    fun countByScene(contentMapId: Long): Flow<SceneCapabilityCountRow>

    /**
     * 씬별 카운트 뒤의 **행 목록.** 인스펙터가 "그 440 이 무엇인가"를 물을 때 답하는 자리다.
     *
     * [countByScene] 과 **필터도 조인도 똑같다.** 베낀 것이 아니라 같아야 하는 것이다 — 두 질의가
     * 다른 집합을 보면 `capabilityList.size == capabilities.total` 이 조용히 깨지고, 화면은 개수와
     * 목록 중 어느 쪽이 거짓인지 알 수 없다. 필터를 손대면 위아래를 함께 손댄다.
     *
     * `v_content_map_capability` 로 답할 수 없는 것도 [countByScene] 과 같은 이유다. 그 뷰는
     * `not-a-step` 을 걸러 내고, 이 목록이 설명해야 하는 것이 바로 그 걸러진 행들이다. 조작이 있는
     * 행의 컨트롤 정보는 이미 `steps` 가 들고 있으므로 여기서는 다시 담지 않는다.
     *
     * 판정 세 축을 함께 내는 이유: `status` 는 그 셋에서 유도된 값이라, 축 없이 `status` 만 보면
     * 화면이 "왜 runnable 이 아닌가"를 답할 수 없다.
     */
    @Query(
        """
        SELECT c.scene_id, c.id AS capability_id, c.summary, c.status, c.origin, c.verification,
               c.actionability, c.observability, c.applicability, c.interaction
        FROM capability c
        JOIN scene s ON s.id = c.scene_id
        WHERE s.content_map_id = :contentMapId AND c.merged_into IS NULL
        ORDER BY c.scene_id ASC, c.id ASC
        """
    )
    fun findSceneCapabilities(contentMapId: Long): Flow<SceneCapabilityRow>

    /**
     * 안정 키로 넣거나 갱신하고 id 를 돌려준다.
     *
     * `save()` 를 못 쓰는 이유: 적재기는 id 를 모르고 키만 안다. 조회 후 분기하면 같은 문서를 두 번
     * 적재할 때 경합에서 유니크에 걸린다.
     *
     * **`verification` 을 UPDATE 절에 두지 않는다.** 되돌릴지는 근거가 실제로 달라졌는지가 정하고, 그
     * 판정은 적재기가 따로 내린다 — 여기서 매번 덮으면 재적재가 확인을 통째로 버린다. `created_at` 도
     * 그대로 둔다. 처음 안 시점은 스캔이 다시 돌았다고 바뀌지 않는다.
     *
     * **`@Modifying` 을 붙이지 않는다.** 붙이면 Spring Data 가 반환값을 "영향받은 행 수"로 읽어
     * `RETURNING id` 대신 늘 1 이 돌아오고, 그 1 이 id 로 쓰여 **모든 근거 행이 첫 기능에 붙는다.**
     */
    @Query(
        """
        INSERT INTO capability (
            scene_id, content_map_id, capability_key, origin, verification, summary, given_text,
            control_selector, control_path, control_label, spawned_by_field, spawned_by_scene_path,
            interaction, input_key, input_phase, actionability, observability, applicability
        ) VALUES (
            :sceneId, :contentMapId, :capabilityKey, 'evidence', :verification, :summary, :givenText,
            :controlSelector, :controlPath, :controlLabel, :spawnedByField, :spawnedByScenePath,
            :interaction, :inputKey, :inputPhase, :actionability, :observability, :applicability
        )
        ON CONFLICT (content_map_id, capability_key) DO UPDATE SET
            scene_id = EXCLUDED.scene_id,
            summary = EXCLUDED.summary,
            given_text = EXCLUDED.given_text,
            control_selector = EXCLUDED.control_selector,
            control_path = EXCLUDED.control_path,
            control_label = EXCLUDED.control_label,
            spawned_by_field = EXCLUDED.spawned_by_field,
            spawned_by_scene_path = EXCLUDED.spawned_by_scene_path,
            interaction = EXCLUDED.interaction,
            input_key = EXCLUDED.input_key,
            input_phase = EXCLUDED.input_phase,
            actionability = EXCLUDED.actionability,
            observability = EXCLUDED.observability,
            applicability = EXCLUDED.applicability,
            updated_at = CURRENT_TIMESTAMP
        RETURNING id
        """
    )
    suspend fun upsertByKey(
        sceneId: Long,
        contentMapId: Long,
        capabilityKey: String,
        verification: String,
        summary: String,
        givenText: String?,
        controlSelector: String?,
        controlPath: String?,
        controlLabel: String?,
        spawnedByField: String?,
        spawnedByScenePath: String?,
        interaction: String,
        inputKey: String?,
        inputPhase: String?,
        actionability: String,
        observability: String,
        applicability: String,
    ): Long

    /** 안정 키로 기존 행 찾기. 재적재가 무엇을 덮어쓰는지 알아야 확인을 되돌릴지 정할 수 있다. */
    suspend fun findByContentMapIdAndCapabilityKey(contentMapId: Long, capabilityKey: String): CapabilityEntity?

    /**
     * 근거가 달라진 기능의 확인을 되돌린다.
     *
     * 재적재마다 무조건 되돌리지 않는 이유: 문서는 코드 한 줄만 바뀌어도 새로 구워지고, 그때 멀쩡한
     * 기능 수백 개의 확인을 함께 버리면 QA 가 매번 같은 것을 다시 눌러야 한다.
     */
    @Modifying
    @Query(
        """
        UPDATE capability SET verification = 'unverified', updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND verification <> 'unverified'
        """
    )
    suspend fun resetVerification(id: Long): Long

    /** 이 지도의 근거 출신 기능 전부. 이번 문서에 없는 것을 가리려면 먼저 있는 것을 알아야 한다. */
    @Query(
        """
        SELECT c.* FROM capability c
        WHERE c.content_map_id = :contentMapId AND c.origin = 'evidence' AND c.merged_into IS NULL
        ORDER BY c.id ASC
        """
    )
    fun findEvidenceCapabilitiesOfMap(contentMapId: Long): Flow<CapabilityEntity>

    /**
     * 이 기능에 런타임 지식이 매달려 있나.
     *
     * 매달린 것이 있으면 지우지 않는다 — `capability_observation` · `screen_capability` 는 CASCADE 라
     * 관측이 통째로 사라지고, `scene_edge` · `screen_transition` 은 SET NULL 이라 지식이 주인을 잃는다.
     * 사라진 기능을 표에서 내리는 일이 QA 가 실제로 눌러 본 기록을 지울 값을 하지는 않는다.
     */
    @Query(
        """
        SELECT EXISTS (
            SELECT 1 FROM capability_observation o WHERE o.capability_id = :capabilityId
            UNION ALL
            SELECT 1 FROM screen_capability sc WHERE sc.capability_id = :capabilityId
            UNION ALL
            SELECT 1 FROM scene_edge e WHERE e.capability_id = :capabilityId
            UNION ALL
            SELECT 1 FROM screen_transition t WHERE t.capability_id = :capabilityId
            UNION ALL
            SELECT 1 FROM capability m WHERE m.merged_into = :capabilityId
        )
        """
    )
    suspend fun hasRuntimeReferences(capabilityId: Long): Boolean


    /**
     * 참조가 매달린 사라진 기능을 내리는 자리. 행은 남고 TC 창구에서만 빠진다.
     *
     * 이미 내려간 행은 건드리지 않는다(갱신 0건). 다시 세면 적재 결과의 "이번에 내린 수"가 매 적재마다
     * 같은 행을 다시 세어, 표가 안정된 뒤에도 계속 무언가 벌어지는 것처럼 보인다.
     */
    @Modifying
    @Query(
        """
        UPDATE capability SET applicability = 'not-applicable', updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND applicability <> 'not-applicable'
        """
    )
    suspend fun markNotApplicable(id: Long): Long
}

/** [CapabilityRepository.countEvidenceVerification] 결과. */
data class VerificationCount(
    val verified: Long,
    val total: Long,
)
