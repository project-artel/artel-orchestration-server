package kr.artel.orchestration.project.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 사용자와 프로젝트의 M:N 참여 관계를 잇는 행.
 *
 * R2DBC는 연관관계 매핑을 지원하지 않으므로 양쪽 참조를 외래키 값으로 직접 갖는다.
 * 이 테이블에 행이 있다는 것이 곧 접근 권한이며, 없으면 프로젝트는 존재하지 않는 것처럼 보인다.
 */
@Table("project_member")
data class ProjectMemberEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("app_user_id")
    val appUserId: Long,

    @Column("role")
    val role: String,

    @Column("created_at")
    val createdAt: Instant
)

/**
 * 프로젝트 안에서의 역할.
 *
 * 프로젝트를 만든 사람이 첫 [OWNER]가 된다. 삭제처럼 되돌릴 수 없는 동작은 OWNER만 할 수 있고,
 * [MEMBER]는 조회와 수정, 기획서 업로드까지 할 수 있다.
 */
enum class ProjectRole {
    OWNER,
    MEMBER
}
