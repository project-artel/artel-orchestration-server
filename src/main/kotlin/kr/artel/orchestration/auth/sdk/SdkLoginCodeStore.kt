package kr.artel.orchestration.auth.sdk

import kr.artel.orchestration.auth.config.AuthProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private const val CODE_BYTES = 32

/**
 * SDK 로그인에서 브라우저와 SDK 사이를 잇는 일회용 코드.
 *
 * SDK는 loopback 서버로 돌아온 코드를 토큰으로 바꾼다. 토큰 자체를 리다이렉트 URL에 실으면
 * 브라우저 히스토리와 중간 프록시 로그에 30일짜리 자격증명이 남으므로, URL에는 코드만 싣고
 * 교환은 SDK가 직접 POST로 한다.
 *
 * loopback 리다이렉트는 같은 머신의 다른 프로세스가 포트를 선점해 코드를 가로챌 수 있는
 * 공개 클라이언트 흐름이다. 그래서 코드 발급 시 SDK가 만든 code_challenge를 함께 묶고,
 * 교환 시점에 원본 code_verifier를 요구한다. 코드만 훔친 쪽은 verifier가 없어 교환하지 못한다.
 *
 * ponytail: 인메모리라 서버 인스턴스가 하나라는 전제가 깔려 있다. 발급과 교환이 서로 다른
 * 레플리카로 가면 교환이 실패한다. 레플리카를 늘릴 때 Redis나 테이블로 옮긴다.
 */
@Component
class SdkLoginCodeStore(
    private val properties: AuthProperties,
    private val clock: Clock
) {
    private data class Entry(val userId: Long, val codeChallenge: String, val expiresAt: Instant)

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val entries = ConcurrentHashMap<String, Entry>()

    /** 로그인한 사용자에게 코드를 하나 발급한다. */
    fun issue(userId: Long, codeChallenge: String): String {
        purgeExpired()
        val bytes = ByteArray(CODE_BYTES).also(random::nextBytes)
        val code = encoder.encodeToString(bytes)
        entries[code] = Entry(userId, codeChallenge, Instant.now(clock).plus(properties.sdkLoginCodeTtl))
        return code
    }

    /**
     * 코드를 소비하고 그 주인을 돌려준다. 코드가 없거나, 만료됐거나, verifier가 challenge와
     * 맞지 않으면 null이다.
     *
     * 실패해도 코드는 이미 지워진 뒤다. 남겨두면 verifier를 바꿔가며 다시 시도할 수 있다.
     */
    fun consume(code: String, codeVerifier: String): Long? {
        val entry = entries.remove(code) ?: return null
        if (Instant.now(clock).isAfter(entry.expiresAt)) {
            return null
        }
        if (challengeOf(codeVerifier) != entry.codeChallenge) {
            return null
        }
        return entry.userId
    }

    /** PKCE S256: base64url(SHA-256(verifier)), 패딩 없음. */
    private fun challengeOf(codeVerifier: String): String =
        encoder.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        )

    /**
     * 만료된 코드를 지운다. 발급할 때만 도는 것으로 충분하다. 교환되지 않은 코드는 발급량에
     * 비례해서만 쌓이고, 다음 발급이 그 뒤처리를 한다.
     */
    private fun purgeExpired() {
        val now = Instant.now(clock)
        entries.values.removeIf { now.isAfter(it.expiresAt) }
    }
}
