package kr.artel.orchestration.support

import org.springframework.test.context.DynamicPropertyRegistry
import java.sql.DriverManager
import java.util.UUID

/**
 * 스위트가 함께 쓰는 Postgres 컨테이너 안에 테스트 클래스 하나만 쓰는 빈 database 를 만든다.
 *
 * "첫 사용자가 ADMIN 이 된다" 는 `app_user` 가 비어 있어야 확인할 수 있는데, 공유 database 의
 * `app_user` 는 다른 클래스의 행이 남아 있고, 그것을 지우면 `qa_try.started_by` 처럼 ON DELETE 절이 없는
 * FK 때문에 실패하거나 남의 테스트 데이터를 지운다. 그래서 이 테스트들은 자기 database 에서 돈다.
 * Flyway 가 컨텍스트를 띄울 때 그 database 에 전체 마이그레이션을 적용한다.
 *
 * 컨테이너 접속 정보는 [PostgresTestContainer] 가 스위트 시작 때 시스템 프로퍼티로 내보낸 값을 읽는다.
 */
object PrivateTestDatabase {

    /** 새 database 를 만들고 그쪽을 가리키도록 R2DBC 와 Flyway 주소를 덮어쓴다. */
    fun register(registry: DynamicPropertyRegistry, prefix: String) {
        val host = System.getProperty("DB_HOST")
        val port = System.getProperty("DB_PORT")
        val username = System.getProperty("DB_USERNAME")
        val password = System.getProperty("DB_PASSWORD")
        val sharedDatabase = System.getProperty("DB_NAME")
        val databaseName = "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        DriverManager.getConnection("jdbc:postgresql://$host:$port/$sharedDatabase", username, password).use {
            it.createStatement().use { statement -> statement.execute("CREATE DATABASE $databaseName") }
        }
        registry.add("spring.r2dbc.url") { "r2dbc:postgresql://$host:$port/$databaseName" }
        registry.add("spring.flyway.url") { "jdbc:postgresql://$host:$port/$databaseName" }
    }
}
