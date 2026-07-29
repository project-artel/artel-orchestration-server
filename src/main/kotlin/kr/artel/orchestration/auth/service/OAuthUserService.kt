package kr.artel.orchestration.auth.service

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.entity.OAuthIdentityEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

@Service
class OAuthUserService(
    private val identityRepository: OAuthIdentityRepository,
    private val appUserRepository: AppUserRepository,
    private val transactionalOperator: TransactionalOperator,
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
    suspend fun upsert(identity: OAuthIdentity): AuthenticatedUser {
        val now = Instant.now(clock)

        return transactionalOperator.executeAndAwait {
            val existing = identityRepository
                .findByProviderAndProviderUserId(identity.provider, identity.providerUserId)
            val toSave = existing?.refreshedWith(identity, now) ?: newIdentityFor(identity, now)
            val saved = identityRepository.save(toSave)
            AuthenticatedUser(
                userId = saved.appUserId.toString(),
                provider = saved.provider,
                login = saved.login,
                displayName = saved.displayName,
                avatarUrl = saved.avatarUrl
            )
        }!!
    }

    /** 처음 보는 제공자 계정이므로 사용자 본체부터 만든다. */
    private suspend fun newIdentityFor(identity: OAuthIdentity, now: Instant): OAuthIdentityEntity {
        val appUser = appUserRepository.save(
            AppUserEntity(
                displayName = identity.displayName,
                email = identity.email,
                createdAt = now,
                updatedAt = now
            )
        )
        return OAuthIdentityEntity(
            appUserId = requireNotNull(appUser.id) { "app_user id was not generated" },
            provider = identity.provider,
            providerUserId = identity.providerUserId,
            login = identity.login,
            displayName = identity.displayName,
            avatarUrl = identity.avatarUrl,
            email = identity.email,
            createdAt = now,
            updatedAt = now,
            lastLoginAt = now
        )
    }

    /** 로그인할 때마다 제공자 쪽 프로필 변경을 반영한다. */
    private fun OAuthIdentityEntity.refreshedWith(identity: OAuthIdentity, now: Instant) = copy(
        login = identity.login,
        displayName = identity.displayName,
        avatarUrl = identity.avatarUrl,
        email = identity.email,
        updatedAt = now,
        lastLoginAt = now
    )

    /**
     * 사용자 프로필을 읽는다. 세션이 가리키는 사용자가 더 이상 없으면 null이다.
     *
     * 토큰이 아니라 DB에서 읽으므로 프로필 변경이 즉시 반영되고, 삭제된 사용자의 토큰이
     * 만료 전까지 통하는 문제도 생기지 않는다.
     */
    suspend fun findProfile(userId: Long): UserProfile? {
        val appUser = appUserRepository.findById(userId) ?: return null
        val identities = identityRepository.findByAppUserIdOrderByLastLoginAtDesc(userId)
            .map {
                LinkedIdentity(
                    provider = it.provider,
                    login = it.login,
                    displayName = it.displayName,
                    avatarUrl = it.avatarUrl
                )
            }
            .toList()
        return UserProfile(
            userId = appUser.id.toString(),
            displayName = appUser.displayName,
            email = appUser.email,
            locale = appUser.locale,
            identities = identities
        )
    }

    /**
     * 표시 언어 설정을 바꾼다. 세션이 가리키는 사용자가 더 이상 없으면 null이다.
     * 허용 값 검증은 API 경계에서 끝난 뒤이므로 여기서는 저장만 한다.
     */
    suspend fun updateLocale(userId: Long, locale: String): AppUserEntity? {
        val appUser = appUserRepository.findById(userId) ?: return null
        return appUserRepository.save(appUser.copy(locale = locale, updatedAt = Instant.now(clock)))
    }
}
