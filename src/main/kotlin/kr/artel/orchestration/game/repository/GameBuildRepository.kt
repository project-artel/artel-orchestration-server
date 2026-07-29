package kr.artel.orchestration.game.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.game.entity.GameBuildEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 빌드에는 soft delete가 없다. 지워도 SDK가 같은 버전을 다시 보고하는 순간 되살아나므로,
 * 지울 수 있다는 인상만 주고 실제로는 지워지지 않는 동작이 된다.
 *
 * 조회는 프로젝트와 마찬가지로 참여자 조인과 프로젝트의 `deleted_at IS NULL`로 좁힌다.
 */
interface GameBuildRepository : CoroutineCrudRepository<GameBuildEntity, Long> {

    @Query(
        """
        SELECT gb.* FROM game_build gb
        JOIN project p ON p.id = gb.project_id
        JOIN project_member m ON m.project_id = gb.project_id
        WHERE gb.project_id = :projectId AND m.app_user_id = :userId
          AND p.deleted_at IS NULL
        ORDER BY gb.created_at DESC, gb.id DESC
        """
    )
    fun findAllForMember(projectId: Long, userId: Long): Flow<GameBuildEntity>

    @Query(
        """
        SELECT gb.* FROM game_build gb
        JOIN project p ON p.id = gb.project_id
        JOIN project_member m ON m.project_id = gb.project_id
        WHERE gb.id = :id AND gb.project_id = :projectId AND m.app_user_id = :userId
          AND p.deleted_at IS NULL
        """
    )
    suspend fun findAccessibleById(id: Long, projectId: Long, userId: Long): GameBuildEntity?

    suspend fun findByProjectIdAndVersion(projectId: Long, version: String): GameBuildEntity?
}
