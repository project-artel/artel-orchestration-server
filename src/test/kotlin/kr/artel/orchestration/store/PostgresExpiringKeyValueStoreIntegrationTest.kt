package kr.artel.orchestration.store

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.sdk.SdkLoginCodeKind
import kr.artel.orchestration.auth.sdk.SdkLoginCodeStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

@ActiveProfiles("test")
@SpringBootTest
class PostgresExpiringKeyValueStoreIntegrationTest {
    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var authProperties: AuthProperties

    private lateinit var store: PostgresExpiringKeyValueStore

    @BeforeEach
    fun clean() {
        store = PostgresExpiringKeyValueStore(databaseClient)
        runBlocking {
            databaseClient.sql("DELETE FROM expiring_entry WHERE entry_key LIKE 'test:%'").fetch().rowsUpdated().block()
        }
    }

    @Test
    fun `저장한 값을 TTL 안에 읽는다`(): Unit = runBlocking {
        store.set("test:a", "value", Duration.ofMinutes(5))

        assertThat(store.get("test:a")).isEqualTo("value")
        // get 은 지우지 않는다.
        assertThat(store.get("test:a")).isEqualTo("value")
    }

    @Test
    fun `같은 키를 다시 저장하면 값과 TTL 을 덮어쓴다`(): Unit = runBlocking {
        store.set("test:a", "first", Duration.ofMillis(300))
        store.set("test:a", "second", Duration.ofMinutes(5))
        Thread.sleep(500)

        assertThat(store.get("test:a")).isEqualTo("second")
    }

    @Test
    fun `만료된 행은 읽히지 않고 getAndDelete 도 null 이다`(): Unit = runBlocking {
        store.set("test:a", "value", Duration.ofMillis(200))
        store.set("test:b", "value", Duration.ofMillis(200))
        Thread.sleep(400)

        assertThat(store.get("test:a")).isNull()
        assertThat(store.getAndDelete("test:b")).isNull()
        // 만료된 행을 getAndDelete 가 지웠다.
        assertThat(store.getAndDelete("test:b")).isNull()
    }

    @Test
    fun `getAndDelete 는 한 번만 값을 준다`(): Unit = runBlocking {
        store.set("test:a", "value", Duration.ofMinutes(5))

        assertThat(store.getAndDelete("test:a")).isEqualTo("value")
        assertThat(store.getAndDelete("test:a")).isNull()
        assertThat(store.get("test:a")).isNull()
    }

    @Test
    fun `동시에 getAndDelete 를 불러도 한 호출자만 값을 받는다`(): Unit = runBlocking {
        store.set("test:a", "value", Duration.ofMinutes(5))

        val results = (1..8).map { async { store.getAndDelete("test:a") } }.awaitAll()

        assertThat(results.filterNotNull()).containsExactly("value")
    }

    @Test
    fun `delete 는 행을 지우고 없는 키에도 실패하지 않는다`(): Unit = runBlocking {
        store.set("test:a", "value", Duration.ofMinutes(5))

        store.delete("test:a")
        store.delete("test:never")

        assertThat(store.get("test:a")).isNull()
    }

    @Test
    fun `deleteExpired 는 만료된 행만 지운다`(): Unit = runBlocking {
        store.set("test:old", "value", Duration.ofMillis(200))
        store.set("test:live", "value", Duration.ofMinutes(5))
        Thread.sleep(400)

        assertThat(store.deleteExpired()).isGreaterThanOrEqualTo(1)

        assertThat(rowCount("test:old")).isZero()
        assertThat(rowCount("test:live")).isEqualTo(1)
    }

    @Test
    fun `SDK 로그인 코드는 postgres 저장소로도 한 번만 교환된다`(): Unit = runBlocking {
        val verifier = "verifier-that-is-long-enough-for-pkce-43-chars"
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val issuer = SdkLoginCodeStore(store, authProperties)
        val exchanger = SdkLoginCodeStore(PostgresExpiringKeyValueStore(databaseClient), authProperties)

        val code = issuer.issue(42L, challenge, SdkLoginCodeKind.SDK)

        assertThat(exchanger.consume(code, verifier, SdkLoginCodeKind.SDK)).isEqualTo(42L)
        assertThat(exchanger.consume(code, verifier, SdkLoginCodeKind.SDK)).isNull()
    }

    private fun rowCount(key: String): Long =
        databaseClient.sql("SELECT count(*) AS n FROM expiring_entry WHERE entry_key = :key")
            .bind("key", key)
            .map { row -> row.get("n", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!
}
