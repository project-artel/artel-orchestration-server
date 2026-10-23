package kr.artel.orchestration.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * `artel.store`(`ARTEL_STORE`)에 따라 [ExpiringKeyValueStore] 구현을 하나만 만든다.
 *
 * `postgres` 에서는 Redis 자동 구성을 `PostgresStoreEnvironmentPostProcessor` 가 빼므로 `ReactiveStringRedisTemplate` 이
 * 없다. [redisStore] 가 조건으로 막히는 이유다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(StoreProperties::class)
class StoreConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "artel", name = ["store"], havingValue = "redis", matchIfMissing = true)
    fun redisStore(redis: ReactiveStringRedisTemplate): ExpiringKeyValueStore =
        RedisExpiringKeyValueStore(redis)

    @Bean
    @ConditionalOnProperty(prefix = "artel", name = ["store"], havingValue = "postgres")
    fun postgresStore(databaseClient: DatabaseClient): PostgresExpiringKeyValueStore =
        PostgresExpiringKeyValueStore(databaseClient)

    /** 만료된 행을 주기적으로 지운다. `postgres` 일 때만 뜬다. */
    @Bean
    @ConditionalOnProperty(prefix = "artel", name = ["store"], havingValue = "postgres")
    fun expiringEntrySweeper(store: PostgresExpiringKeyValueStore): ExpiringEntrySweeper =
        ExpiringEntrySweeper(store)

    class ExpiringEntrySweeper(private val store: PostgresExpiringKeyValueStore) {
        private val logger = LoggerFactory.getLogger(ExpiringEntrySweeper::class.java)

        // 빈을 읽을 수 없는 자리라 placeholder 를 쓴다. 기본값은 StoreProperties.storeSweepIntervalMillis 와 같다.
        @Scheduled(
            fixedDelayString = "\${artel.store-sweep-interval-millis:60000}",
            initialDelayString = "\${artel.store-sweep-interval-millis:60000}"
        )
        fun sweep() {
            runBlocking {
                try {
                    val deleted = store.deleteExpired()
                    if (deleted > 0) logger.info("만료된 expiring_entry {}건 삭제", deleted)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // 실패해도 스케줄은 살아 있어야 한다. 만료된 행은 읽히지 않으므로 다음 tick 에 지워도 된다.
                    logger.error("expiring_entry 청소 tick 실패: {}", error.message, error)
                }
            }
        }
    }
}
