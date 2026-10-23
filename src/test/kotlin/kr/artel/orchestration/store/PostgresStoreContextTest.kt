package kr.artel.orchestration.store

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * `ARTEL_STORE=postgres` 로 컨텍스트가 뜨는지 본다. `REDIS_URL` 은 아무도 듣지 않는 포트로 돌려
 * 두었으므로, Redis 에 연결을 시도하는 빈이 하나라도 있으면 아래 검사가 아니라 기동에서 먼저 드러난다.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = ["artel.store=postgres", "REDIS_URL=redis://127.0.0.1:1"])
class PostgresStoreContextTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `Redis 빈 없이 postgres 저장소로 뜬다`() {
        assertThat(context.getBeansOfType(ReactiveRedisConnectionFactory::class.java)).isEmpty()
        assertThat(context.getBeansOfType(ReactiveStringRedisTemplate::class.java)).isEmpty()
        assertThat(context.getBean(ExpiringKeyValueStore::class.java))
            .isInstanceOf(PostgresExpiringKeyValueStore::class.java)
        assertThat(context.getBeansOfType(StoreConfiguration.ExpiringEntrySweeper::class.java)).hasSize(1)
    }
}
