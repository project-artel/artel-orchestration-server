package kr.artel.orchestration.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 외부 OAuth 제공자에서 정규화한 신원. 한 [AppUserEntity]에 여러 제공자를 연결할 수 있도록
 * 사용자 본체와 분리했다. (provider, providerUserId)가 제공자 계정을 유일하게 식별한다.
 */
@Entity
@Table(
    name = "oauth_identity",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_oauth_identity_provider_identity",
            columnNames = ["provider", "provider_user_id"]
        )
    ]
)
open class OAuthIdentityEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    open var appUser: AppUserEntity? = null,

    @Column(nullable = false, length = 64)
    open var provider: String = "",

    @Column(name = "provider_user_id", nullable = false, length = 255)
    open var providerUserId: String = "",

    @Column(nullable = false, length = 255)
    open var login: String = "",

    @Column(name = "display_name", nullable = false, length = 255)
    open var displayName: String = "",

    @Column(name = "avatar_url", length = 2048)
    open var avatarUrl: String? = null,

    @Column(length = 320)
    open var email: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now(),

    @Column(name = "last_login_at", nullable = false)
    open var lastLoginAt: Instant = Instant.now()
)
