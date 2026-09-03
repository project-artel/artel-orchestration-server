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
     * 받은 초대함의 계정 쪽 절반. [findPendingForEmail] 과 같은 조건 — 삭제된 프로젝트 제외,
     * `PENDING`, 만료 전 — 을 `app_user_id` 로 맞춘다. `ProjectInvitationService.listForUser` 가
     * 둘을 합쳐 낸다.
     */
    @Query(
        """
        SELECT i.* FROM project_invitation i
        JOIN project p ON p.id = i.project_id
        WHERE i.app_user_id = :appUserId
          AND i.status = 'PENDING'
          AND i.expires_at > :now
          AND p.deleted_at IS NULL
        ORDER BY i.created_at DESC, i.id DESC
        """
    )
    fun findPendingForAppUserId(appUserId: Long, now: Instant): Flow<ProjectInvitationEntity>

    /**
     * 이 프로젝트에서 이 주소로 나갔다가 만료된 채 `PENDING` 으로 남은 초대를 거둔다.
     *
     * `uk_project_invitation_pending` 은 `status = 'PENDING'` 만 보고 만료는 보지 않으므로, 답을
     * 받지 못한 채 기간이 지난 행이 같은 사람을 다시 부르는 길을 막는다. 그 행은 목록에도 나오지
     * 않아([findPendingByProjectId] 가 `expires_at > :now` 로 거른다) revoke 로 치울 id 를 얻을
     * 수도 없다. `ProjectInvitationService.create` 가 다시 부르기 직전에 이것으로 치운다.
     *
     * 여러 행이 걸릴 일은 없지만 — 그 unique index 가 하나로 묶는다 — 행 수를 세지 않는 것은
     * 부르는 쪽이 그 숫자로 할 일이 없어서다. 치울 것이 없으면 0 이고 그것도 정상이다.
     */
    @Modifying
    @Query(
        """
        UPDATE project_invitation
        SET status = :status, responded_at = :respondedAt
        WHERE project_id = :projectId
          AND status = 'PENDING'
          AND expires_at <= :now
          AND lower(email) = lower(:email)
        """
    )
    suspend fun settleExpiredForEmail(
        projectId: Long,
        email: String,
        status: String,
        respondedAt: Instant,
        now: Instant
    ): Int

    /** [settleExpiredForEmail] 의 계정 쪽 짝. 같은 이유로 같은 일을 한다. */
    @Modifying
    @Query(
        """
        UPDATE project_invitation
        SET status = :status, responded_at = :respondedAt
        WHERE project_id = :projectId
          AND status = 'PENDING'
          AND expires_at <= :now
          AND app_user_id = :appUserId
        """
    )
    suspend fun settleExpiredForAppUser(
        projectId: Long,
        appUserId: Long,
        status: String,
        respondedAt: Instant,
        now: Instant
    ): Int

    /**
     * 사람의 답으로 `PENDING` 을 벗어나는 경로. `@Modifying` 이 있어야 영향 행 수가 돌아온다
     * (`IssueRepository.resolve` 와 같은 모양).
     *
     * 위 두 `settleExpired...` 도 `PENDING` 을 벗어나게 하지만 그쪽은 이미 만료된 행만 건드린다.
     * 아직 살아 있는 초대의 상태를 바꾸는 것은 여기뿐이다.
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
