package kr.artel.orchestration.auth.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.entity.EmailVerificationEntity
import kr.artel.orchestration.auth.mail.MailSender
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.EmailVerificationRepository
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.UnauthorizedException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * 확인 토큰의 수명. 설정이 아니라 상수인 이유는 `INVITATION_LIFETIME` 과 같다 — stage 와 prod 가
 * 서로 다른 기간 뒤에 토큰을 죽일 이유가 없고, 다르게 두면 "내 확인 메일이 왜 죽었나"를 설정을
 * 열어보기 전에는 아무도 답할 수 없다.
 */
private val VERIFICATION_LIFETIME: Duration = Duration.ofHours(24)

/** 토큰의 원본 바이트 길이. base64url 로 43자가 된다. */
private const val TOKEN_BYTES = 32

/**
 * 계정이 답하는 이메일 주소를 받고, 그 주소가 정말 그 사람 것인지 확인한다.
 *
 * 두 단계인 이유는 사용자가 적은 주소를 그대로 믿을 수 없기 때문이다. 남의 주소를 적어 넣는 것을
 * 막지 않으면, 그 주소로 간 초대를 가져가는 길이 열린다. [issue] 는 주소를 받아 둘 뿐이고,
 * [verify] 만이 `app_user.email` 을 바꾼다.
 *
 * 제공자가 준 주소는 이 경로를 지나지 않는다. GitHub 은 자기가 확인을 마친 주소만 공개 이메일로
 * 고를 수 있게 하므로, 가입 시점에 이미 확인된 것으로 본다([OAuthUserService.newIdentityFor]).
 */
@Service
class EmailVerificationService(
    private val appUserRepository: AppUserRepository,
    private val verificationRepository: EmailVerificationRepository,
    private val mailSender: MailSender,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    private val random = SecureRandom()

    /**
     * 주소를 받아 확인 토큰을 발급하고 내보낸다. `app_user.email` 은 아직 바뀌지 않는다.
     *
     * 다른 계정이 이미 확인을 마친 주소면 여기서 409 다. 확인까지 간 뒤에 실패하면 사용자는
     * 토큰을 받아 붙여 넣은 다음에야 안 된다는 것을 알게 된다. 다만 이 확인은 보장이 아니라
     * 빠른 거절일 뿐이고, 실제 방어선은 `uk_app_user_verified_email` 이 [verify] 에서 잡는 것이다
     * — 두 사람이 같은 주소를 동시에 확인하면 조회로는 못 막는다.
     *
     * 자기가 이미 확인한 주소를 다시 넣는 것은 막지 않는다. 새 토큰이 나갈 뿐 잃는 것이 없고,
     * 막으면 "메일이 안 왔는데 다시 보낼 수가 없다"가 된다.
     */
    suspend fun issue(userId: Long, rawEmail: String): EmailVerificationEntity {
        val email = normalize(rawEmail)
        val now = Instant.now(clock)

        val user = appUserRepository.findById(userId) ?: throw UnauthorizedException()
        if (takenByAnother(email, userId)) throw EmailAlreadyVerifiedElsewhereException()

        val token = newToken()
        val saved = verificationRepository.save(
            EmailVerificationEntity(
                appUserId = requireNotNull(user.id),
                email = email,
                tokenHash = hash(token),
                createdAt = now,
                expiresAt = now.plus(VERIFICATION_LIFETIME)
            )
        )

        mailSender.send(
            to = email,
            subject = "ARTEL 이메일 주소 확인",
            body = "계정 설정 화면에 이 코드를 붙여 넣으세요.\n\n$token\n\n" +
                "24시간 뒤에는 통하지 않습니다. 요청한 적이 없다면 이 메일을 무시하세요."
        )

        return saved
    }

    /**
     * 토큰을 받아 그 주소를 계정의 것으로 확정한다.
     *
     * 없는 토큰, 만료된 토큰, 이미 쓴 토큰을 하나의 오류로 답한다. 셋을 갈라 답하면 토큰을 찍어
     * 보는 쪽에 "그 토큰은 있었다"를 알려 주게 된다.
     *
     * 토큰을 쓴 것으로 표시하는 UPDATE 를 먼저 하고 사용자 행을 나중에 쓴다. 그 UPDATE 가
     * 직렬화 지점이라 동시에 들어온 확인 중 하나만 통과한다. 트랜잭션으로 감싸는 것이 함께
     * 필요하다 — 감싸지 않으면 진 쪽이 오류를 받고도 그 전에 쓴 값이 남는다.
     */
    suspend fun verify(userId: Long, token: String): Unit = transactionalOperator.executeAndAwait {
        val now = Instant.now(clock)
        val verification = verificationRepository.findUsableByTokenHash(hash(token.trim()), now)
            ?: throw InvalidVerificationTokenException()

        // 남의 토큰으로는 자기 주소를 바꿀 수 없다. 없는 토큰과 같은 오류로 답한다 — 남의 토큰을
        // 손에 넣은 쪽에 "그 토큰은 다른 계정 것이다"를 알려 줄 이유가 없다.
        if (verification.appUserId != userId) throw InvalidVerificationTokenException()

        val user = appUserRepository.findById(userId) ?: throw UnauthorizedException()

        if (verificationRepository.consume(requireNotNull(verification.id), now) == 0) {
            throw InvalidVerificationTokenException()
        }

        try {
            appUserRepository.save(
                user.copy(
                    email = verification.email,
                    emailVerifiedAt = now,
                    updatedAt = now
                )
            )
        } catch (conflict: DataIntegrityViolationException) {
            // 이 save 에서 깨질 수 있는 제약은 `uk_app_user_verified_email` 하나다. 발급 시점의
            // 조회가 통과한 뒤 다른 계정이 같은 주소를 확정한 경우다.
            //
            // 이 테이블에 제약을 더하면 이 단정이 거짓이 된다. 그때는 제약 이름을 보고 갈라야 한다.
            throw EmailAlreadyVerifiedElsewhereException()
        }
    }

    /** 확인을 기다리는 주소. 계정 설정 화면이 대기 상태를 그리는 데 쓴다. */
    suspend fun pendingEmail(userId: Long): String? =
        verificationRepository.findLatestPending(userId, Instant.now(clock))?.email

    /**
     * 이 주소를 다른 계정이 이미 확정했는지.
     *
     * `app_user.email` 에 unique 제약이 없던 시절의 행이 남아 있어 여러 건이 나올 수 있다. 그중
     * 확인을 마친 것만 본다 — 확인되지 않은 주소는 아직 아무것도 주장하지 않는다.
     */
    private suspend fun takenByAnother(email: String, userId: Long): Boolean =
        appUserRepository.findVerifiedByEmail(email)
            .toList()
            .any { it.id != null && it.id != userId }

    private fun normalize(email: String): String {
        val trimmed = email.trim().lowercase()
        if (trimmed.isEmpty() || trimmed.length > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matches(trimmed)) {
            throw BadRequestException("이메일 주소 형식이 올바르지 않습니다.", code = "invalid_email")
        }
        return trimmed
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 원문을 저장하지 않으므로 조회 키는 해시다. 같은 원문은 항상 같은 hex 64자가 된다. */
    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/**
 * 형식 검사. RFC 5322 를 다 보지 않는다 — 여기서 걸러야 할 것은 오타와 빈 값이고, 주소가 정말
 * 닿는지는 토큰을 받아 오는 것으로만 알 수 있다. 그것이 이 흐름 전체의 논거다.
 */
private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$")

/** `app_user.email` 과 `email_verification.email` 의 컬럼 폭. */
private const val MAX_EMAIL_LENGTH = 320

/** 다른 계정이 이미 확인을 마친 주소일 때. `uk_app_user_verified_email` 이 잡는다. */
class EmailAlreadyVerifiedElsewhereException :
    ConflictException("다른 계정이 이미 쓰고 있는 이메일입니다.", code = "email_already_verified")

/** 없거나, 기한이 지났거나, 이미 쓴 토큰. 셋을 가르지 않는다. */
class InvalidVerificationTokenException :
    BadRequestException("확인 코드가 올바르지 않거나 기한이 지났습니다.", code = "invalid_verification_token")
