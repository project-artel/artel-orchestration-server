package kr.artel.orchestration.project.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 프로젝트에 사람을 부른 기록.
 *
 * 대상을 가리키는 방법이 둘이고, [email] 과 [appUserId] 중 정확히 하나만 차 있다.
 * `ck_project_invitation_target` 이 그것을 강제한다.
 *
 * [email] 은 아직 가입하지 않은 사람을 부르는 자리다. 그 사람이 나중에 같은 주소로 로그인하면
 * 기다리던 초대가 받은 초대함에 보인다. [appUserId] 는 이미 계정이 있는 사람을 부르는 자리이고,
 * 그 사람에게 확인을 마친 주소가 없어도 초대가 간다 — 배달은 웹 초대함이 한다.
 *
 * 수락하면 [ProjectMemberEntity] 행이 생긴다. 그 전까지 이 행은 접근 권한이 아니다 — 권한의 답은
 * 여전히 `project_member` 한 곳에서만 나온다.
 */
@Table("project_invitation")
data class ProjectInvitationEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    /**
     * 아직 계정이 없는 사람을 부를 때의 주소. 소문자로 정규화해 저장하고 비교도 대소문자를
     * 무시한다. [appUserId] 로 부른 초대에서는 null 이다.
     */
    @Column("email")
    val email: String? = null,

    /**
     * 이미 계정이 있는 사람을 부를 때의 그 계정. [email] 로 부른 초대에서는 null 이다.
     *
     * 그 계정이 지워지면 이 행도 함께 지워진다. 가리키는 사람이 없는 초대는 남길 뜻이 없다.
     */
    @Column("app_user_id")
    val appUserId: Long? = null,

    /** 수락했을 때 갖게 될 [ProjectRole]. */
    @Column("role")
    val role: String,

    @Column("status")
    val status: String,

    /** 초대한 사람. 그 사람이 지워지면 null 이 된다. */
    @Column("invited_by")
    val invitedBy: Long? = null,

    @Column("created_at")
    val createdAt: Instant,

    @Column("expires_at")
    val expiresAt: Instant,

    /** [ProjectInvitationStatus.PENDING] 을 벗어난 시각. 감사 기록이고 판정에는 쓰지 않는다. */
    @Column("responded_at")
    val respondedAt: Instant? = null
)

/**
 * 초대가 놓인 자리.
 *
 * 만료가 값으로 없는 것은 빠뜨린 것이 아니다. `expires_at` 과 현재 시각을 비교해 조회할 때 정한다.
 * `EXPIRED` 를 저장하면 때가 된 행을 뒤집어 줄 배치가 필요해지고, 그 배치가 멈춘 동안 status 가
 * 거짓을 말한다.
 *
 * [DECLINED] 와 [REVOKED] 를 가르는 것은 누가 끝냈느냐다 — 받은 사람이 거절하면 [DECLINED],
 * 보낸 쪽이 거두면 [REVOKED]. 둘 다 다시 초대할 수 있는 상태라 동작은 같지만, 왜 끝났는지를
 * 나중에 물을 수 있어야 한다.
 */
enum class ProjectInvitationStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    REVOKED
}
