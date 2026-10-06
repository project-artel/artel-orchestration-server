package kr.artel.orchestration.contentmap.repository

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.MacroEntity
import kr.artel.orchestration.contentmap.entity.ScreenMacroEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** 등록된 macro 정의(ARTEL-919). */
interface MacroRepository : CoroutineCrudRepository<MacroEntity, Long> {

    /**
     * 이름으로 넣거나 **제자리에서** 갱신하고 id 를 돌려준다.
     *
     * `save()` 를 못 쓰는 이유는 등록하는 쪽이 id 를 모르고 이름만 알기 때문이다. 그리고 조회 후
     * 분기하면 같은 이름이 동시에 둘 올 때 그 사이로 빠져나가 유니크에 걸린다.
     *
     * **지우고 새로 넣지 않는 것이 이 메서드의 요점이다.** `screen_macro` 가 양쪽 FK 를
     * `ON DELETE CASCADE` 로 물고 있어, 지우고 넣으면 그 macro 에 달린 관계 행이 같이 사라진다.
     * 그것은 agent 가 공들여 단 것이라 정의를 고칠 때마다 잃으면 안 된다. `ON CONFLICT DO UPDATE`
     * 는 행의 `id` 를 바꾸지 않으므로 관계 행이 그대로 남는다.
     *
     * `created_at` 은 UPDATE 절에 두지 않는다. 처음 등록한 시점은 정의를 고쳤다고 바뀌지 않는다.
     *
     * **`@Modifying` 을 붙이지 않는다.** 붙이면 Spring Data 가 반환값을 "영향받은 행 수" 로 읽어
     * `RETURNING id` 대신 늘 1 이 돌아오고, 그 1 이 id 로 쓰여 모든 관계 행이 첫 macro 에 붙는다
     * ([CapabilityRepository.upsertByKey] 가 같은 함정을 적어 두었다).
     *
     * 충돌했을 때도 unique violation 이 아니라 UPDATE 로 가므로 늘 한 행이 돌아온다 —
     * `DO NOTHING` 과 달리 0 행이 되지 않는다.
     */
    @Query(
        """
        INSERT INTO macro (content_map_id, name, source, definition_json, parameter_names)
        VALUES (:contentMapId, :name, :source, :definitionJson, :parameterNames)
        ON CONFLICT (content_map_id, name) DO UPDATE SET
            source = EXCLUDED.source,
            definition_json = EXCLUDED.definition_json,
            parameter_names = EXCLUDED.parameter_names,
            updated_at = CURRENT_TIMESTAMP
        RETURNING id
        """
    )
    suspend fun upsertByName(
        contentMapId: Long,
        name: String,
        source: String,
        definitionJson: Json,
        parameterNames: Json,
    ): Long

    /**
     * 이 build 의 지도에서 이름으로 찾는다.
     *
     * `content_map_id` 로 좁히는 것이 계약이다. 이름의 유일 범위가 그 안이므로, 좁히지 않으면 다른
     * build 에 등록된 같은 이름의 macro 를 돌려줄 수 있다 — 그 정의는 이 게임 build 에서 돌지
     * 않는다.
     */
    suspend fun findByContentMapIdAndName(contentMapId: Long, name: String): MacroEntity?
}

/**
 * macro 와 `screen` 의 관계(ARTEL-919).
 *
 * 복합 PK 라 `CoroutineCrudRepository.save()` 로 쓸 수 없다 — 단일 `@Id` 가 없으면 Spring Data 가
 * 신규/기존을 판별하지 못한다. 명시 INSERT 로 쓴다([ScreenCapabilityRepository] 와 같은 사정이다).
 *
 * 조회만 쓰는 [CoroutineCrudRepository] 의 id 타입은 형식상 `Long` 이고 id 기반 메서드는 쓰지 않는다.
 */
interface ScreenMacroRepository : CoroutineCrudRepository<ScreenMacroEntity, Long> {

    /**
     * 이 `screen` 에서 이 macro 를 쓸 수 있다는 관계를 **더한다.**
     *
     * 멱등을 앱의 `if` 로 막지 않는다. 같은 쌍이 동시에 둘 오면 조회와 INSERT 사이로 빠져나가고,
     * 그때 PK 위반 예외가 receive 체인 밖으로 나가면 WebSocket 이 닫혀 런 전체가 실패한다.
     * `ON CONFLICT DO NOTHING` 이 그것을 DB 에서 막는다.
     *
     * 관계를 빼는 길은 v1 에 없다. 더하기만 한다.
     */
    @Modifying
    @Query(
        """
        INSERT INTO screen_macro (screen_id, macro_id)
        VALUES (:screenId, :macroId)
        ON CONFLICT (screen_id, macro_id) DO NOTHING
        """
    )
    suspend fun link(screenId: Long, macroId: Long): Long

    /**
     * 이 macro 가 달린 `screen` 전부. 오름차순이라 같은 관계를 다시 받아도 응답이 흔들리지 않는다.
     *
     * PK `(screen_id, macro_id)` 의 두 번째 컬럼으로 찾으므로 `idx_screen_macro_macro` 를 탄다.
     *
     * 반대 방향(`screen` 으로 macro 찾기)은 메서드를 두지 않는다. 이 PR 에 호출부가 없고, 그쪽을
     * 읽는 것은 scene context 응답(ARTEL-934)의 몫이다. PK 의 선두 컬럼이 `screen_id` 라 그 조회는
     * index 를 더하지 않고도 선다.
     */
    @Query("SELECT screen_id FROM screen_macro WHERE macro_id = :macroId ORDER BY screen_id ASC")
    fun findScreenIdsByMacroId(macroId: Long): Flow<Long>
}
