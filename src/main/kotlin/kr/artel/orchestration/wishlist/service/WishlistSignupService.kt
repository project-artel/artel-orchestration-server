package kr.artel.orchestration.wishlist.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.wishlist.repository.WishlistSignupRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Wishlist 이메일 길이 상한. SMTP 가 실제로 받는 주소 길이다. 로그인 이메일(`MAX_LOGIN_EMAIL_LENGTH`, 320)보다
 * 짧은 이유는 이 주소로 메일을 보낼 것이라서다 — 320 자 주소는 저장은 되어도 보낼 수 없다.
 */
const val MAX_WISHLIST_EMAIL_LENGTH = 254

/**
 * Wishlist 이메일 형식. 도메인에 점이 하나 이상 있고 마지막 label(TLD)이 ASCII 글자 2자 이상이어야 한다.
 *
 * 연락 이메일 확인(`EmailVerificationService`)의 형식보다 좁다. 그쪽은 `o@s.s` 를 받지만, 이 목록은 로그인한 사람이
 * 아니라 누구나 채우는 칸이라 오타와 장난 입력을 저장 전에 더 걸러 둔다. 두 흐름이 같은 형식을 쓸 이유가 없어 따로 둔다.
 */
private val WISHLIST_EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)*\\.[A-Za-z]{2,}$")

/** `wishlist_signup.locale` 이 받는 값. */
private val WISHLIST_LOCALES = setOf("ko", "en")

/**
 * 소개 사이트의 Wishlist 가입. 이메일을 정규화해 한 번만 저장한다.
 *
 * 이미 있는 주소도 처음과 같이 끝난다. 응답이 다르면 아무 주소나 넣어 보고 가입 여부를 알아낼 수 있다.
 *
 * 검사 순서는 honeypot, email, consent, locale 이다. 셋 다 잘못된 요청은 `invalid_email` 하나만 받는다.
 *
 * honeypot(`website`)이 채워진 요청은 검사 없이 204 로 끝나고 아무것도 저장하지 않는다. 사람에게는 보이지 않는 칸이라
 * 채운 쪽은 bot 이고, 400 을 주면 bot 이 어느 칸 때문에 막혔는지 배운다.
 */
@Service
class WishlistSignupService(
    private val wishlistSignupRepository: WishlistSignupRepository,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(WishlistSignupService::class.java)

    suspend fun signup(email: String?, locale: String?, consent: Boolean?, honeypot: String?) {
        if (!honeypot.isNullOrBlank()) {
            // 이메일은 남기지 않는다. bot 이 넣은 값이라도 남의 주소일 수 있다.
            logger.info("Wishlist honeypot 이 채워진 요청을 버렸다")
            return
        }
        val normalizedEmail = normalizeEmail(email)
        if (consent != true) throw WishlistConsentRequiredException()
        val normalizedLocale = normalizeLocale(locale)
        // 동의 시각은 클라이언트가 보낸 값이 아니라 서버가 요청을 받은 시각이다.
        wishlistSignupRepository.insertIfAbsent(normalizedEmail, normalizedLocale, consentedAt = Instant.now(clock))
    }

    /** 형식은 [WISHLIST_EMAIL_PATTERN], 길이 상한은 [MAX_WISHLIST_EMAIL_LENGTH] 다. */
    private fun normalizeEmail(email: String?): String =
        email?.trim()?.lowercase()
            ?.takeIf { it.length <= MAX_WISHLIST_EMAIL_LENGTH && WISHLIST_EMAIL_PATTERN.matches(it) }
            ?: throw InvalidWishlistEmailException()

    private fun normalizeLocale(locale: String?): String? {
        if (locale == null) return null
        return locale.trim().lowercase().takeIf { it in WISHLIST_LOCALES } ?: throw InvalidWishlistLocaleException()
    }
}

class InvalidWishlistEmailException :
    BadRequestException("이메일 형식이 아닙니다.", code = "invalid_email")

/** 개인정보 수집·이용 동의 없이 보낸 요청. 동의 체크박스는 소개 사이트가 그린다. */
class WishlistConsentRequiredException :
    BadRequestException("개인정보 수집·이용에 동의해야 합니다.", code = "consent_required")

class InvalidWishlistLocaleException :
    BadRequestException("locale 은 ko 또는 en 이어야 합니다.", code = "invalid_locale")
