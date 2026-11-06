package kr.artel.orchestration.testcase

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestCaseIdentifierMigrationTest {
    private val host = System.getProperty("DB_HOST")
    private val port = System.getProperty("DB_PORT")
    private val username = System.getProperty("DB_USERNAME")
    private val password = System.getProperty("DB_PASSWORD")
    private val databaseName = "tc_numbers_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    private val url get() = "jdbc:postgresql://$host:$port/$databaseName"

    @BeforeAll
    fun migrateExistingAndConcurrentCases() {
        admin().use { it.createStatement().execute("CREATE DATABASE $databaseName") }
        val previous = flyway(MigrationVersion.LATEST).info().all().map { it.version }
            .filter { it < MigrationVersion.fromVersion("147.1") }.max()
        flyway(previous).migrate()
        connection().use { connection ->
            // 과거 QA 기록의 source만 구성한다. 번호 migration의 실제 FK와 trigger는 끄지 않는다.
            connection.createStatement().use { sql ->
                sql.execute("SET session_replication_role = replica")
                sql.execute("INSERT INTO project(id, name, genre) VALUES (1, 'A', 'RPG'), (2, 'B', 'RPG')")
                sql.execute("INSERT INTO test_case(id, project_id, scene, step, expected_value) VALUES (20, 1, 'S', 'A', 'x'), (30, 2, 'S', 'B', 'x'), (50, 1, 'S', 'A2', 'x')")
                sql.execute("INSERT INTO test_scenario(id, project_id) VALUES (1, 1), (2, 2)")
                sql.execute("""INSERT INTO qa_try(id, test_scenario_id, game_instance_id, started_by, status, started_at, completed_at)
                    VALUES (1, 1, 1, 1, 'COMPLETED', now(), now()), (2, 2, 1, 1, 'COMPLETED', now(), now())""")
                sql.execute("""INSERT INTO qa_case_attempt(id, qa_try_id, test_case_id, project_id, case_snapshot, finished_at, verdict)
                    VALUES ('00000000-0000-0000-0000-000000000001', 1, 10, 1, '{}', now(), 'PASSED')""")
                for ((tryId, rawId) in listOf(1 to "15", 1 to "00015", 1 to "30", 2 to "50", 1 to "40", 2 to "40",
                    1 to "9223372036854775808", 1 to "not-a-case", 1 to "-1", 1 to "0", 1 to "999999999999999999999999")) {
                    sql.execute("INSERT INTO qa_log(qa_try_id, direction, type, payload) VALUES ($tryId, 'ORCHE_INTERNAL', 'LOG', '{\"case_id\":\"$rawId\"}')")
                }
                sql.execute("SET session_replication_role = origin")
            }
        }

        // migration이 기다리는 동안 들어온 INSERT도 backfill 이후 trigger를 통과해야 한다.
        val workers = Executors.newFixedThreadPool(2)
        try {
            connection().use { blocker ->
                blocker.autoCommit = false
                blocker.createStatement().execute("LOCK TABLE test_case IN SHARE ROW EXCLUSIVE MODE")
                val migration = workers.submit { flyway(MigrationVersion.fromVersion("147.1")).migrate() }
                await().atMost(Duration.ofSeconds(30)).until { waitingWriters() >= 1 }
                val writer = workers.submit {
                    connection().use { it.createStatement().execute("INSERT INTO test_case(id, project_id, scene, step, expected_value) VALUES (60, 1, 'S', '동시 생성', 'x')") }
                }
                await().atMost(Duration.ofSeconds(30)).until { waitingWriters() >= 2 }
                blocker.commit()
                migration.get(60, TimeUnit.SECONDS)
                writer.get(60, TimeUnit.SECONDS)
            }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `현재 TC와 삭제 TC의 유효한 과거 기록을 내부 ID 순서로 채운다`() {
        assertThat(rows()).containsExactly(
            Triple(10L, 1L, 1L), Triple(15L, 1L, 2L), Triple(20L, 1L, 3L),
            Triple(50L, 1L, 4L), Triple(60L, 1L, 5L), Triple(30L, 2L, 1L))
    }

    @Test
    fun `현재 소유 프로젝트를 우선하고 모호하거나 잘못된 과거 ID에는 번호를 만들지 않는다`() {
        val rows = rows().associateBy { it.first }
        assertThat(rows.getValue(30).second).isEqualTo(2)
        assertThat(rows.getValue(50).second).isEqualTo(1)
        assertThat(rows.keys).doesNotContain(40L, 0L, -1L)
        assertThat(rows).hasSize(6)
    }

    @Test
    fun `backfill 중 대기한 INSERT도 초기화된 counter의 다음 번호를 받는다`() {
        assertThat(rows().single { it.first == 60L }).isEqualTo(Triple(60L, 1L, 5L))
        connection().use { connection ->
            connection.createStatement().executeQuery("SELECT last_number FROM test_case_number_counter WHERE project_id = 1").use {
                assertThat(it.next()).isTrue()
                assertThat(it.getLong(1)).isEqualTo(5L)
            }
        }
    }

    @AfterAll
    fun dropDatabase() {
        admin().use { it.createStatement().execute("DROP DATABASE IF EXISTS $databaseName WITH (FORCE)") }
    }

    private fun rows(): List<Triple<Long, Long, Long>> = connection().use { connection ->
        connection.createStatement().executeQuery("SELECT test_case_id, project_id, case_number FROM test_case_identifier ORDER BY project_id, case_number").use { result ->
            buildList { while (result.next()) add(Triple(result.getLong(1), result.getLong(2), result.getLong(3))) }
        }
    }
    private fun waitingWriters(): Int = connection().use { connection ->
        connection.createStatement().executeQuery("SELECT count(*) FROM pg_locks WHERE relation = 'test_case'::regclass AND NOT granted").use {
            it.next(); it.getInt(1)
        }
    }
    private fun flyway(target: MigrationVersion) = Flyway.configure().dataSource(url, username, password)
        .locations("classpath:db/migration").target(target).load()
    private fun connection(): Connection = DriverManager.getConnection(url, username, password)
    private fun admin(): Connection = DriverManager.getConnection("jdbc:postgresql://$host:$port/${System.getProperty("DB_NAME")}", username, password)
}
