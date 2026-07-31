package kr.artel.orchestration.auth.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kr.artel.orchestration.auth.config.AuthProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import java.security.MessageDigest
import java.util.Base64

private const val VERIFIER = "verifier-that-is-long-enough-for-pkce-43-chars"
private const val OTHER_VERIFIER = "a-different-verifier-that-is-also-long-enough-1"

@ActiveProfiles("test")
@SpringBootTest
class SdkLoginCodeStoreIntegrationTest {
    @Autowired
    private lateinit var store: SdkLoginCodeStore

    @Autowired
    private lateinit var redis: ReactiveStringRedisTemplate

    @Autowired
    private lateinit var properties: AuthProperties

    /**
     * Redis는 스위트 전체가 공유하고 코드 키는 TTL이 5분이라 테스트 사이에 그대로 남는다.
     * 앞 테스트가 예외로 끊겨도 다음 테스트가 깨끗한 키 공간에서 시작하도록 시작 시점에 비운다.
     */
    @BeforeEach
    fun clean() {
        runBlocking { redis.delete(redis.keys("$SDK_LOGIN_CODE_KEY_PREFIX*")).awaitSingle() }
    }

    @Test
    fun `발급한 코드를 올바른 verifier로 교환하면 발급 대상이 나온다`(): Unit = runBlocking {
        val code = store.issue(7L, challengeOf(VERIFIER))

        assertThat(store.consume(code, VERIFIER)).isEqualTo(7L)
    }

    @Test
    fun `없는 코드는 교환되지 않는다`(): Unit = runBlocking {
        assertThat(store.consume("never-issued", VERIFIER)).isNull()
    }

    @Test
    fun `verifier가 맞지 않으면 교환되지 않고, 코드는 그 시도로 소진된다`(): Unit = runBlocking {
        val code = store.issue(7L, challengeOf(VERIFIER))

        assertThat(store.consume(code, OTHER_VERIFIER)).isNull()
        // 틀린 시도가 코드를 남겨두면 verifier를 바꿔가며 좁혀갈 수 있다.
        assertThat(store.consume(code, VERIFIER)).isNull()
    }

    /**
     * 한 번만 돌리면 코루틴 스케줄 편차 때문에 GET 후 DEL 같은 비원자적 구현도 통과할 수 있다.
     * 원자성은 명시된 수용 조건이므로 반복해서 경합을 만든다. sleep이 없어 수십 밀리초면 끝난다.
     */
    @Test
    fun `같은 코드를 동시에 두 번 교환하면 한 번만 성공한다`(): Unit = runBlocking {
        repeat(20) {
            val code = store.issue(9L, challengeOf(VERIFIER))

            val results = withContext(Dispatchers.Default) {
                listOf(
                    async { store.consume(code, VERIFIER) },
                    async { store.consume(code, VERIFIER) }
                ).awaitAll()
            }

            assertThat(results.filterNotNull()).containsExactly(9L)
        }
    }

    /** 이 이슈의 본래 목적. 발급과 교환이 다른 프로세스로 갈라져도 성립해야 한다. */
    @Test
    fun `한 인스턴스가 발급한 코드를 다른 인스턴스가 교환한다`(): Unit = runBlocking {
        val issuer = SdkLoginCodeStore(redis, properties)
        val exchanger = SdkLoginCodeStore(redis, properties)

        val code = issuer.issue(42L, challengeOf(VERIFIER))

        assertThat(exchanger.consume(code, VERIFIER)).isEqualTo(42L)
    }

    @Test
    fun `발급한 코드 키에 TTL이 걸려 있다`(): Unit = runBlocking {
        store.issue(1L, challengeOf(VERIFIER))

        val key = issuedKeys().single()
        val ttl = redis.getExpire(key).awaitSingle()

        // 만료를 실제로 기다리지 않는다. 저장소가 만료를 맡았는지만 본다.
        // 아래쪽 경계도 잡는다. isPositive만으로는 TTL을 1초로 줄여도 통과한다.
        assertThat(ttl)
            .isGreaterThan(properties.sdkLoginCodeTtl.minusSeconds(30))
            .isLessThanOrEqualTo(properties.sdkLoginCodeTtl)
    }

    @Test
    fun `코드 원문은 키에 남지 않는다`(): Unit = runBlocking {
        val code = store.issue(1L, challengeOf(VERIFIER))

        assertThat(issuedKeys().single()).doesNotContain(code)
    }

    private suspend fun issuedKeys(): List<String> =
        redis.keys("$SDK_LOGIN_CODE_KEY_PREFIX*").collectList().awaitSingle()

    /** PKCE S256. 프로덕션 코드와 같은 계산을 테스트 쪽에서 독립적으로 한다. */
    private fun challengeOf(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
}
