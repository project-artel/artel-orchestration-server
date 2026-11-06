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

    /**
     * 멤버십을 따지지 않는 목록. `DEVELOPER` 등급의 `scope=all` 조회만 부른다.
     *
     * [findPageForMember]에 매개변수를 더하지 않고 메서드를 가른 것은, 넓힌 목록이 조인이 아예 없는
     * 다른 질의이기 때문이다. 한 문장에 담으면 조인을 남긴 채 조건만 꺼야 해서, 읽는 사람이 언제
     * 멤버십이 서는지를 SQL이 아니라 호출부에서 찾아야 한다.
     *
     * 등급 판정은 여기서 하지 않는다 — 이 메서드를 부르는 것 자체가 이미 판정을 통과했다는 뜻이고,
     * 그 판정은 `ProjectService`가 `PlatformAccessService`로 한다.
     */
    @Query(
        """
        SELECT p.* FROM project p
        WHERE p.deleted_at IS NULL
        ORDER BY p.updated_at DESC, p.id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun findActivePage(limit: Int, offset: Long): Flow<ProjectEntity>

    /**
     * [findActivePage]의 전체 개수.
     *
     * 이름에 `Active`가 붙는 이유는 [CoroutineCrudRepository.count]가 이미 있기 때문이다. 그쪽은
     * 삭제된 행까지 세므로, 이 질의가 `count`라는 이름을 가져가면 두 개수가 조용히 뒤바뀐다.
     */
    @Query("SELECT COUNT(*) FROM project WHERE deleted_at IS NULL")
    suspend fun countActive(): Long

    /**
     * 멤버십을 따지지 않는 존재 확인. **서버-투-서버 경로 전용**이다(Agent는 엔드유저가 아니라
     * 참여자로 판정할 대상이 없다). 삭제된 프로젝트는 여전히 없는 것으로 본다 — 그러지 않으면
     * 지운 프로젝트에 데이터가 다시 쌓인다.
     */
    @Query("SELECT * FROM project WHERE id = :id AND deleted_at IS NULL")
    suspend fun findActiveById(id: Long): ProjectEntity?
}
