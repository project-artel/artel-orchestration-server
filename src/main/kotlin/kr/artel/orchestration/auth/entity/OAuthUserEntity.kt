package kr.artel.orchestration.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "oauth_user",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_oauth_user_provider_identity",
            columnNames = ["provider", "provider_user_id"]
        )
    ]
)
open class OAuthUserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

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
