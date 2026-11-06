package kr.artel.orchestration.store

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * `ARTEL_STORE=postgres` 면 Redis 자동 구성을 뺀다.
 *
 * 자동 구성을 빼지 않으면 `REDIS_URL` 이 없을 때 기본값 localhost 로 연결 팩토리가 만들어진다.
 * Redis 를 쓰지 않는 배포(desktop, headless)에서 그 연결 팩토리가 곧 불필요한 연결 시도다.
 *
 * `application.yml` 의 `spring.config.activate.on-property` 는 값을 비교하지 못하고 프로퍼티가 있는지만
 * 본다. 그래서 값을 읽는 이 후처리기가 `spring.autoconfigure.exclude` 를 채운다. 이미 지정된 제외
 * 목록은 유지하고 뒤에 붙인다.
 *
 * 기본 `redis` 에서는 아무것도 하지 않는다. 이 이름들은 Spring Boot 3.3 의 클래스다.
 */
class PostgresStoreEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (environment.getProperty(STORE_PROPERTY)?.trim()?.lowercase() != "postgres") return

        val existing = environment.getProperty(EXCLUDE_PROPERTY)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val merged = (existing + REDIS_AUTO_CONFIGURATIONS).distinct().joinToString(",")
        environment.propertySources.addFirst(MapPropertySource(SOURCE_NAME, mapOf(EXCLUDE_PROPERTY to merged)))
    }

    private companion object {
        const val STORE_PROPERTY = "artel.store"
        const val EXCLUDE_PROPERTY = "spring.autoconfigure.exclude"
        const val SOURCE_NAME = "artelPostgresStoreExclusions"

        val REDIS_AUTO_CONFIGURATIONS = listOf(
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        )
    }
}
