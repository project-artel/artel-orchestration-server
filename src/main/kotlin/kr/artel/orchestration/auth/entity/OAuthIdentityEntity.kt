package kr.artel.orchestration.auth.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 외부 OAuth 제공자에서 정규화한 신원. 한 [AppUserEntity]에 여러 제공자를 연결할 수 있도록
 * 사용자 본체와 분리했다. (provider, providerUserId)가 제공자 계정을 유일하게 식별한다.
 *
 * R2DBC는 연관관계 매핑을 지원하지 않으므로 사용자 참조는 외래키 값([appUserId])으로 직접 갖는다.
 */
@Table("oauth_identity")
data class OAuthIdentityEntity(
    @Id
    val id: Long? = null,

    @Column("app_user_id")
    val appUserId: Long,

    @Column("provider")
    val provider: String,

    @Column("provider_user_id")
    val providerUserId: String,

    @Column("login")
    val login: String,

    @Column("display_name")
    val displayName: String,

    @Column("avatar_url")
    val avatarUrl: String? = null,

    @Column("email")
    val email: String? = null,

    @Column("created_at")
    val createdAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant,

    @Column("last_login_at")
    val lastLoginAt: Instant
)
