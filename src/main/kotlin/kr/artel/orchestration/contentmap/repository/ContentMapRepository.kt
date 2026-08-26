package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.dto.ContentMapCallEdge
import kr.artel.orchestration.contentmap.dto.ContentMapCapabilityRow
import kr.artel.orchestration.contentmap.dto.SpecGapRow
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * content_map 루트 조회.
 *
 * 키가 (게임 빌드, capture) 인 것이 이 도메인의 전제다 — `editor` 스캔과 `player` 스캔은 같은
 * 필드가 다른 뜻이라 한 행에 섞을 수 없다.
 */
interface ContentMapRepository : CoroutineCrudRepository<ContentMapEntity, Long> {

    suspend fun findByGameBuildIdAndCapture(gameBuildId: Long, capture: String): ContentMapEntity?

    fun findByGameBuildIdOrderByIdDesc(gameBuildId: Long): Flow<ContentMapEntity>

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
     * **호출로 이어진 결과 갈래**(ARTEL-554).
     *
     * 조작 갈래가 결과를 안 들고 있을 때 그 결과가 어디 있는지 찾는다. 코루틴·상태 머신에서는
     * 입력을 받는 갈래와 결과를 내는 갈래가 다른 행이고, **공통 호출자**만이 둘을 잇는다 —
     * 실측(word-venture)에서 `StoryController.StoryTelling()` 이 `IsAdvanceKeyDown` 과
     * `LoadMapScene` · `UpdateChatStream` 을 다 부른다.
     *
     * `entry_id` 공유로 이으면 안 된다. 진입점은 갈래의 출처이지 인과가 아니라, 그렇게 이었더니
     * "`RightArrow` 를 누르면 배경이 바뀐다"는 거짓 케이스가 나왔다(배경은 씬 진입 때 정해진다).
     *
     * 한 줄이 (**부른 메서드**, 불린 기능)이다. 기능 행이 아니라 메서드로 묶는 것이 요점이다 —
     * 코루틴 하나가 갈래 16개로 쪼개지고 **각 갈래가 호출을 하나씩만 든다.** 행 단위로 보면
     * `IsAdvanceKeyDown` 을 부른 갈래가 `LoadMapScene` 은 안 부르는 것으로 보인다. 메서드로 묶어야
     * 그 코루틴이 부르는 13개가 한자리에 모인다.
     *
     * 걸러 내지 않고 전부 낸다 — 조작 갈래는 효과가 없어서, 효과로 거르면 **그 갈래를 부른 쪽을
     * 찾을 수 없다.** 무엇을 이을지는 부르는 쪽이 정한다.
     *
     * 부른 쪽의 조건도 함께 내는 것은 그 호출이 조건 아래에서만 일어나기 때문이다 — 이은 케이스의
     * 사전조건이 그것을 함께 들어야 한다.
     */
    @Query(
        """
        SELECT caller.method_id AS caller_method_id,
               caller.condition_tree AS caller_condition,
               callee_cap.id AS capability_id,
               callee.condition_tree AS condition_tree
        FROM capability_evidence caller
        JOIN capability caller_cap ON caller_cap.id = caller.capability_id
        JOIN scene caller_scene ON caller_scene.id = caller_cap.scene_id
        CROSS JOIN LATERAL jsonb_array_elements(caller.calls) AS call
        JOIN capability_evidence callee ON callee.method_id = call->>'targetId'
        JOIN capability callee_cap ON callee_cap.id = callee.capability_id
        JOIN scene callee_scene ON callee_scene.id = callee_cap.scene_id
        WHERE caller_scene.content_map_id = :contentMapId
          AND callee_scene.content_map_id = :contentMapId
          AND caller_cap.merged_into IS NULL
          AND callee_cap.merged_into IS NULL
          AND caller.method_id IS NOT NULL
          AND callee.method_id IS DISTINCT FROM caller.method_id
        """
    )
    fun findCallEdges(contentMapId: Long): Flow<ContentMapCallEdge>

    /**
     * 이 지도가 어느 프로젝트의 것인가(ARTEL-578).
     *
     * 지도는 게임 빌드에 매달려 있고 프로젝트는 그 위에 있다. 케이스를 앉히려면 `project_id` 가
     * 있어야 하는데(`test_case` 의 소유자가 프로젝트다) 지도 행은 그것을 직접 안 든다.
     */
    @Query(
        """
        SELECT game_build.project_id
        FROM content_map
        JOIN game_build ON game_build.id = content_map.game_build_id
        WHERE content_map.id = :contentMapId
        """
    )
    suspend fun findProjectId(contentMapId: Long): Long?

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
