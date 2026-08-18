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

    /**
     * 빌드 id 만으로 접근을 확인한다. SDK 는 등록 응답으로 받은 빌드 id 를 들고 오지만 프로젝트
     * id 는 다시 보내지 않으므로, [findAccessibleById] 와 달리 프로젝트를 인자로 받지 않는다.
     *
     * 부재와 권한 없음을 구분하지 않고 null 하나로 답한다 — 구분해서 알려주면 id 를 훑어 남의
     * 빌드가 존재한다는 사실을 알아낼 수 있다.
     */
    @Query(
        """
        SELECT gb.* FROM game_build gb
        JOIN project p ON p.id = gb.project_id
        JOIN project_member m ON m.project_id = gb.project_id
        WHERE gb.id = :id AND m.app_user_id = :userId AND p.deleted_at IS NULL
        """
    )
    suspend fun findAccessibleByIdForMember(id: Long, userId: Long): GameBuildEntity?
}
