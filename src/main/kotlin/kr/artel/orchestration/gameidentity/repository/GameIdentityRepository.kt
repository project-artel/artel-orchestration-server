package kr.artel.orchestration.gameidentity.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.gameidentity.dto.GameIdentityRow
import kr.artel.orchestration.gameidentity.entity.GameIdentityEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** 목록·upsert 가 함께 돌려주는 칸. 두 응답이 같은 칸을 같은 이름으로 읽게 한다. */
private const val GAME_IDENTITY_COLUMNS = """
    id, name, kind, base, meaning, changes_when, identifies, role, game_build_id, version, updated_at
"""

/**
 * 게임 값 선언 저장소(V173). **접근 확인은 하지 않는다** — 프로젝트 멤버 검사는 `GameIdentityService` 가
 * `ProjectAccessService.requireMember` 로 먼저 한다.
 */
interface GameIdentityRepository : CoroutineCrudRepository<GameIdentityEntity, Long> {

    /**
     * 이름으로 넣거나 제자리에서 갱신하고 갱신 뒤의 행을 돌려준다.
     *
     * **선언한 칸(`kind`·`base`·`meaning`·`changes_when`·`identifies`·`role`)이 하나라도 바뀌면 `version` 이 오른다.**
     * 같은 선언을 다시 등록하면 그대로다. 비교를 SQL 안에서 하는 이유는 `DeveloperMacroRepository.upsertByName` 과
     * 같다 — 앞서 따로 조회하면 그 사이에 다른 등록이 끼어든다. `game_build_id` 만 바뀐 등록은 기준 build 를 옮긴
     * 것이라 `version` 을 건드리지 않는다.
     *
     * `updated_by` · `updated_at` 은 선언이 같아도 바꾼다. `created_by` · `created_at` 은 처음 등록한 값을 남긴다.
     *
     * **`@Modifying` 을 붙이지 않는다.** 붙이면 반환값이 `RETURNING` 행이 아니라 영향받은 행 수가 된다.
     */
    @Query(
        """
        INSERT INTO game_identity (
            project_id, name, kind, base, meaning, changes_when, identifies, role, game_build_id, created_by, updated_by
        )
        VALUES (
            :projectId, :name, :kind, :base, :meaning, :changesWhen, :identifies, :role, :gameBuildId,
            :createdBy, :updatedBy
        )
        ON CONFLICT (project_id, name) DO UPDATE SET
            kind = EXCLUDED.kind,
            base = EXCLUDED.base,
            meaning = EXCLUDED.meaning,
            changes_when = EXCLUDED.changes_when,
            identifies = EXCLUDED.identifies,
            role = EXCLUDED.role,
            game_build_id = EXCLUDED.game_build_id,
            version = CASE
                WHEN (game_identity.kind, game_identity.base, game_identity.meaning,
                      game_identity.changes_when, game_identity.identifies, game_identity.role)
                     IS DISTINCT FROM
                     (EXCLUDED.kind, EXCLUDED.base, EXCLUDED.meaning,
                      EXCLUDED.changes_when, EXCLUDED.identifies, EXCLUDED.role)
                THEN game_identity.version + 1
                ELSE game_identity.version
            END,
            updated_by = EXCLUDED.updated_by,
            updated_at = CURRENT_TIMESTAMP
        RETURNING $GAME_IDENTITY_COLUMNS
        """
    )
    suspend fun upsertByName(
        projectId: Long,
        name: String,
        kind: String,
        base: Int?,
        meaning: String,
        changesWhen: String,
        identifies: String,
        role: String,
        gameBuildId: Long,
        createdBy: Long,
        updatedBy: Long,
    ): GameIdentityRow

    /** 프로젝트의 선언 전부, 이름 오름차순. */
    @Query(
        """
        SELECT $GAME_IDENTITY_COLUMNS
        FROM game_identity
        WHERE project_id = :projectId
        ORDER BY name ASC
        """
    )
    fun findRows(projectId: Long): Flow<GameIdentityRow>

    /** 지운 행 수. 0 이면 그 이름이 없었다. */
    @Modifying
    @Query("DELETE FROM game_identity WHERE project_id = :projectId AND name = :name")
    suspend fun deleteByProjectIdAndName(projectId: Long, name: String): Int
}
