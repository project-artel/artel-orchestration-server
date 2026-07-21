package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.entity.OAuthUserEntity
import kr.artel.orchestration.auth.repository.OAuthUserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class OAuthUserService(
    private val repository: OAuthUserRepository,
    private val clock: Clock
) {
    @Transactional
    fun upsert(identity: OAuthIdentity): OAuthIdentity {
        val now = Instant.now(clock)
        val user = repository.findByProviderAndProviderUserId(
            identity.provider,
            identity.providerUserId
        ) ?: OAuthUserEntity(
            provider = identity.provider,
            providerUserId = identity.providerUserId,
            createdAt = now
        )

        user.login = identity.login
        user.displayName = identity.displayName
        user.avatarUrl = identity.avatarUrl
        user.email = identity.email
        user.updatedAt = now
        user.lastLoginAt = now
        repository.save(user)

        return identity
    }
}
