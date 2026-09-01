package kr.artel.orchestration.auth.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * Artel 사용자 본체. 연결된 OAuth 제공자와 무관하게 안정적으로 유지되며,
 * 이 id가 JWT의 sub 클레임이 된다.
 */
@Table("app_user")
data class AppUserEntity(
    @Id
    val id: Long? = null,

    @Column("display_name")
    val displayName: String,

    @Column("email")
    val email: String? = null,

    /** 홈 UI 표시 언어. null이면 아직 고르지 않았고 클라이언트 기본 로직을 따른다. */
    @Column("locale")
    val locale: String? = null,

    /** 프로젝트 밖의 등급. 값은 [PlatformRole]이고, 읽지 못하는 값은 [USER]로 다룬다. */
    @Column("platform_role")
    val platformRole: String = PlatformRole.USER.name,

    @Column("created_at")
    val createdAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant
)

/**
 * 프로젝트 밖의 등급.
 *
 * [kr.artel.orchestration.project.entity.ProjectRole]과 층이 다르다. 저쪽은 한 프로젝트 안에서
 * 무엇을 할 수 있는지를 정하고, 이쪽은 어느 프로젝트를 볼 수 있는지를 정한다. 그래서 프로젝트
 * 역할로는 "모든 프로젝트를 본다"를 쓸 수 없고, 이 등급이 그 자리다.
 *
 * [DEVELOPER]가 여는 것은 조회뿐이다. 쓰기는 이 등급과 무관하게 `project_member`를 그대로
 * 요구한다 — 근거는 [kr.artel.orchestration.auth.service.PlatformAccessService]에 있다.
 */
enum class PlatformRole {
    USER,
    DEVELOPER
}
