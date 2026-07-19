package kr.artel.orchestration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

/**
 * 데이터베이스 연결(DataSource) 및 Flyway 스키마 마이그레이션이 정상 적용되었는지 검증하는 통합 테스트
 */
@ActiveProfiles("test")
@SpringBootTest
class DatabaseConnectionTest {

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun testDatabaseConnectionAndFlywayMigration() {
        // 1. DataSource 주입 및 커넥션 풀 객체 존재 검증
        assertThat(dataSource).isNotNull

        // 2. 쿼리 실행을 통한 물리 데이터베이스 커넥션 검증 (SELECT 1)
        val result = jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
        assertThat(result).isEqualTo(1)

        // 3. Flyway V1 마이그레이션으로 생성된 sdk_session_log 및 action_execution_log 테이블 존재 검증
        val sessionLogCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sdk_session_log",
            Int::class.java
        )
        assertThat(sessionLogCount).isEqualTo(0)

        val actionLogCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM action_execution_log",
            Int::class.java
        )
        assertThat(actionLogCount).isEqualTo(0)
    }
}
