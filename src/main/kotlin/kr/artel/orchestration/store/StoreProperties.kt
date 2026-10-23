package kr.artel.orchestration.store

import org.springframework.boot.context.properties.ConfigurationProperties

/** `ARTEL_STORE` 로 고르는 저장소 종류. */
enum class StoreType { REDIS, POSTGRES }

/**
 * 만료가 있는 키-값 저장소를 Redis 에 둘지 PostgreSQL 에 둘지 정한다.
 *
 * `artel.store`(환경 변수 `ARTEL_STORE`)는 `redis` 가 기본이라 기존 배포는 그대로다. `postgres` 면
 * Redis 연결을 열지 않고 `REDIS_URL` 도 필요 없다.
 *
 * `@ConditionalOnProperty` 가 같은 키를 문자열로 읽는다. 빈을 읽을 수 없는 자리라서이며, 기본값
 * (`redis`)은 이 클래스와 [StoreConfiguration] 의 `matchIfMissing` 양쪽에서 같이 움직여야 한다.
 * [PostgresStoreEnvironmentPostProcessor] 도 같은 키를 본다.
 */
@ConfigurationProperties("artel")
data class StoreProperties(
    val store: StoreType = StoreType.REDIS,
    /** `postgres` 일 때 만료된 행을 지우는 간격. 만료된 행은 지우기 전에도 읽히지 않으므로 정확하지 않아도 된다. */
    val storeSweepIntervalMillis: Long = 60_000
) {
    init {
        require(storeSweepIntervalMillis > 0) { "artel.store-sweep-interval-millis 는 1 이상이어야 합니다." }
    }
}
