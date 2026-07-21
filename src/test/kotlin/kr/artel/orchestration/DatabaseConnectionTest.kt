package kr.artel.orchestration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles

/**
 * R2DBC 커넥션 및 Flyway(JDBC) 스키마 마이그레이션이 정상 적용되어, R2DBC 쿼리가 마이그레이션 결과를
 * 조회할 수 있는지 검증하는 통합 테스트. (Flyway는 JDBC로 실행되고 앱은 R2DBC로 접근하므로 둘이 동일한
 * 인메모리 DB를 공유하는지도 함께 확인된다.)
 */
@ActiveProfiles("test")
@SpringBootTest
class DatabaseConnectionTest {

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Test
    fun testR2dbcConnectionAndFlywayMigration() {
        // 1. R2DBC 커넥션으로 쿼리 실행 검증
        val one = databaseClient.sql("SELECT 1")
            .map { row -> row.get(0, Integer::class.java) }
            .one()
            .block()
        assertThat(one).isEqualTo(1)

        // 2. Flyway 마이그레이션으로 생성된 테이블을 R2DBC로 조회할 수 있는지 검증
        //    (인메모리 DB가 다른 테스트와 공유되므로 정확한 건수 대신 조회 가능 여부만 확인한다)
        val tables = listOf("sdk_session_log", "action_execution_log", "oauth_user")
        for (table in tables) {
            val count = databaseClient.sql("SELECT COUNT(*) FROM $table")
                .map { row -> row.get(0, java.lang.Long::class.java) }
                .one()
                .block()
            assertThat(count).isNotNull
            assertThat(count!!.toLong()).isGreaterThanOrEqualTo(0L)
        }
    }
}
