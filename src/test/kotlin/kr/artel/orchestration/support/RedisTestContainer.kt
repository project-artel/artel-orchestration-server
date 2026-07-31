package kr.artel.orchestration.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트 스위트 전체가 함께 쓰는 Redis 컨테이너.
 *
 * SDK 로그인 코드 저장소(`SdkLoginCodeStore`)가 Redis를 요구하므로, 그 빈이 뜨는 모든
 * `@SpringBootTest` 컨텍스트에 Redis가 있어야 한다. [PostgresTestContainer]와 같은 이유로
 * 개발자가 미리 띄워 둔 Redis에 기대지 않는다 — 테스트가 자기 것을 띄운다.
 *
 * `GenericContainer`는 재귀 제네릭이라 Kotlin이 타입을 추론하지 못한다. 그래서 `<*>`를 명시한다.
 *
 * **수명.** JVM당 하나. `stop()`을 부르지 않는 것은 의도적이다 — Testcontainers가 JVM 종료
 * 훅으로 내린다. 자세한 것은 [DockerEnvironment].
 */
object RedisTestContainer {

    /** `GETDEL`은 6.2부터다. 7 계열로 고정해 그 아래로 내려갈 여지를 두지 않는다. */
    private val IMAGE: DockerImageName = DockerImageName.parse("redis:7-alpine")

    private const val REDIS_PORT = 6379

    private val container: GenericContainer<*> by lazy {
        GenericContainer(IMAGE)
            .withExposedPorts(REDIS_PORT)
            .also { it.start() }
    }

    /**
     * 컨테이너를 띄우고 접속 정보를 시스템 프로퍼티로 내보낸다.
     *
     * `application.yml`이 이미 `${REDIS_HOST:localhost}` 형태로 값을 읽으므로, 여기서 그 이름들을
     * 채워 주면 테스트 클래스도 `application-test.yml`도 손대지 않고 컨테이너를 가리킨다.
     * DB가 `DB_HOST`로 하는 것과 정확히 같은 경로다.
     */
    fun startAndExportProperties() {
        System.setProperty("REDIS_HOST", container.host)
        System.setProperty("REDIS_PORT", container.getMappedPort(REDIS_PORT).toString())
    }
}
