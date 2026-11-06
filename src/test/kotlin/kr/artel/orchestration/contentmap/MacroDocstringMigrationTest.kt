package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** 이 migration 의 version. 그 바로 앞 version 까지 적용한 database 에 행을 넣는다. */
private val DOCSTRING_MIGRATION: MigrationVersion = MigrationVersion.fromVersion("101")
private val FIXED_UPDATED_AT: OffsetDateTime = OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)

/**
 * V101 이 진입점 `def` 본문의 `#` 요약 줄을 docstring 으로 옮기는지 본다(ARTEL-985).
 *
 * 두 가지를 본다.
 *
 * 1. **`move_macro_summary_comment_into_docstring` 의 결과 `source` 를 글자 그대로** 비교한다. 경계
 *    경우가 SQL 함수 안의 줄 고르기 규칙에 달려 있어, 행을 하나씩 넣고 migration 을 다시 돌리는 것보다
 *    함수를 직접 부르는 편이 경우마다 무엇이 바뀌는지 바로 보인다
 * 2. **이미 있는 행이 V101 을 지나며 바뀌는 모양.** V101 바로 앞까지 적용한 database 에 행을 넣고 나머지를
 *    적용한다. `updated_at` 이 그대로인지, 백업 표에 원본이 남는지, `definition_json` 의 두 칸이
 *    맞춰지는지를 본다. 함수만 보면 `UPDATE` 문이 그 결과를 제대로 적는지는 모른다
 *
 * Spring context 를 띄우지 않는다. 공유 database 는 컨텍스트가 뜰 때 이미 끝까지 migrate 되어 있어
 * "V101 전의 행" 을 만들 수 없다. 그래서 자기 database 를 만들고 Flyway 를 직접 두 번 돌린다.
 *
 * `content_map` 부터 `project` 까지 FK 를 채우는 대신 `session_replication_role = replica` 로 FK
 * trigger 를 끄고 `macro` 행만 넣는다. 이 테스트가 보는 것은 `macro` 한 표이고, CHECK 제약은 그 설정과
 * 상관없이 그대로 걸린다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MacroDocstringMigrationTest {

    private val objectMapper = ObjectMapper()
    private val host = System.getProperty("DB_HOST")
    private val port = System.getProperty("DB_PORT")
    private val username = System.getProperty("DB_USERNAME")
    private val password = System.getProperty("DB_PASSWORD")
    private val databaseName = "macro_docstring_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    private val url get() = "jdbc:postgresql://$host:$port/$databaseName"

    private var commentedMacroId: Long = 0
    private var plainMacroId: Long = 0

    @BeforeAll
    fun migrateAroundExistingRows() {
        adminConnection().use { it.createStatement().execute("CREATE DATABASE $databaseName") }

        // Flyway 의 `target` 은 있는 version 이어야 한다. V99 · V100 이 다른 branch 에서 들어올 수 있어
        // 번호를 적지 않고 V101 바로 앞 version 을 찾는다.
        val previousVersion = flyway(MigrationVersion.LATEST).info().all()
            .map { it.version }
            .filter { it < DOCSTRING_MIGRATION }
            .max()
        flyway(previousVersion).migrate()
        connection().use { connection ->
            connection.createStatement().execute("SET session_replication_role = replica")
            commentedMacroId = insertMacro(connection, "go", COMMENTED_SOURCE)
            plainMacroId = insertMacro(connection, "plain", PLAIN_SOURCE)
        }
        flyway(MigrationVersion.LATEST).migrate()
    }

    @AfterAll
    fun dropDatabase() {
        adminConnection().use { it.createStatement().execute("DROP DATABASE IF EXISTS $databaseName WITH (FORCE)") }
    }

    @Test
    fun `본문 첫 줄의 주석이 signature 바로 아래 docstring 이 된다`() {
        assertRewrite(
            "def go(card):\n    # Cast the card\n    click(card)\n",
            "def go(card):\n    \"\"\"Cast the card\"\"\"\n    click(card)\n",
            "Cast the card",
        )
    }

    /** 사용자 결정(2026-10-13): statement 뒤의 주석도 옮긴다. 지금 보이는 요약을 그대로 남기려는 것이다. */
    @Test
    fun `statement 뒤에 있는 주석도 signature 바로 아래로 옮긴다`() {
        assertRewrite(
            "def go():\n    click(1)\n    # Then wait\n    wait(2)\n",
            "def go():\n    \"\"\"Then wait\"\"\"\n    click(1)\n    wait(2)\n",
            "Then wait",
        )
    }

    @Test
    fun `주석이 없으면 바꾸지 않는다`() {
        assertUnchanged("def go():\n    click(1)\n    wait(2)\n")
    }

    @Test
    fun `글자 없는 주석 줄은 건너뛰고 남겨 둔다`() {
        assertRewrite(
            "def go():\n    #\n    #   \n    # Real one\n    click(1)\n",
            "def go():\n    \"\"\"Real one\"\"\"\n    #\n    #   \n    click(1)\n",
            "Real one",
        )
    }

    @Test
    fun `닫는 괄호가 따로 선 여러 줄 signature 뒤에 넣는다`() {
        assertRewrite(
            "def go(\n    card: object,\n    count: int = 2,\n):\n    # Many\n    click(card)\n",
            "def go(\n    card: object,\n    count: int = 2,\n):\n    \"\"\"Many\"\"\"\n    click(card)\n",
            "Many",
        )
    }

    @Test
    fun `진입점 앞의 helper 는 건드리지 않는다`() {
        assertRewrite(
            "def helper():\n    # helper note\n    click(0)\n\n\ndef go():\n    # Entry note\n    helper()\n",
            "def helper():\n    # helper note\n    click(0)\n\n\ndef go():\n    \"\"\"Entry note\"\"\"\n    helper()\n",
            "Entry note",
        )
    }

    /** 진입점 본문은 들여쓰지 않은 다음 `def` 에서 끝난다. 그 뒤 helper 의 주석은 요약이 아니다. */
    @Test
    fun `진입점 뒤 helper 의 주석은 가져오지 않는다`() {
        assertUnchanged("def go():\n    helper()\n\ndef helper():\n    # helper note\n    click(0)\n")
    }

    /**
     * `\` 와 `"` 를 escape 해 넣는다. 주석에 `"""` 가 있거나 글자가 `\` 로 끝나도 Python 문자열로
     * 파싱되어야 한다. `docstring` 칸에는 escape 하기 전 글자가 간다.
     */
    @Test
    fun `따옴표와 backslash 를 escape 해 넣는다`() {
        assertRewrite(
            "def go():\n    # Say \"\"\"hi\"\"\" and \"q\" \\ path\\\n    click(1)\n",
            "def go():\n    \"\"\"Say \\\"\\\"\\\"hi\\\"\\\"\\\" and \\\"q\\\" \\\\ path\\\\\"\"\"\n    click(1)\n",
            "Say \"\"\"hi\"\"\" and \"q\" \\ path\\",
        )
    }

    @Test
    fun `CRLF 원본은 CRLF 로 남는다`() {
        assertRewrite(
            "def go():\r\n    # Win\r\n    click(1)\r\n",
            "def go():\r\n    \"\"\"Win\"\"\"\r\n    click(1)\r\n",
            "Win",
        )
    }

    @Test
    fun `끝 줄바꿈이 없던 원본은 없는 채로 남는다`() {
        assertRewrite(
            "def go():\n    # Last\n    click(1)",
            "def go():\n    \"\"\"Last\"\"\"\n    click(1)",
            "Last",
        )
    }

    /** docstring 은 statement 라 다음 statement 와 들여쓰기가 같아야 한다. 주석 줄의 들여쓰기는 상관없다. */
    @Test
    fun `들여쓰기는 주석 줄이 아니라 첫 statement 를 따른다`() {
        assertRewrite(
            "def go():\n        # Deep comment\n    click(1)\n",
            "def go():\n    \"\"\"Deep comment\"\"\"\n    click(1)\n",
            "Deep comment",
        )
    }

    /** 첫 statement 가 이미 문자열이면 docstring 이 있는 것이다. 둘째 문자열을 넣으면 parser 가 거절한다. */
    @Test
    fun `이미 docstring 이 있으면 바꾸지 않는다`() {
        assertUnchanged("def go():\n    \"\"\"Already.\"\"\"\n    # A comment\n    click(1)\n")
        assertUnchanged("def go():\n    # A comment\n    '''Already.'''\n    click(1)\n")
    }

    @Test
    fun `이미 있던 행은 source 와 docstring 과 tree 가 바뀌고 updated_at 은 그대로다`() {
        val row = readMacro(commentedMacroId)

        assertThat(row.source).isEqualTo(COMMENTED_REWRITTEN)
        assertThat(row.docstring).isEqualTo("Cast the card at the enemy")
        val definition = objectMapper.readTree(row.definitionJson)
        assertThat(definition.path("entry").path("docstring").asText()).isEqualTo("Cast the card at the enemy")
        assertThat(definition.path("source").asText())
            .describedAs("tree 가 들고 있는 원문 사본도 같이 맞춘다")
            .isEqualTo(COMMENTED_REWRITTEN)
        assertThat(definition.path("entry").path("statements").size())
            .describedAs("docstring 은 statement 목록에 더하지 않는다")
            .isEqualTo(1)
        assertThat(row.updatedAt.toInstant())
            .describedAs("migration 은 정의를 고친 것이 아니라 updated_at 을 움직이지 않는다")
            .isEqualTo(FIXED_UPDATED_AT.toInstant())

        val backup = readBackup(commentedMacroId)
        assertThat(backup?.first).isEqualTo(COMMENTED_SOURCE)
        assertThat(objectMapper.readTree(backup?.second).path("source").asText()).isEqualTo(COMMENTED_SOURCE)
    }

    @Test
    fun `주석이 없는 행은 그대로 두고 백업하지 않는다`() {
        val row = readMacro(plainMacroId)

        assertThat(row.source).isEqualTo(PLAIN_SOURCE)
        assertThat(row.docstring).isNull()
        assertThat(objectMapper.readTree(row.definitionJson).path("entry").has("docstring")).isFalse()
        assertThat(row.updatedAt.toInstant()).isEqualTo(FIXED_UPDATED_AT.toInstant())
        assertThat(readBackup(plainMacroId)).isNull()
    }

    private fun assertRewrite(source: String, expectedSource: String, expectedDocstring: String) {
        val (rewritten, docstring) = rewrite(source)
        assertThat(rewritten).isEqualTo(expectedSource)
        assertThat(docstring).isEqualTo(expectedDocstring)
    }

    private fun assertUnchanged(source: String) {
        assertThat(rewrite(source)).isEqualTo(null to null)
    }

    private fun rewrite(source: String, entryName: String = "go"): Pair<String?, String?> =
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT rewritten_source, docstring FROM move_macro_summary_comment_into_docstring(?, ?)"
            ).use { statement ->
                statement.setString(1, source)
                statement.setString(2, entryName)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getString(1) to result.getString(2)
                }
            }
        }

    private fun insertMacro(connection: Connection, name: String, source: String): Long {
        val definition = objectMapper.createObjectNode().apply {
            put("name", name)
            put("source", source)
            putObject("entry").apply {
                put("name", name)
                putArray("parameters")
                putArray("statements").addObject().put("kind", "call")
            }
            putArray("helpers")
        }
        return connection.prepareStatement(
            """
            INSERT INTO macro (content_map_id, name, source, definition_json, parameter_names, created_at, updated_at)
            VALUES (1, ?, ?, ?::jsonb, '[]'::jsonb, ?, ?)
            RETURNING id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, source)
            statement.setString(3, objectMapper.writeValueAsString(definition))
            statement.setObject(4, FIXED_UPDATED_AT)
            statement.setObject(5, FIXED_UPDATED_AT)
            statement.executeQuery().use { result ->
                result.next()
                result.getLong(1)
            }
        }
    }

    private fun readMacro(macroId: Long): StoredMacro = connection().use { connection ->
        connection.prepareStatement(
            "SELECT source, docstring, definition_json::text, updated_at FROM macro WHERE id = ?"
        ).use { statement ->
            statement.setLong(1, macroId)
            statement.executeQuery().use { result ->
                result.next()
                StoredMacro(
                    source = result.getString(1),
                    docstring = result.getString(2),
                    definitionJson = result.getString(3),
                    updatedAt = result.getObject(4, OffsetDateTime::class.java),
                )
            }
        }
    }

    private fun readBackup(macroId: Long): Pair<String, String>? = connection().use { connection ->
        connection.prepareStatement(
            "SELECT source, definition_json::text FROM macro_source_before_docstring WHERE macro_id = ?"
        ).use { statement ->
            statement.setLong(1, macroId)
            statement.executeQuery().use { result ->
                if (result.next()) result.getString(1) to result.getString(2) else null
            }
        }
    }

    private fun flyway(target: MigrationVersion): Flyway = Flyway.configure()
        .dataSource(url, username, password)
        .locations("classpath:db/migration")
        .target(target)
        .load()

    private fun connection(): Connection = DriverManager.getConnection(url, username, password)

    private fun adminConnection(): Connection =
        DriverManager.getConnection("jdbc:postgresql://$host:$port/${System.getProperty("DB_NAME")}", username, password)

    private data class StoredMacro(
        val source: String,
        val docstring: String?,
        val definitionJson: String,
        val updatedAt: OffsetDateTime,
    )

    private companion object {
        const val COMMENTED_SOURCE = "def go(card: object):\n    # Cast the card at the enemy\n    click(card)\n"
        const val COMMENTED_REWRITTEN = "def go(card: object):\n    \"\"\"Cast the card at the enemy\"\"\"\n    click(card)\n"
        const val PLAIN_SOURCE = "def plain():\n    click(selector(\"Canvas/Button\"))\n"
    }
}
