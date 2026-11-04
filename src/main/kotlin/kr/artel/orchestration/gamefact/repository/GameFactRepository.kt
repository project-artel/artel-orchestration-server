package kr.artel.orchestration.gamefact.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.gamefact.dto.GameFactRow
import kr.artel.orchestration.gamefact.entity.GameFactEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

private const val GAME_FACT_COLUMNS = "id, name, scope, member, type, unit, means, game_build_id, version, updated_at"

/**
 * fact 저장소(V177). **접근 확인은 하지 않는다** — 프로젝트 멤버 검사는 `GameFactService` 가 먼저 한다.
 */
interface GameFactRepository : CoroutineCrudRepository<GameFactEntity, Long> {

    /**
     * 이름으로 넣거나 제자리에서 갱신하고 갱신 뒤의 행을 돌려준다. 선언한 칸(`scope`·`member`·`type`·`unit`·`means`)이
     * 하나라도 바뀌면 `version` 이 오른다. 비교를 SQL 안에서 하는 이유와 `@Modifying` 을 붙이지 않는 이유는
     * `GameIdentityRepository.upsertByName` 과 같다.
     */
    @Query(
        """
        INSERT INTO game_fact (project_id, name, scope, member, type, unit, means, game_build_id, created_by, updated_by)
        VALUES (:projectId, :name, :scope, :member, :type, :unit, :means, :gameBuildId, :createdBy, :updatedBy)
        ON CONFLICT (project_id, name) DO UPDATE SET
            scope = EXCLUDED.scope,
            member = EXCLUDED.member,
            type = EXCLUDED.type,
            unit = EXCLUDED.unit,
            means = EXCLUDED.means,
            game_build_id = EXCLUDED.game_build_id,
            version = CASE
                WHEN (game_fact.scope, game_fact.member, game_fact.type, game_fact.unit, game_fact.means)
                     IS DISTINCT FROM
                     (EXCLUDED.scope, EXCLUDED.member, EXCLUDED.type, EXCLUDED.unit, EXCLUDED.means)
                THEN game_fact.version + 1
                ELSE game_fact.version
            END,
            updated_by = EXCLUDED.updated_by,
            updated_at = CURRENT_TIMESTAMP
        RETURNING $GAME_FACT_COLUMNS
        """
    )
    suspend fun upsertByName(
        projectId: Long,
        name: String,
        scope: String,
        member: String,
        type: String,
        unit: String?,
        means: String,
        gameBuildId: Long,
        createdBy: Long,
        updatedBy: Long,
    ): GameFactRow

    /** 프로젝트의 fact 전부, 이름 오름차순. */
    @Query("SELECT $GAME_FACT_COLUMNS FROM game_fact WHERE project_id = :projectId ORDER BY name ASC")
    fun findRows(projectId: Long): Flow<GameFactRow>

    /** 주어진 이름 가운데 프로젝트에 있는 것. rule 의 참조 검사가 쓴다. */
    @Query("SELECT name FROM game_fact WHERE project_id = :projectId AND name = ANY(:names)")
    fun findExistingNames(projectId: Long, names: Array<String>): Flow<String>

    /** 지운 행 수. 0 이면 그 이름이 없었다. */
    @Modifying
    @Query("DELETE FROM game_fact WHERE project_id = :projectId AND name = :name")
    suspend fun deleteByProjectIdAndName(projectId: Long, name: String): Int
}
