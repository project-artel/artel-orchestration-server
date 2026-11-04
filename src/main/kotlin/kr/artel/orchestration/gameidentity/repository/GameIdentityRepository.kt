package kr.artel.orchestration.gameidentity.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.gameidentity.dto.GameIdentityRow
import kr.artel.orchestration.gameidentity.entity.GameIdentityEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

private const val REVISION_COLUMNS = "r.*, r.created_at AS updated_at"

interface GameIdentityRepository : CoroutineCrudRepository<GameIdentityEntity, Long> {
    // transaction 안에서 프로젝트 쓰기를 직렬화해 version과 binding을 함께 보호한다.
    @Query("SELECT id FROM project WHERE id = :projectId AND deleted_at IS NULL FOR UPDATE")
    suspend fun lockProject(projectId: Long): Long?

    @Query("SELECT $REVISION_COLUMNS FROM game_identity_revision r WHERE project_id = :projectId AND name = :name ORDER BY version DESC")
    fun history(projectId: Long, name: String): Flow<GameIdentityRow>

    @Query("""
        SELECT $REVISION_COLUMNS FROM game_identity_revision r
        JOIN game_identity_build_binding b ON b.revision_id = r.id
        WHERE b.project_id = :projectId AND b.game_build_id = :gameBuildId
          AND (:includeDeleted = TRUE OR r.deleted = FALSE) ORDER BY r.name
    """)
    fun findBuildRows(projectId: Long, gameBuildId: Long, includeDeleted: Boolean): Flow<GameIdentityRow>

    @Query("""
        SELECT * FROM (
            SELECT DISTINCT ON (r.name) $REVISION_COLUMNS FROM game_identity_revision r
            WHERE r.project_id = :projectId ORDER BY r.name, r.version DESC
        ) latest WHERE (:includeDeleted = TRUE OR deleted = FALSE) ORDER BY name
    """)
    fun findRows(projectId: Long, includeDeleted: Boolean = false): Flow<GameIdentityRow>

    @Query("""
        INSERT INTO game_identity_revision
            (project_id, name, kind, base, meaning, changes_when, identifies, role, game_build_id, version, source, deleted, created_by)
        VALUES (:projectId, :name, :kind, :base, :meaning, :changesWhen, :identifies, :role, :gameBuildId,
            (SELECT COALESCE(MAX(version), 0) + 1 FROM game_identity_revision WHERE project_id = :projectId AND name = :name),
            :source, :deleted, :createdBy)
        RETURNING *, created_at AS updated_at
    """)
    suspend fun insertRevision(projectId: Long, name: String, kind: String, base: Int?, meaning: String,
        changesWhen: String, identifies: String, role: String, gameBuildId: Long,
        source: String, deleted: Boolean, createdBy: Long?): GameIdentityRow

    @Modifying
    @Query("""
        INSERT INTO game_identity_build_binding(project_id, game_build_id, name, revision_id)
        VALUES (:projectId, :gameBuildId, :name, :revisionId)
        ON CONFLICT(project_id, game_build_id, name) DO UPDATE SET revision_id = EXCLUDED.revision_id
    """)
    suspend fun bind(projectId: Long, gameBuildId: Long, name: String, revisionId: Long): Int
}
