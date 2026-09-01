package kr.artel.orchestration.project.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.project.entity.ProjectInvitationEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface ProjectInvitationRepository : CoroutineCrudRepository<ProjectInvitationEntity, Long> {

    /** 보낸 초대 목록. 아직 답을 기다리는 것만 낸다. */
    @Query(
        """
        SELECT * FROM project_invitation
        WHERE project_id = :projectId AND status = 'PENDING' AND expires_at > :now
        ORDER BY created_at DESC, id DESC
        """
    )
    fun findPendingByProjectId(projectId: Long, now: Instant): Flow<ProjectInvitationEntity>

    /**
     * 받은 초대함. 삭제된 프로젝트의 초대는 내보내지 않는다 — 수락해도 404 가 될 초대를 보여 주면
     * 받는 사람이 왜 안 되는지 알 길이 없다.
     */
    @Query(
        """
        SELECT i.* FROM project_invitation i
        JOIN project p ON p.id = i.project_id
        WHERE lower(i.email) = lower(:email)
          AND i.status = 'PENDING'
          AND i.expires_at > :now
          AND p.deleted_at IS NULL
        ORDER BY i.created_at DESC, i.id DESC
        """
    )
    fun findPendingForEmail(email: String, now: Instant): Flow<ProjectInvitationEntity>

    /**
     * `PENDING` 을 벗어나는 유일한 경로. `@Modifying` 이 있어야 영향 행 수가 돌아온다
     * (`IssueRepository.resolve` 와 같은 모양).
     *
     * 읽고 나서 쓰면 그 사이에 다른 요청이 같은 초대를 처리한다. `WHERE status = 'PENDING'` 이
     * 직렬화 지점이라, 동시에 들어온 수락 중 하나만 1 을 받고 나머지는 0 을 받는다. 서비스는 0 을
     * 409 로 옮긴다 — 재요청을 멱등 성공으로 볼 수 없다. 이미 거절된 초대를 수락으로 뒤집거나 그
     * 반대를 하는 일이 조용히 성공하면 안 된다.
     */
    @Modifying
    @Query(
        """
        UPDATE project_invitation
        SET status = :status, responded_at = :respondedAt
        WHERE id = :id AND status = 'PENDING'
        """
    )
    suspend fun settle(id: Long, status: String, respondedAt: Instant): Int
}
