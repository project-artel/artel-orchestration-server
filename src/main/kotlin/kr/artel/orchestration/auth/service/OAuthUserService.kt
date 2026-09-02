package kr.artel.orchestration.auth.service

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.toSet
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.entity.MAX_NICKNAME_LENGTH
import kr.artel.orchestration.auth.entity.OAuthIdentityEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

/** display name도 login도 비어 있을 때 쓰는 nickname. V80의 backfill도 같은 값을 쓴다. */
private const val FALLBACK_NICKNAME = "user"

/** user_tag의 기본 자릿수. 이 길이가 다 나가면 한 자리씩 늘어난다. */
private const val MIN_USER_TAG_LENGTH = 4

/** user_tag 배정 저장을 몇 번까지 다시 해 보는지. 이유는 [OAuthUserService.retryingOnConflict]에 있다. */
private const val USER_TAG_ATTEMPTS = 3

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
     *
     * 재시도가 트랜잭션 **바깥**에 있는 것은 Postgres 때문이다. 제약 위반이 나면 그 트랜잭션은
     * 실패 상태가 되어 안에서 다음 문장을 실행할 수 없다. 그래서 트랜잭션을 다시 연다.
     */
    suspend fun upsert(identity: OAuthIdentity): AuthenticatedUser {
        val now = Instant.now(clock)

        return retryingOnConflict {
            transactionalOperator.executeAndAwait {
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
    }

    /**
     * 처음 보는 제공자 계정이므로 사용자 본체부터 만든다. nickname과 user_tag는 이때 정해진다 —
     * 이름이 없는 사용자를 만들 수 없으므로 프로필 갱신을 기다리지 않는다.
     */
    private suspend fun newIdentityFor(identity: OAuthIdentity, now: Instant): OAuthIdentityEntity {
        val nickname = nicknameFrom(identity)
        val appUser = appUserRepository.save(
            AppUserEntity(
                displayName = identity.displayName,
                email = identity.email,
                nickname = nickname,
                userTag = assignUserTag(nickname, forUserId = null, currentUserTag = null),
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
            platformRole = appUser.platformRole,
            nickname = appUser.nickname,
            userTag = appUser.userTag,
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
     * nickname을 바꾸고 바뀐 프로필을 돌려준다. 세션이 가리키는 사용자가 더 이상 없으면 null이다.
     *
     * 이름이 그대로면 아무것도 저장하지 않는다 — 같은 이름을 다시 저장했다고 번호가 바뀌면 그
     * 사람을 가리키던 `nickname#user_tag`가 다른 사람을 가리키게 된다. 이름이 바뀌면 번호를 다시
     * 배정한다. 트리밍과 길이 검증은 API 경계에서 끝난 뒤다.
     */
    suspend fun updateProfile(userId: Long, nickname: String): UserProfile? {
        val appUser = appUserRepository.findById(userId) ?: return null
        if (nickname != appUser.nickname) {
            retryingOnConflict {
                appUserRepository.save(
                    appUser.copy(
                        nickname = nickname,
                        userTag = assignUserTag(nickname, appUser.id, appUser.userTag),
                        updatedAt = Instant.now(clock)
                    )
                )
            }
        }
        return findProfile(userId)
    }

    /**
     * 같은 nickname 아래에서 쓸 수 있는 user_tag를 고른다. 새 사용자를 만들 때도, 이름을 바꿀
     * 때도 번호를 고르는 곳은 여기 하나다.
     *
     * 쓰던 번호가 새 이름 아래에서도 비어 있으면 그대로 쓴다 — 이름만 바꿨는데 번호까지 바뀌면
     * 그 사람에게 알려 둔 `nickname#user_tag`가 두 곳에서 어긋난다.
     */
    private suspend fun assignUserTag(
        nickname: String,
        forUserId: Long?,
        currentUserTag: String?
    ): String {
        val usedUserTags = appUserRepository.findByNickname(nickname)
            .filter { it.id != forUserId }
            .map { it.userTag }
            .toSet()
        if (currentUserTag != null && currentUserTag !in usedUserTags) return currentUserTag
        return lowestFreeUserTag(usedUserTags)
    }

    /**
     * 비어 있는 가장 작은 번호를, 네 자리에서 시작해 그 자릿수가 다 나가면 한 자리씩 늘려 가며 찾는다.
     *
     * 한 자릿수 안에서 훑는 범위가 `0..usedAtLength.size`인 것이 핵심이다. 후보가 size + 1개인데
     * 이미 쓰인 것은 size개뿐이라 그중 하나는 반드시 비어 있다. 그래서 훑는 양이 10^L이 아니라
     * 같은 이름을 쓰는 사람 수에 비례한다. 훑다가 자릿수를 넘긴 후보가 나오면 그 길이는 전부 나간
     * 것이므로 길이를 하나 늘린다. 길이가 다르면 다른 값이라 `0042`와 `00042`는 겹치지 않는다.
     */
    private fun lowestFreeUserTag(usedUserTags: Set<String>): String {
        var length = MIN_USER_TAG_LENGTH
        while (true) {
            val usedAtLength = usedUserTags.filterTo(mutableSetOf()) { it.length == length }
            for (number in 0..usedAtLength.size) {
                val candidate = number.toString().padStart(length, '0')
                if (candidate.length > length) break
                if (candidate !in usedAtLength) return candidate
            }
            length++
        }
    }

    /**
     * user_tag 배정은 "이미 나간 번호를 읽고 → 저장"이라, 두 요청이 그 사이에 끼어 같은 번호를
     * 고를 수 있다. 그때 `uk_app_user_nickname_user_tag`가 저장을 막는데 이것은 오류가 아니라
     * 흔한 경합이다. 번호를 다시 읽고 [USER_TAG_ATTEMPTS]번까지 다시 저장한다 — 매번 다시 읽으므로
     * 앞서 밀린 번호를 또 고르지 않는다.
     *
     * 세 번까지 다 실패하면 마지막 예외를 그대로 올린다. 삼키면 사용자는 저장됐다고 믿고 화면은
     * 옛 값을 그린다.
     */
    private suspend fun <T> retryingOnConflict(block: suspend () -> T): T {
        var lastConflict: DataIntegrityViolationException? = null
        repeat(USER_TAG_ATTEMPTS) {
            try {
                return block()
            } catch (conflict: DataIntegrityViolationException) {
                lastConflict = conflict
            }
        }
        throw checkNotNull(lastConflict)
    }

    /**
     * 제공자 신원에서 처음 nickname을 만든다. 여기서 한 번만 정해지고, 이후 로그인은 이 값을
     * 건드리지 않는다 — display_name은 [refreshedWith]가 매번 제공자 값으로 덮어쓰므로, 그것을
     * 따라가면 사용자가 고른 이름을 담는다는 이 컬럼의 이유가 사라진다.
     *
     * display name이 공백뿐인 계정이 있어 login으로 한 번 더 떨어뜨리고, 그것도 비어 있으면
     * [FALLBACK_NICKNAME]을 쓴다. 이름이 겹쳐도 user_tag가 사람을 가르므로 해가 없다.
     * V80의 backfill이 기존 행에 적용하는 순서와 같다.
     */
    private fun nicknameFrom(identity: OAuthIdentity): String =
        identity.displayName.toNickname()
            ?: identity.login.toNickname()
            ?: FALLBACK_NICKNAME

    /** 컬럼 폭에 맞춰 자르고 앞뒤 공백을 지운다. 남는 것이 없으면 nickname으로 쓸 수 없어 null이다. */
    private fun String.toNickname(): String? =
        take(MAX_NICKNAME_LENGTH).trim().ifEmpty { null }
}
