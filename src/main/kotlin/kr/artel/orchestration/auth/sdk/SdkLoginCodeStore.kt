package kr.artel.orchestration.auth.sdk

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.auth.config.AuthProperties
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private const val CODE_BYTES = 32

/** 발급된 로그인 코드가 사는 키 공간. 테스트가 이 접두사로 키를 훑는다. */
internal const val SDK_LOGIN_CODE_KEY_PREFIX = "artel:sdk:login-code:"

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
 * **왜 Redis인가.** 발급은 브라우저가, 교환은 SDK가 부른다. 서로 다른 요청이라 인스턴스가 둘
 * 이상이면 다른 프로세스로 갈라지고, 인메모리로는 그 순간 교환이 실패한다.
 *
 * **키에 코드 원문을 쓰지 않는다.** Redis를 들여다볼 수 있는 쪽(운영자, `MONITOR`, 덤프)이
 * 유효한 로그인 코드를 그대로 읽게 된다. 키는 코드의 SHA-256이고 원문은 어디에도 남지 않는다.
 *
 * ponytail: 이것만으로 레플리카를 늘릴 수는 없다. `SessionManager`, `WebSocketQaAgentAdapter`,
 * `TestScenarioStreamManager`, `QaLogStreamManager`, `ViewerSessionRegistry`가 여전히 소켓과 함께
 * 인스턴스에 고정돼 있다. 그쪽은 저장소 이전이 아니라 pub/sub 라우팅이 필요한 별개 문제다.
 */
@Component
class SdkLoginCodeStore(
    private val redis: ReactiveStringRedisTemplate,
    private val properties: AuthProperties
) {
    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * 로그인한 사용자에게 코드를 하나 발급한다.
     *
     * 만료는 키 TTL이 판정한다. 애플리케이션이 만료 시각을 들고 비교하지 않으므로 청소할
     * 잔재도 없다 — 교환되지 않은 코드는 Redis가 알아서 지운다.
     */
    suspend fun issue(userId: Long, codeChallenge: String): String {
        val bytes = ByteArray(CODE_BYTES).also(random::nextBytes)
        val code = encoder.encodeToString(bytes)
        redis.opsForValue()
            .set(keyOf(code), "$userId:$codeChallenge", properties.sdkLoginCodeTtl)
            .awaitSingle()
        return code
    }

    /**
     * 코드를 소비하고 그 주인을 돌려준다. 코드가 없거나, 만료됐거나, verifier가 challenge와
     * 맞지 않으면 null이다.
     *
     * 읽기와 삭제를 `GETDEL` 하나로 묶는다. 읽고 나서 지우면 두 프로세스가 같은 코드를 동시에
     * 교환하는 창이 남는다.
     *
     * 실패해도 코드는 이미 지워진 뒤다. 남겨두면 verifier를 바꿔가며 다시 시도할 수 있다.
     *
     * Redis 장애는 예외로 그대로 올라간다. null로 삼키면 호출부가 400 "유효하지 않은 로그인
     * 코드"를 내어, 멀쩡한 코드를 든 사용자에게 코드가 틀렸다고 말하게 된다. 장애가 사용자
     * 입력 오류로 위장되면 원인을 찾을 단서가 사라진다.
     */
    suspend fun consume(code: String, codeVerifier: String): Long? {
        val stored = redis.opsForValue().getAndDelete(keyOf(code)).awaitSingleOrNull()
            ?: return null

        // 값은 "<userId>:<codeChallenge>"다. 경계를 첫 ':'로 잡는 근거는 **앞쪽**에 있다 —
        // userId는 서버가 찍은 Long이라 ':'를 담을 수 없다. challenge 쪽은 클라이언트가 준
        // 문자열이고 형식 검증이 없으므로 ':'가 들어올 수 있는데, 그래도 뒤쪽을 통째로 가져오는
        // 이 파싱은 무엇이 오든 원문 그대로를 돌려준다. split(":")이나 lastIndexOf로 "고치면"
        // 그때 깨진다. 필드 둘에 JSON을 들일 이유는 없다.
        val separator = stored.indexOf(':')
        if (separator < 0) {
            return null
        }
        if (stored.substring(separator + 1) != challengeOf(codeVerifier)) {
            return null
        }
        return stored.substring(0, separator).toLongOrNull()
    }

    /** PKCE S256: base64url(SHA-256(verifier)), 패딩 없음. */
    private fun challengeOf(codeVerifier: String): String = sha256Base64Url(codeVerifier)

    private fun keyOf(code: String): String = SDK_LOGIN_CODE_KEY_PREFIX + sha256Base64Url(code)

    private fun sha256Base64Url(value: String): String =
        encoder.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
        )
}
