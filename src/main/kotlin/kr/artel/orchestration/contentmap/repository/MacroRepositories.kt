package kr.artel.orchestration.contentmap.repository

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.dto.MacroDetailRow
import kr.artel.orchestration.contentmap.dto.MacroScreenRow
import kr.artel.orchestration.contentmap.dto.MacroSummaryRow
import kr.artel.orchestration.contentmap.dto.MacroUpsertRow
import kr.artel.orchestration.contentmap.entity.MacroEntity
import kr.artel.orchestration.contentmap.entity.ScreenMacroEntity
import kr.artel.orchestration.contentmap.macro.MacroWriteFrames
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 조회가 읽는 macro 칸과, 선언 타입을 tree 에서 **SQL 로 잘라내는** 한 조각(ARTEL-943).
 *
 * `const val` 로 둔 것은 목록과 상세가 글자까지 같은 조각을 써야 하기 때문이다. 두 질의에 따로
 * 적으면 한쪽만 고쳐지는 날 목록과 상세가 서로 다른 타입을 말한다 — `ContentMapViewService` 가
 * 조건 파서를 적재기와 한 벌로 쓰는 것과 같은 이유다.
 *
 * **tree 전체를 끌고 오지 않으려고 SQL 에서 자른다.** `definition_json` 은 직렬화 200,000자까지
 * 들어오는데(`MacroWriteFrames.MAX_DEFINITION_LENGTH`) 조회가 쓰는 것은 진입점 `def` 의
 * `parameters` 배열 하나뿐이다. Kotlin 으로 올려서 거르면 목록 하나에 메가바이트가 오간다.
 *
 * 선언 타입이 `macro.parameter_names` 에 없는 것은 `V98` 의 결정이다 — 그 칸은 `run_macro` 가
 * 인자를 위치로 대응시킬 때 보는 이름과 순서만 담고, 타입은 tree 쪽이 원본이다.
 *
 * **tree key 이름을 여기 적지 않는다.** `MacroWriteFrames` 가 그 셋을 들고 있고 이 식이 보간해
 * 쓴다 — `MacroViewService.declaredTypes` 가 같은 object 에서 나머지 둘을 읽으므로, SQL 과 Kotlin
 * 이 tree 에 대해 아는 것이 한 자리에 모인다. `ck_macro_require_carries_remedy` 와
 * `MacroDefinitionService.hasRequireWithoutRemedy` 가 `KIND_FIELD` · `REMEDY_FIELD` 를 공유하는
 * 것과 같은 모양이다.
 *
 * **tree 의 모양이 다르면 null 로 떨어진다.** `defs` 가 배열이 아니거나 진입점 이름과 맞는 `def`
 * 가 없으면 `CASE` 가 NULL 을 낸다. ARTEL-918 이 tree 를 확정할 때 key 이름을 바꾸면 타입만 비고
 * 이름과 순서는 `parameter_names` 가 그대로 낸다 — 조회가 통째로 깨지는 것보다 이쪽이 낫다.
 * `jsonb_typeof` 가드가 없으면 `jsonb_array_elements` 가 배열 아닌 값에 예외를 던진다.
 */
private const val MACRO_SUMMARY_COLUMNS = """
    m.id, m.name, m.parameter_names, m.updated_at,
    CASE WHEN jsonb_typeof(m.definition_json -> '${MacroWriteFrames.DEFS_FIELD}') = 'array' THEN (
        SELECT d.value -> '${MacroWriteFrames.PARAMETERS_FIELD}'
        FROM jsonb_array_elements(m.definition_json -> '${MacroWriteFrames.DEFS_FIELD}') AS d
        WHERE d.value ->> '${MacroWriteFrames.DEF_NAME_FIELD}' = m.name
        LIMIT 1
    ) END AS declared_parameters
"""

/**
 * macro 와 `screen` 의 관계에 사람이 알아볼 이름을 붙이는 조인(ARTEL-943).
 *
 * `screen_macro` 만 읽으면 id 뿐이라 화면이 그 숫자를 보여 줄 수 없다. `screen` 에
 * `content_map_id` 칸이 없어(`V40`) `scene` 까지 지나가고, 그 `scene` 이 지도를 좁히는 조건이자
 * `scene_name` 의 출처다. 전부 INNER JOIN 이고 FK 가 다 NOT NULL 이라 행이 늘지도 줄지도 않는다.
 *
 * `ORDER BY` 두 칸은 응답을 고정하려는 것이다. macro 로 묶어 접을 수 있어야 하고, 같은 관계를
 * 다시 받아도 `screen` 순서가 흔들리면 화면이 이유 없이 다시 그려진다.
 */
private const val MACRO_SCREEN_JOIN = """
    SELECT sm.macro_id, sc.id AS screen_id, sc.name AS screen_name, s.name AS scene_name
    FROM screen_macro sm
    JOIN macro m ON m.id = sm.macro_id
    JOIN screen sc ON sc.id = sm.screen_id
    JOIN scene s ON s.id = sc.scene_id
"""

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
     *
     * `(xmax = 0) AS inserted` 가 새로 등록한 것과 갱신한 것을 가른다. 그 값을 upsert **전에**
     * 따로 조회해 비교하면 같은 이름이 동시에 둘 올 때 둘 다 "없었다" 를 읽어 둘 다 새로
     * 만들었다고 답한다. 읽는 법은 [ScreenRepository.observe] 의 KDoc 에 있다.
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
        RETURNING id, (xmax = 0) AS inserted
        """
    )
    suspend fun upsertByName(
        contentMapId: Long,
        name: String,
        source: String,
        definitionJson: Json,
        parameterNames: Json,
    ): MacroUpsertRow

    /**
     * 이 build 의 지도에서 이름으로 찾는다.
     *
     * `content_map_id` 로 좁히는 것이 계약이다. 이름의 유일 범위가 그 안이므로, 좁히지 않으면 다른
     * build 에 등록된 같은 이름의 macro 를 돌려줄 수 있다 — 그 정의는 이 게임 build 에서 돌지
     * 않는다.
     */
    suspend fun findByContentMapIdAndName(contentMapId: Long, name: String): MacroEntity?

    /**
     * 이 지도에 등록된 macro 전부를 **목록이 쓰는 칸만** 읽는다(ARTEL-943).
     *
     * `SELECT *` 를 안 쓰는 것이 이 메서드의 요점이다. `source` 는 한 건이 20,000자,
     * `definition_json` 은 200,000자라 둘을 그대로 올리면 macro 수십 개짜리 빌드에서 목록 하나가
     * 메가바이트가 된다. 상세가 원문을 책임지고 목록은 무엇이 등록돼 있는지만 낸다.
     *
     * 이름 오름차순이다. `id` 순으로 내면 같은 이름을 다시 등록해도 제자리 갱신이라 자리는 안
     * 바뀌지만, 등록 순서는 agent 가 무엇을 먼저 떠올렸나일 뿐 사람이 찾는 축이 아니다.
     *
     * `uk_macro_name (content_map_id, name)` 이 이 질의를 그대로 받는다 — 선두 컬럼이 좁히는
     * 칸이고 두 번째가 정렬 칸이라, `content_map_id` 단독 index 없이도 선다(`V98` 이 적어 둔
     * 그 이유다).
     */
    @Query(
        """
        SELECT $MACRO_SUMMARY_COLUMNS
        FROM macro m
        WHERE m.content_map_id = :contentMapId
        ORDER BY m.name ASC
        """
    )
    fun findSummariesByContentMapId(contentMapId: Long): Flow<MacroSummaryRow>

    /**
     * macro 하나를 원문까지 읽는다(ARTEL-943).
     *
     * **`content_map_id` 로 함께 좁히는 것이 계약이다.** id 만으로 찾으면 다른 빌드에 등록된
     * macro 가 이 빌드의 주소로 나와, 경로의 `gameBuildId` 가 장식이 된다 —
     * `ContentMapViewService.read` 가 경로의 `projectId` 를 실제로 보는 것과 같은 판단이다.
     * 없으면 null 이고 컨트롤러가 404 로 바꾼다.
     *
     * 한 건이라 `source` 를 실어도 20,000자가 상한이다. `definition_json` 은 여기서도 통째로는
     * 안 올린다 — 실행용 캐시이고 사람이 읽을 것은 원문이다.
     */
    @Query(
        """
        SELECT $MACRO_SUMMARY_COLUMNS, m.source
        FROM macro m
        WHERE m.content_map_id = :contentMapId AND m.id = :macroId
        """
    )
    suspend fun findDetailByContentMapIdAndId(contentMapId: Long, macroId: Long): MacroDetailRow?
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

    /**
     * 이 지도의 macro 에 달린 `screen` 관계 **전부**를 이름과 함께 읽는다(ARTEL-943).
     *
     * 목록이 쓰는 질의 하나다. macro 마다 [findScreenIdsByMacroId] 를 부르지 않는 것은 macro 수만큼
     * 왕복이 생기고 그 수가 빌드가 클수록 늘기 때문이다 — `ScreenRepository.findByContentMapId` 가
     * 같은 이유로 씬마다의 조회를 지도 하나짜리 조회로 바꿨다.
     *
     * 관계가 없는 macro 는 이 질의에 **행이 없다.** 부르는 쪽이 그 macro 를 빈 목록으로 채워야
     * 하고, 빈 목록의 뜻은 **아직 어디서 쓸지 모른다** 이지 아무 데서나 된다가 아니다.
     */
    @Query(
        """
        $MACRO_SCREEN_JOIN
        WHERE m.content_map_id = :contentMapId
        ORDER BY sm.macro_id ASC, sc.id ASC
        """
    )
    fun findScreenRowsByContentMapId(contentMapId: Long): Flow<MacroScreenRow>

    /**
     * macro 하나에 달린 `screen` 을 이름과 함께 읽는다(ARTEL-943).
     *
     * 상세가 쓴다. `content_map_id` 로 함께 좁히는 것은 조회 하나가 이 빌드 밖을 보지 않게 하려는
     * 것이고, macro 자체를 찾는 질의가 이미 같은 조건을 걸므로 여기서 빠지면 그 조건이 반쪽이 된다.
     *
     * PK `(screen_id, macro_id)` 의 두 번째 컬럼으로 찾으므로 `idx_screen_macro_macro` 를 탄다.
     */
    @Query(
        """
        $MACRO_SCREEN_JOIN
        WHERE m.content_map_id = :contentMapId AND sm.macro_id = :macroId
        ORDER BY sc.id ASC
        """
    )
    fun findScreenRowsByMacroId(contentMapId: Long, macroId: Long): Flow<MacroScreenRow>
}
