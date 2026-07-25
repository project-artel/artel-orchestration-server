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

    @Column("created_at")
    val createdAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant
)
