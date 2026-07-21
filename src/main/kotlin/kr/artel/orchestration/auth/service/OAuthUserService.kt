package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.entity.OAuthIdentityEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class OAuthUserService(
    private val identityRepository: OAuthIdentityRepository,
    private val appUserRepository: AppUserRepository,
    private val clock: Clock
) {
    /**
     * 제공자 신원을 저장하고 연결된 Artel 사용자를 돌려준다.
     *
     * 이미 아는 제공자 계정이면 기존 사용자를 그대로 쓰고, 처음 보는 계정이면 새 사용자를 만든다.
     * 이메일이 같더라도 기존 사용자에 자동으로 붙이지 않는다. 제공자가 이메일 소유를 보장하지
     * 않는 경우 계정 탈취로 이어지기 때문이다. 여러 제공자를 한 사용자에 묶는 것은 로그인된
     * 상태에서의 명시적 연결로만 허용한다(별도 작업).
     */
    @Transactional
    fun upsert(identity: OAuthIdentity): AuthenticatedUser {
        val now = Instant.now(clock)
        val existing = identityRepository.findByProviderAndProviderUserId(
            identity.provider,
            identity.providerUserId
        )

        val entity = existing ?: OAuthIdentityEntity(
            appUser = appUserRepository.save(
                AppUserEntity(
                    displayName = identity.displayName,
                    email = identity.email,
                    createdAt = now,
                    updatedAt = now
                )
            ),
            provider = identity.provider,
            providerUserId = identity.providerUserId,
            createdAt = now
        )

        entity.login = identity.login
        entity.displayName = identity.displayName
        entity.avatarUrl = identity.avatarUrl
        entity.email = identity.email
        entity.updatedAt = now
        entity.lastLoginAt = now
        identityRepository.save(entity)

        val appUser = entity.appUser ?: error("oauth_identity ${entity.id} has no linked app_user")
        return AuthenticatedUser(
            userId = appUser.id.toString(),
            provider = entity.provider,
            login = entity.login,
            displayName = entity.displayName,
            avatarUrl = entity.avatarUrl
        )
    }
}
