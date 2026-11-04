package kr.artel.orchestration.gamefact.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.gamefact.dto.GameRuleRow
import kr.artel.orchestration.gamefact.entity.GameRuleEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

private const val GAME_RULE_COLUMNS = "id, name, says, facts, game_build_id, version, updated_at"

/**
 * rule 저장소(V177). **접근 확인은 하지 않는다** — 프로젝트 멤버 검사와 fact 참조 검사는 `GameFactService` 가 먼저 한다.
 */
interface GameRuleRepository : CoroutineCrudRepository<GameRuleEntity, Long> {

    /** 이름으로 넣거나 갱신한다. `says`·`facts` 가 바뀌면 `version` 이 오른다(`GameFactRepository.upsertByName` 과 같은 규칙). */
    @Query(
        """
        INSERT INTO game_rule (project_id, name, says, facts, game_build_id, created_by, updated_by)
        VALUES (:projectId, :name, :says, :facts, :gameBuildId, :createdBy, :updatedBy)
        ON CONFLICT (project_id, name) DO UPDATE SET
            says = EXCLUDED.says,
            facts = EXCLUDED.facts,
            game_build_id = EXCLUDED.game_build_id,
            version = CASE
                WHEN (game_rule.says, game_rule.facts) IS DISTINCT FROM (EXCLUDED.says, EXCLUDED.facts)
                THEN game_rule.version + 1
                ELSE game_rule.version
            END,
            updated_by = EXCLUDED.updated_by,
            updated_at = CURRENT_TIMESTAMP
        RETURNING $GAME_RULE_COLUMNS
        """
    )
    suspend fun upsertByName(
        projectId: Long,
        name: String,
        says: String,
        facts: Array<String>,
        gameBuildId: Long,
        createdBy: Long,
        updatedBy: Long,
    ): GameRuleRow

    /** 프로젝트의 rule 전부, 이름 오름차순. */
    @Query("SELECT $GAME_RULE_COLUMNS FROM game_rule WHERE project_id = :projectId ORDER BY name ASC")
    fun findRows(projectId: Long): Flow<GameRuleRow>

    /** 이 fact 를 말하는 rule 의 이름. fact 삭제 검사가 쓴다. */
    @Query("SELECT name FROM game_rule WHERE project_id = :projectId AND :fact = ANY(facts) ORDER BY name ASC")
    fun findNamesUsingFact(projectId: Long, fact: String): Flow<String>

    @Modifying
    @Query("DELETE FROM game_rule WHERE project_id = :projectId AND name = :name")
    suspend fun deleteByProjectIdAndName(projectId: Long, name: String): Int
}
