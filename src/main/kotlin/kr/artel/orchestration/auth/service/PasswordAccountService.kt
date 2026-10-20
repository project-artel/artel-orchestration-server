package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.config.SignupProperties
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.entity.LocalCredentialEntity
import kr.artel.orchestration.auth.entity.MAX_LOGIN_EMAIL_LENGTH
import kr.artel.orchestration.auth.entity.MAX_NICKNAME_LENGTH
import kr.artel.orchestration.auth.entity.PlatformRole
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.LocalCredentialRepository
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.ForbiddenException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.common.error.UnauthorizedException
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

/** 비밀번호 로그인으로 발급한 JWT 의 `provider` claim. OAuth 제공자 이름과 겹치지 않는다. */
const val PASSWORD_PROVIDER = "password"

/** 비밀번호 최소 길이. */
const val MIN_PASSWORD_LENGTH = 8

/** BCrypt 는 72 바이트 뒤를 버린다. 그보다 긴 비밀번호는 뒤가 달라도 같은 비밀번호로 통하므로 받지 않는다. */
const val MAX_PASSWORD_BYTES = 72

/** 로그인 이메일 형식. 직접 설치한 서버는 `admin@localhost` 같은 주소도 쓰므로 점을 요구하지 않는다. */
private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+$")

/** 새로 만들어진 계정. 임시 비밀번호는 ADMIN 이 만든 계정에만 있다. */
data class CreatedAccount(val user: AppUserEntity, val temporaryPassword: String?) {
    /** 임시 비밀번호가 로그로 나가지 않도록 뺀다. */
    override fun toString(): String = "CreatedAccount(userId=${user.id})"
}

/**
 * 이메일과 비밀번호 계정의 가입, 로그인, 비밀번호 변경, 그리고 ADMIN 의 계정 생성과 초기화.
 *
 * 비밀번호 원문은 이 클래스 밖으로 나가지 않고, 로그에도 남기지 않는다. 임시 비밀번호만 예외로
 * 응답에 한 번 실린다.
 */
@Service
class PasswordAccountService(
    private val appUserRepository: AppUserRepository,
    private val localCredentialRepository: LocalCredentialRepository,
    private val oauthUserService: OAuthUserService,
    private val firstUserGate: FirstUserGate,
    private val passwordHasher: PasswordHasher,
    private val temporaryPasswordGenerator: TemporaryPasswordGenerator,
    private val signupProperties: SignupProperties,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    /** 지금 누구나 가입할 수 있는지. 첫 사용자가 아직 없으면 [SignupProperties.open] 과 무관하게 열려 있다. */
    suspend fun signupOpen(): Boolean = signupProperties.open || !firstUserGate.anyUserExists()

    /**
     * 공개 가입. 설치의 첫 사용자면 ADMIN 이 된다.
     *
     * "첫 사용자인가" 와 행 삽입이 한 트랜잭션 안에서 [FirstUserGate] 잠금 뒤에 있으므로, 두 가입이
     * 동시에 와도 ADMIN 은 하나다. 늦게 온 쪽은 잠금이 풀린 뒤 행이 있는 것을 보고 USER 가 되거나,
     * 가입이 닫혀 있으면 403 을 받는다.
     *
     * BCrypt 해시는 트랜잭션 밖에서 먼저 만든다. 잠금을 잡은 채 수십 ms 를 CPU 로 쓰면 그동안 다른
     * 가입이 전부 기다린다.
     */
    suspend fun signup(email: String, password: String, name: String): AuthenticatedUser {
        val loginEmail = normalizeEmail(email)
        val displayName = normalizeName(name)
        validatePassword(password)
        val passwordHash = passwordHasher.hash(password)

        val user = transactionalOperator.executeAndAwait {
            val isFirstUser = firstUserGate.lockAndCheckFirstUser()
            if (!isFirstUser && !signupProperties.open) throw SignupClosedException()
            insertAccount(
                loginEmail = loginEmail,
                displayName = displayName,
                passwordHash = passwordHash,
                platformRole = if (isFirstUser) PlatformRole.ADMIN else PlatformRole.USER,
                mustChangePassword = false
            )
        }!!
        return authenticatedUser(user, loginEmail)
    }

    /**
     * 이메일과 비밀번호로 로그인한다.
     *
     * 이메일이 없는 경우와 비밀번호가 틀린 경우를 같은 401 하나로 답하고, 계정이 없어도 BCrypt 대조를
     * 한 번 한다. 둘을 가르면 응답이나 응답 시간만 보고 그 이메일로 가입한 사람이 있는지 알 수 있다.
     *
     * 막힌 계정은 비밀번호가 맞은 뒤에만 403 으로 알린다. 비밀번호를 모르는 사람에게는 막혔다는
     * 사실도 알리지 않는다.
     */
    suspend fun login(email: String, password: String): AuthenticatedUser {
        val loginEmail = email.trim().lowercase()
        if (password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES) throw InvalidCredentialsException()
        val credential = localCredentialRepository.findByEmailIgnoringCase(loginEmail)
        if (credential == null) {
            passwordHasher.matchesNothing(password)
            throw InvalidCredentialsException()
        }
        if (!passwordHasher.matches(password, credential.passwordHash)) throw InvalidCredentialsException()
        val user = appUserRepository.findById(credential.appUserId) ?: throw InvalidCredentialsException()
        if (user.disabled) throw AccountDisabledException()
        return authenticatedUser(user, credential.email)
    }

    /**
     * 자기 비밀번호를 바꾼다. `must_change_password` 도 함께 내린다.
     *
     * 지금 비밀번호를 다시 받는다. 열려 있는 브라우저 하나를 잠깐 손에 넣은 사람이 비밀번호를 바꿔
     * 계정을 가져가는 것을 막는다. 임시 비밀번호로 들어온 사람에게는 그 임시 비밀번호가 지금 비밀번호다.
     */
    suspend fun changePassword(userId: Long, currentPassword: String, newPassword: String) {
        val credential = localCredentialRepository.findById(userId) ?: throw PasswordNotSetException()
        val currentMatches = currentPassword.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES &&
            passwordHasher.matches(currentPassword, credential.passwordHash)
        if (!currentMatches) throw InvalidCurrentPasswordException()
        validatePassword(newPassword)
        if (newPassword == currentPassword) throw SamePasswordException()
        localCredentialRepository.updatePassword(
            appUserId = userId,
            passwordHash = passwordHasher.hash(newPassword),
            mustChangePassword = false,
            now = Instant.now(clock)
        )
    }

    /**
     * ADMIN 이 계정을 만든다. 임시 비밀번호를 만들어 돌려주고, 그 사람은 처음 로그인한 뒤 비밀번호를
     * 바꾸기 전까지 다른 요청을 할 수 없다.
     *
     * 가입이 닫혀 있어도 된다 — 가입을 닫은 설치에서 사람을 들이는 길이 이것이다.
     */
    suspend fun createByAdmin(email: String, name: String, platformRole: PlatformRole): CreatedAccount {
        val loginEmail = normalizeEmail(email)
        val displayName = normalizeName(name)
        val temporaryPassword = temporaryPasswordGenerator.generate()
        val passwordHash = passwordHasher.hash(temporaryPassword)

        val user = transactionalOperator.executeAndAwait {
            firstUserGate.lockAccountChanges()
            insertAccount(
                loginEmail = loginEmail,
                displayName = displayName,
                passwordHash = passwordHash,
                platformRole = platformRole,
                mustChangePassword = true
            )
        }!!
        return CreatedAccount(user, temporaryPassword)
    }

    /**
     * ADMIN 이 비밀번호를 임시 비밀번호로 초기화한다.
     *
     * 비밀번호 자격증명이 없는 계정(GitHub 으로만 들어온 사람)이면 `app_user.email` 로 새로 만든다. 그
     * 주소가 비어 있거나 이미 다른 계정의 로그인 이메일이면 409 다.
     */
    suspend fun resetByAdmin(userId: Long): String {
        val user = appUserRepository.findById(userId) ?: throw AccountNotFoundException()
        val temporaryPassword = temporaryPasswordGenerator.generate()
        val passwordHash = passwordHasher.hash(temporaryPassword)
        val now = Instant.now(clock)

        transactionalOperator.executeAndAwait {
            firstUserGate.lockAccountChanges()
            val updated = localCredentialRepository.updatePassword(userId, passwordHash, mustChangePassword = true, now = now)
            if (updated == 0) {
                val loginEmail = user.email?.let(::normalizeEmailOrNull) ?: throw LoginEmailMissingException()
                if (localCredentialRepository.findByEmailIgnoringCase(loginEmail) != null) throw EmailTakenException()
                localCredentialRepository.insert(userId, loginEmail, passwordHash, mustChangePassword = true, now = now)
            }
        }
        return temporaryPassword
    }

    /** 이 사용자의 비밀번호 자격증명. GitHub 으로만 들어온 사람이면 null 이다. */
    suspend fun credentialOf(userId: Long): LocalCredentialEntity? = localCredentialRepository.findById(userId)

    /**
     * 재발급할 때 쓰는 비밀번호 세션의 표시용 claim. OAuth 신원이 하나도 없는 계정이 이 길을 탄다.
     * 자격증명도 없으면 null 이다.
     */
    suspend fun passwordSessionUser(userId: Long): AuthenticatedUser? {
        val credential = localCredentialRepository.findById(userId) ?: return null
        val user = appUserRepository.findById(userId) ?: return null
        return authenticatedUser(user, credential.email)
    }

    /** 잠금 안에서 부른다. 같은 로그인 이메일이 이미 있으면 409 다. */
    private suspend fun insertAccount(
        loginEmail: String,
        displayName: String,
        passwordHash: String,
        platformRole: PlatformRole,
        mustChangePassword: Boolean
    ): AppUserEntity {
        if (localCredentialRepository.findByEmailIgnoringCase(loginEmail) != null) throw EmailTakenException()
        val now = Instant.now(clock)
        val user = appUserRepository.save(
            AppUserEntity(
                displayName = displayName,
                // 연락 주소로도 남긴다. 확인은 하지 않았으므로 초대를 받으려면 계정 화면에서 확인을 거친다.
                email = loginEmail,
                platformRole = platformRole.name,
                nickname = displayName,
                userTag = oauthUserService.assignUserTag(displayName, forUserId = null, currentUserTag = null),
                createdAt = now,
                updatedAt = now
            )
        )
        val userId = requireNotNull(user.id) { "app_user id was not generated" }
        localCredentialRepository.insert(userId, loginEmail, passwordHash, mustChangePassword, now)
        return user
    }

    private fun authenticatedUser(user: AppUserEntity, loginEmail: String) = AuthenticatedUser(
        userId = requireNotNull(user.id).toString(),
        provider = PASSWORD_PROVIDER,
        login = loginEmail,
        displayName = user.displayName,
        avatarUrl = null
    )

    private fun normalizeEmail(email: String): String =
        normalizeEmailOrNull(email) ?: throw BadRequestException("이메일 형식이 아닙니다.", code = "invalid_email")

    private fun normalizeEmailOrNull(email: String): String? =
        email.trim().lowercase().takeIf { it.length <= MAX_LOGIN_EMAIL_LENGTH && EMAIL_PATTERN.matches(it) }

    private fun normalizeName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NICKNAME_LENGTH) {
            throw BadRequestException("이름은 1자 이상 ${MAX_NICKNAME_LENGTH}자 이하여야 합니다.", code = "invalid_name")
        }
        return trimmed
    }

    private fun validatePassword(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH || password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES) {
            throw BadRequestException(
                "비밀번호는 ${MIN_PASSWORD_LENGTH}자 이상, ${MAX_PASSWORD_BYTES}바이트 이하여야 합니다.",
                code = "invalid_password"
            )
        }
    }
}

/** 첫 사용자가 생긴 뒤 `ARTEL_SIGNUP_OPEN` 이 꺼져 있을 때. */
class SignupClosedException :
    ForbiddenException("가입이 닫혀 있습니다. 관리자에게 계정을 요청하세요.", code = "signup_closed")

class EmailTakenException :
    ConflictException("이미 가입한 이메일입니다.", code = "email_taken")

/** 이메일이 없는 경우와 비밀번호가 틀린 경우를 가르지 않는다. */
class InvalidCredentialsException :
    UnauthorizedException("이메일 또는 비밀번호가 맞지 않습니다.", code = "invalid_credentials")

class AccountDisabledException :
    ForbiddenException("비활성화된 계정입니다.", code = "account_disabled")

/** GitHub 으로만 들어와 비밀번호가 없는 계정이 비밀번호를 바꾸려 할 때. */
class PasswordNotSetException :
    ConflictException("이 계정에는 비밀번호가 없습니다.", code = "password_not_set")

/**
 * 비밀번호 변경에서 지금 비밀번호가 틀렸을 때. 401 이 아닌 이유는 클라이언트가 401 을 세션 만료로
 * 읽고 재발급을 시도하기 때문이다 — 세션은 멀쩡하다.
 */
class InvalidCurrentPasswordException :
    BadRequestException("지금 비밀번호가 맞지 않습니다.", code = "invalid_current_password")

class SamePasswordException :
    BadRequestException("새 비밀번호가 지금 비밀번호와 같습니다.", code = "same_password")

class AccountNotFoundException : NotFoundException("사용자를 찾을 수 없습니다.")

/** 비밀번호를 초기화하려는 계정에 로그인 이메일로 쓸 주소가 없을 때. */
class LoginEmailMissingException :
    ConflictException("이 계정에는 로그인 이메일로 쓸 주소가 없습니다.", code = "login_email_missing")
