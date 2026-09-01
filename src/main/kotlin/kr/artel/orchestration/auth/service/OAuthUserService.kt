package kr.artel.orchestration.auth.service

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.entity.OAuthIdentityEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import org.springframework.dao.DataIntegrityViolationException
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

        return try {
            link(identity, now, claimEmail = true)
        } catch (conflict: DataIntegrityViolationException) {
            // `uk_app_user_verified_email` 이다. 같은 주소를 확인된 것으로 가진 계정이 이미 있고,
            // 그 사이에 들어와 아래 조회로는 못 걸렀다. 트랜잭션이 롤백됐으므로 주소를 주장하지
            // 않고 한 번 더 시도한다 — 가입이 실패할 이유는 아니다.
            link(identity, now, claimEmail = false)
        }
    }

    private suspend fun link(
        identity: OAuthIdentity,
        now: Instant,
        claimEmail: Boolean
    ): AuthenticatedUser =
        transactionalOperator.executeAndAwait {
            val existing = identityRepository
                .findByProviderAndProviderUserId(identity.provider, identity.providerUserId)
            val toSave = existing?.refreshedWith(identity, now)
                ?: newIdentityFor(identity, now, claimEmail)
            val saved = identityRepository.save(toSave)
            AuthenticatedUser(
                userId = saved.appUserId.toString(),
                provider = saved.provider,
                login = saved.login,
                displayName = saved.displayName,
                avatarUrl = saved.avatarUrl
            )
        }!!

    /**
     * 처음 보는 제공자 계정이므로 사용자 본체부터 만든다.
     *
     * 제공자가 준 주소는 확인된 것으로 본다. GitHub은 자기가 확인을 마친 주소만 공개 이메일로 고를
     * 수 있게 하므로 사용자가 적어 넣은 값이 아니다. 사용자가 직접 적은 주소는
     * `EmailVerificationService`의 토큰을 지나야만 이 자리에 온다.
     *
     * 다만 그 주소를 이미 다른 계정이 확인해 두었으면 주장하지 않는다. 주소는 남기되
     * `email_verified_at`을 비워, `uk_app_user_verified_email`과 부딪히지 않게 한다. 같은 이메일을
     * 쓰는 다른 제공자로 가입하는 것은 막을 일이 아니고([upsert]가 자동 연결을 거부하는 것과 같은
     * 이유로 두 계정이다), 그 사람은 계정 설정에서 다시 확인하면 된다 — 그때 409로 답한다.
     *
     * 마이그레이션 `V81`이 기존 행을 옮길 때 쓴 규칙과 같다. 먼저 온 계정이 주소를 갖는다.
     */
    private suspend fun newIdentityFor(
        identity: OAuthIdentity,
        now: Instant,
        claimEmail: Boolean
    ): OAuthIdentityEntity {
        val verifiedAt = identity.email
            ?.takeIf { claimEmail && appUserRepository.findVerifiedByEmail(it).toList().isEmpty() }
            ?.let { now }
        val appUser = appUserRepository.save(
            AppUserEntity(
                displayName = identity.displayName,
                email = identity.email,
                emailVerifiedAt = verifiedAt,
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
            nickname = appUser.nickname,
            battleTag = appUser.battleTag,
            emailVerifiedAt = appUser.emailVerifiedAt,
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

    /**
     * nickname과 battleTag를 갱신한다. 세션이 가리키는 사용자가 더 이상 없으면 null이다.
     *
     * 두 값 모두 통째로 덮어쓴다 — null을 넘기면 그 필드를 지운다. 트리밍과 형식 검증은 API
     * 경계에서 끝난 뒤이므로 여기서는 저장만 한다.
     */
    suspend fun updateProfile(userId: Long, nickname: String?, battleTag: String?): AppUserEntity? {
        val appUser = appUserRepository.findById(userId) ?: return null
        return appUserRepository.save(
            appUser.copy(nickname = nickname, battleTag = battleTag, updatedAt = Instant.now(clock))
        )
    }
}
