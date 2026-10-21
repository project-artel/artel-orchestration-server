package kr.artel.orchestration.store

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration

/**
 * PostgreSQL 구현(`expiring_entry`). `ARTEL_STORE=postgres` 일 때 쓴다.
 *
 * 만료 시각은 DB 의 `now()` 로 정하고 DB 의 `now()` 와 비교한다. 인스턴스마다 시계가 달라도 같은
 * 행을 두고 판정이 갈리지 않는다.
 *
 * [getAndDelete] 는 `DELETE ... RETURNING` 한 문장이다. 행 잠금이 걸려 있어 동시에 온 두 호출 중
 * 하나만 행을 받는다. 만료된 행은 지우되 값은 돌려주지 않는다.
 */
class PostgresExpiringKeyValueStore(
    private val databaseClient: DatabaseClient
) : ExpiringKeyValueStore {
    override suspend fun set(key: String, value: String, ttl: Duration) {
        databaseClient.sql(
            """
            INSERT INTO expiring_entry (entry_key, entry_value, expires_at)
            VALUES (:key, :value, now() + make_interval(secs => :seconds))
            ON CONFLICT (entry_key) DO UPDATE
                SET entry_value = EXCLUDED.entry_value, expires_at = EXCLUDED.expires_at
            """.trimIndent()
        )
            .bind("key", key)
            .bind("value", value)
            .bind("seconds", ttl.toMillis() / 1000.0)
            .fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun get(key: String): String? =
        databaseClient.sql(
            "SELECT entry_value FROM expiring_entry WHERE entry_key = :key AND expires_at > now()"
        )
            .bind("key", key)
            .map { row -> row.get("entry_value", String::class.java)!! }
            .one().awaitSingleOrNull()

    override suspend fun getAndDelete(key: String): String? =
        databaseClient.sql(
            """
            DELETE FROM expiring_entry WHERE entry_key = :key
            RETURNING entry_value, (expires_at > now()) AS live
            """.trimIndent()
        )
            .bind("key", key)
            // R2DBC 매퍼는 null 을 돌려줄 수 없어 만료 여부를 값과 함께 받아 밖에서 거른다.
            .map { row ->
                Pair(
                    row.get("live", java.lang.Boolean::class.java)!!.booleanValue(),
                    row.get("entry_value", String::class.java)!!
                )
            }
            .one().awaitSingleOrNull()
            ?.takeIf { (live, _) -> live }?.second

    override suspend fun delete(key: String) {
        databaseClient.sql("DELETE FROM expiring_entry WHERE entry_key = :key")
            .bind("key", key)
            .fetch().rowsUpdated().awaitSingle()
    }

    /** 만료된 행을 지우고 지운 수를 돌려준다. */
    suspend fun deleteExpired(): Long =
        databaseClient.sql("DELETE FROM expiring_entry WHERE expires_at <= now()")
            .fetch().rowsUpdated().awaitSingle()
}
