package kr.artel.orchestration.gameidentity

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

class GameIdentityMigrationTest {
    @Test
    fun `기존 선언을 LEGACY revision과 원래 build binding으로 backfill한다`() {
        val host = System.getProperty("DB_HOST")
        val port = System.getProperty("DB_PORT")
        val username = System.getProperty("DB_USERNAME")
        val password = System.getProperty("DB_PASSWORD")
        val databaseName = "identity_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val adminUrl = "jdbc:postgresql://$host:$port/${System.getProperty("DB_NAME")}"
        val url = "jdbc:postgresql://$host:$port/$databaseName"
        DriverManager.getConnection(adminUrl, username, password).use { it.createStatement().execute("CREATE DATABASE $databaseName") }
        try {
            Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration").target("173").load().migrate()
            DriverManager.getConnection(url, username, password).use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("INSERT INTO project(id, name, genre) VALUES (1, 'identity', 'ACTION')")
                    sql.execute("INSERT INTO game_build(id, project_id, version) VALUES (1, 1, '1.0'), (2, 1, '2.0')")
                    sql.execute("""INSERT INTO game_identity(project_id, name, kind, base, meaning, changes_when,
                        identifies, role, game_build_id, version, updated_at)
                        VALUES (1, 'Stage.position', 'ordinal', 0, '현재 stage', 'stage 진입', 'stage', 'current', 1, 8, '2026-11-03T00:00:00Z')""")
                }
            }
            Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration").target("175").load().migrate()
            DriverManager.getConnection(url, username, password).use { connection ->
                connection.createStatement().executeQuery("""SELECT r.*, b.game_build_id AS selected_build
                    FROM game_identity_revision r JOIN game_identity_build_binding b ON b.revision_id = r.id""").use { row ->
                    assertThat(row.next()).isTrue()
                    assertThat(row.getString("source")).isEqualTo("LEGACY")
                    assertThat(row.getInt("version")).isEqualTo(8)
                    assertThat(row.getString("meaning")).isEqualTo("현재 stage")
                    assertThat(row.getLong("selected_build")).isEqualTo(1)
                    assertThat(row.getBoolean("deleted")).isFalse()
                    assertThat(row.getTimestamp("created_at").toInstant().toString()).isEqualTo("2026-11-03T00:00:00Z")
                    assertThat(row.next()).isFalse()
                }
            }
        } finally {
            DriverManager.getConnection(adminUrl, username, password).use { it.createStatement().execute("DROP DATABASE IF EXISTS $databaseName WITH (FORCE)") }
        }
    }
}
