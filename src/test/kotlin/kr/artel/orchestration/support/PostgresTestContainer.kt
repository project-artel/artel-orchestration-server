package kr.artel.orchestration.support

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트 스위트 전체가 함께 쓰는 PostgreSQL 컨테이너.
 *
 * **왜 개발자 로컬 DB가 아니라 컨테이너인가.**
 * knowledge 벡터 색인(ARTEL-185)이 `CREATE EXTENSION vector`를 요구하는데, Flyway는 모든
 * `@SpringBootTest`에서 마이그레이션 체인 전체를 돌린다. 즉 pgvector가 없는 DB에서는 벡터 테스트만이
 * 아니라 **기존 통합 테스트 전부가** 깨진다. 개발자가 흔히 띄우는 순정 `postgres` 이미지에는 pgvector가
 * 없으므로, 테스트가 자기 DB를 직접 띄우게 해서 이 의존을 끊는다.
 *
 * 부수 효과로 테스트가 개발자의 가변 로컬 DB를 공유하지 않게 된다.
 *
 * **수명.** JVM당 하나만 띄우고 스위트가 끝날 때까지 재사용한다. 클래스마다 띄우면 마이그레이션
 * 비용을 테스트 클래스 수만큼 낸다. `stop()`을 부르지 않는 것은 의도적이다 — Testcontainers가
 * JVM 종료 훅으로 내린다(Ryuk을 꺼도 마찬가지다. 자세한 것은 [DockerEnvironment]).
 */
object PostgresTestContainer {

    /**
     * 순정 `postgres`가 아니라 pgvector 확장이 미리 들어간 이미지를 쓴다.
     * `asCompatibleSubstituteFor`는 Testcontainers에게 "이건 postgres로 취급해도 된다"고 알리는 것으로,
     * 이것이 없으면 이미지 이름이 `postgres`가 아니라는 이유로 기동을 거부한다.
     */
    private val IMAGE: DockerImageName = DockerImageName
        .parse("pgvector/pgvector:pg16")
        .asCompatibleSubstituteFor("postgres")

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(IMAGE)
            .withDatabaseName("artel_test")
            .withUsername("artel")
            .withPassword("artel")
            .also { it.start() }
    }

    /**
     * 컨테이너를 띄우고 접속 정보를 시스템 프로퍼티로 내보낸다.
     *
     * `application-test.yml`이 이미 `${DB_HOST:localhost}` 형태로 값을 읽으므로, 여기서 그 이름들을
     * 채워 주면 **기존 테스트 클래스를 한 줄도 고치지 않고** 컨테이너로 옮겨 간다.
     * (`@DynamicPropertySource`를 썼다면 테스트 클래스마다 같은 블록을 복사해야 했다.)
     */
    fun startAndExportProperties() {
        System.setProperty("DB_HOST", container.host)
        System.setProperty("DB_PORT", container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT).toString())
        System.setProperty("DB_NAME", container.databaseName)
        System.setProperty("DB_USERNAME", container.username)
        System.setProperty("DB_PASSWORD", container.password)
    }
}
