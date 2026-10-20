package kr.artel.orchestration.store

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Duration

/** Redis 구현. 만료는 키 TTL 이, 한 번만 소비하는 보장은 `GETDEL` 이 맡는다. */
class RedisExpiringKeyValueStore(
    private val redis: ReactiveStringRedisTemplate
) : ExpiringKeyValueStore {
    override suspend fun set(key: String, value: String, ttl: Duration) {
        redis.opsForValue().set(key, value, ttl).awaitSingle()
    }

    override suspend fun get(key: String): String? =
        redis.opsForValue().get(key).awaitSingleOrNull()

    override suspend fun getAndDelete(key: String): String? =
        redis.opsForValue().getAndDelete(key).awaitSingleOrNull()

    override suspend fun delete(key: String) {
        redis.delete(key).awaitSingle()
    }
}
