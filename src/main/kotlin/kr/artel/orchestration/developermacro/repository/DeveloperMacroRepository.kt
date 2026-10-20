package kr.artel.orchestration.developermacro.repository

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.developermacro.dto.DeveloperMacroDetailRow
import kr.artel.orchestration.developermacro.dto.DeveloperMacroSourceRow
import kr.artel.orchestration.developermacro.dto.DeveloperMacroSummaryRow
import kr.artel.orchestration.developermacro.dto.DeveloperMacroUpsertRow
import kr.artel.orchestration.developermacro.entity.DeveloperMacroEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 목록·상세·내부 목록이 함께 쓰는 칸과 조인. 목록과 상세가 글자까지 같은 조각을 써야 build 표시와
 * 작성자 표시가 두 응답에서 갈리지 않는다.
 *
 * `game_build` 는 INNER JOIN 이다 — FK 가 NOT NULL 이라 행이 줄지 않는다. `app_user` 는 LEFT JOIN 이다 —
 * 사용자를 지우면 `updated_by` 가 NULL 이 된다(V116).
 */
private const val DEVELOPER_MACRO_COLUMNS = """
    dm.name, dm.parameter_names, dm.docstring, dm.game_build_id,
    gb.version AS build_version, gb.label AS build_label,
    dm.version, dm.updated_at, dm.updated_by, u.nickname AS updated_by_nickname
"""

private const val DEVELOPER_MACRO_JOINS = """
    FROM developer_macro dm
    JOIN game_build gb ON gb.id = dm.game_build_id
    LEFT JOIN app_user u ON u.id = dm.updated_by
"""

/**
 * 개발자 macro 저장소(V116, ARTEL-1088). **접근 확인은 하지 않는다** — 프로젝트 멤버 검사는
 * `DeveloperMacroService` 가 `ProjectAccessService.requireMember` 로 먼저 한다.
 */
interface DeveloperMacroRepository : CoroutineCrudRepository<DeveloperMacroEntity, Long> {

    /**
     * 이름으로 넣거나 제자리에서 갱신한다.
     *
     * **`version` 은 정의가 실제로 바뀔 때만 오른다.** 같은 source 를 다시 등록하면 그대로다. 비교를 SQL 안에서
     * 하는 이유는 `MacroRepository.upsertByName` 과 같다 — 앞서 따로 조회하면 그 사이에 다른 등록이 끼어든다.
     * `game_build_id` 만 바뀐 등록은 정의가 바뀐 것이 아니라 기준 build 를 옮긴 것이라 버전을 올리지 않는다.
     *
     * `updated_by` · `updated_at` 은 정의가 같아도 바꾼다. 같은 source 라도 누가 언제 마지막으로 등록했는지는
     * 사람이 묻는 값이다. `created_by` · `created_at` 은 처음 등록한 값을 남긴다.
     *
     * **`@Modifying` 을 붙이지 않는다.** 붙이면 반환값이 `RETURNING` 행이 아니라 영향받은 행 수가 된다
     * (`MacroRepository.upsertByName` 이 적어 둔 함정).
     */
    @Query(
        """
        INSERT INTO developer_macro (
            project_id, name, source, definition_json, parameter_names, docstring,
            game_build_id, created_by, updated_by
        )
        VALUES (
            :projectId, :name, :source, :definitionJson, :parameterNames, :docstring,
            :gameBuildId, :createdBy, :updatedBy
        )
        ON CONFLICT (project_id, name) DO UPDATE SET
            source = EXCLUDED.source,
            definition_json = EXCLUDED.definition_json,
            parameter_names = EXCLUDED.parameter_names,
            docstring = EXCLUDED.docstring,
            game_build_id = EXCLUDED.game_build_id,
            version = CASE
                WHEN developer_macro.source IS DISTINCT FROM EXCLUDED.source
                  OR developer_macro.definition_json IS DISTINCT FROM EXCLUDED.definition_json
                  OR developer_macro.parameter_names IS DISTINCT FROM EXCLUDED.parameter_names
                THEN developer_macro.version + 1
                ELSE developer_macro.version
            END,
            updated_by = EXCLUDED.updated_by,
            updated_at = CURRENT_TIMESTAMP
        RETURNING id, (xmax = 0) AS inserted, version
        """
    )
    suspend fun upsertByName(
        projectId: Long,
        name: String,
        source: String,
        definitionJson: Json,
        parameterNames: Json,
        docstring: String,
        gameBuildId: Long,
        createdBy: Long,
        updatedBy: Long,
    ): DeveloperMacroUpsertRow

    /** 프로젝트의 developer macro 전부, 이름 오름차순. 원문 없이. */
    @Query(
        """
        SELECT $DEVELOPER_MACRO_COLUMNS
        $DEVELOPER_MACRO_JOINS
        WHERE dm.project_id = :projectId
        ORDER BY dm.name ASC
        """
    )
    fun findSummaries(projectId: Long): Flow<DeveloperMacroSummaryRow>

    /** 이름 하나를 원문까지. 없으면 null. */
    @Query(
        """
        SELECT $DEVELOPER_MACRO_COLUMNS, dm.source
        $DEVELOPER_MACRO_JOINS
        WHERE dm.project_id = :projectId AND dm.name = :name
        """
    )
    suspend fun findDetail(projectId: Long, name: String): DeveloperMacroDetailRow?

    /** agent-server 엔진이 읽는 전부, 원문까지. 이름 오름차순. */
    @Query(
        """
        SELECT $DEVELOPER_MACRO_COLUMNS, dm.source
        $DEVELOPER_MACRO_JOINS
        WHERE dm.project_id = :projectId
        ORDER BY dm.name ASC
        """
    )
    fun findDetails(projectId: Long): Flow<DeveloperMacroDetailRow>

    /**
     * 검사가 `imports` 로 넘길 원문들. 지금 검사하는 이름 [excludedName] 은 뺀다 — 넣으면 고치는 중인 macro 가
     * 자기 옛 정의를 가져올 수 있는 것처럼 보인다.
     */
    @Query("SELECT name, source FROM developer_macro WHERE project_id = :projectId AND name <> :excludedName")
    fun findSourcesExcept(projectId: Long, excludedName: String): Flow<DeveloperMacroSourceRow>

    /** 지운 행 수. 0 이면 그 이름이 없었다. */
    @Modifying
    @Query("DELETE FROM developer_macro WHERE project_id = :projectId AND name = :name")
    suspend fun deleteByProjectIdAndName(projectId: Long, name: String): Int
}
