package kr.artel.orchestration.store

import java.time.Duration

/**
 * TTL 이 붙은 문자열 키-값 저장소. `SdkLoginCodeStore` 와 `TrackerInstallationChoiceStore` 가 쓴다.
 *
 * 구현은 둘이다. [RedisExpiringKeyValueStore] 는 기본이고, `ARTEL_STORE=postgres` 면
 * [PostgresExpiringKeyValueStore] 가 대신한다. 두 구현은 같은 의미를 지킨다 — 만료된 키는 읽히지
 * 않고, [getAndDelete] 는 읽기와 삭제가 한 번에 일어나 같은 값을 두 호출자가 받을 수 없다.
 *
 * 저장소 장애는 예외로 그대로 올라간다. 호출부가 null 로 오해하지 않게 하려는 판단이다.
 */
interface ExpiringKeyValueStore {
    /** 같은 키는 덮어쓰고 TTL 도 새로 센다. */
    suspend fun set(key: String, value: String, ttl: Duration)

    /** 없거나 만료됐으면 null. */
    suspend fun get(key: String): String?

    /** 읽고 지운다. 없거나 만료됐으면 null. 동시에 부르면 한 호출자만 값을 받는다. */
    suspend fun getAndDelete(key: String): String?

    suspend fun delete(key: String)
}
