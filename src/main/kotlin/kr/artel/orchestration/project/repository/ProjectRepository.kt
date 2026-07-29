package kr.artel.orchestration.project.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.project.entity.ProjectEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 모든 조회는 project_member 조인과 `deleted_at IS NULL`로 함께 좁힌다. 조건을 서비스에서
 * 걸러내지 않고 질의에 두어야, 조건을 빠뜨렸을 때 남의 행이나 삭제된 행이 조용히 새어나가지 않는다.
 */
interface ProjectRepository : CoroutineCrudRepository<ProjectEntity, Long> {

    @Query(
        """
        SELECT p.* FROM project p
        JOIN project_member m ON m.project_id = p.id
        WHERE m.app_user_id = :userId AND p.deleted_at IS NULL
        ORDER BY p.updated_at DESC, p.id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun findPageForMember(userId: Long, limit: Int, offset: Long): Flow<ProjectEntity>

    @Query(
        """
        SELECT COUNT(*) FROM project p
        JOIN project_member m ON m.project_id = p.id
        WHERE m.app_user_id = :userId AND p.deleted_at IS NULL
        """
    )
    suspend fun countForMember(userId: Long): Long

    @Query(
        """
        SELECT p.* FROM project p
        JOIN project_member m ON m.project_id = p.id
        WHERE p.id = :id AND m.app_user_id = :userId AND p.deleted_at IS NULL
        """
    )
    suspend fun findAccessibleById(id: Long, userId: Long): ProjectEntity?
}
