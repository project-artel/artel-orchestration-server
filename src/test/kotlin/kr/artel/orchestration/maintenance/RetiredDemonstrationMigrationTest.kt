package kr.artel.orchestration.maintenance

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.storage.RetiredStorageObject
import kr.artel.orchestration.testscenario.dto.AgentCase
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.sql.DriverManager
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetiredDemonstrationMigrationTest {
    private class CleanupStorage : DocumentStorage by mock(DocumentStorage::class.java) {
        var listed: Flux<RetiredStorageObject> = Flux.empty()
        var onDelete: (RetiredStorageObject) -> Mono<Void> = { Mono.empty() }
        override fun retiredDemonstrationObjects() = listed
        override fun deleteRetiredDemonstrationObject(obj: RetiredStorageObject) = onDelete(obj)
    }
    private val host = System.getProperty("DB_HOST")
    private val port = System.getProperty("DB_PORT")
    private val user = System.getProperty("DB_USERNAME")
    private val password = System.getProperty("DB_PASSWORD")
    private val database = "retired_demo_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    private val url get() = "jdbc:postgresql://$host:$port/$database"
    private fun connection() = DriverManager.getConnection(url,user,password)
    private fun admin() = DriverManager.getConnection("jdbc:postgresql://$host:$port/${System.getProperty("DB_NAME")}",user,password)
    private fun flyway(target: String) = Flyway.configure().dataSource(url,user,password)
        .locations("classpath:db/migration").target(target).load()

    @BeforeAll
    fun setup() {
        admin().use { it.createStatement().execute("CREATE DATABASE $database") }
        flyway("147").migrate()
        connection().use { c -> c.createStatement().use { s ->
            s.execute("SET session_replication_role = replica")
            s.execute("""INSERT INTO demonstration_session(id,project_id,test_case_id,scenario_id,game_instance_id,game_build_id,owner_id,generation,sdk_connection_id,case_json,case_hash,status,lease_expires_at)
                VALUES('00000000-0000-0000-0000-000000000001',1,1,1,1,1,1,1,'old','{}','old','SEALED',now())""")
            s.execute("""INSERT INTO demonstration_event(session_id,kind,payload_json) VALUES('00000000-0000-0000-0000-000000000001','EVIDENCE','{"objectKey":"demonstrations/old/evidence.png"}')""")
            s.execute("""INSERT INTO knowledge(id,project_id,source,tag,summary,description) VALUES(9001,1,'DEMONSTRATION','MISC','old','old'),(9002,1,'QA','MISC','keep','keep')""")
            s.execute("""INSERT INTO knowledge_event(knowledge_id,project_id,event,version,after) VALUES(9001,1,'CREATE',1,'{"description":"old"}')""")
            s.execute("INSERT INTO knowledge_embedding(knowledge_id,kind,model) VALUES(9001,'CONTENT','test')")
            s.execute("INSERT INTO knowledge_usage(knowledge_id,qa_try_id,knowledge_version) VALUES(9001,1,1)")
            s.execute("INSERT INTO knowledge_anchor(knowledge_id,scene_name) VALUES(9001,'test')")
            s.execute("""INSERT INTO qa_case_attempt(id,qa_try_id,test_case_id,project_id,case_snapshot)
                VALUES('00000000-0000-0000-0000-000000000002',1,1,1,'{"expected":"keep","verification_procedure":{"secret":"old"}}')""")
            s.execute("""INSERT INTO qa_video_segment(id,recording_id,started_at,ended_at,object_key,size_bytes)
                VALUES('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004',now(),now(),'qa-recordings/keep.webm',10)""")
        } }
        flyway("148").migrate()
    }

    @AfterAll
    fun teardown() { admin().use { it.createStatement().execute("DROP DATABASE IF EXISTS $database WITH (FORCE)") } }

    private fun scalar(sql: String): String = connection().use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getString(1) } } }

    @Test
    fun `시연 데이터와 연결 정보를 삭제하고 QA 영상과 일반 knowledge는 유지한다`() {
        assertThat(scalar("SELECT count(*) FROM knowledge WHERE id=9001")).isEqualTo("0")
        for(table in listOf("knowledge_event","knowledge_embedding","knowledge_usage","knowledge_anchor")) {
            assertThat(scalar("SELECT count(*) FROM $table WHERE knowledge_id=9001")).isEqualTo("0")
        }
        assertThat(scalar("SELECT count(*) FROM knowledge WHERE id=9002")).isEqualTo("1")
        assertThat(scalar("SELECT count(*) FROM qa_video_segment WHERE object_key='qa-recordings/keep.webm'")).isEqualTo("1")
        assertThat(scalar("SELECT case_snapshot->>'expected' FROM qa_case_attempt LIMIT 1")).isEqualTo("keep")
        assertThat(scalar("SELECT count(*) FROM qa_case_attempt WHERE case_snapshot ? 'verification_procedure'")).isEqualTo("0")
        assertThat(scalar("SELECT count(*) FROM retired_demonstration_object WHERE object_key='demonstrations/old/evidence.png'")).isEqualTo("1")
        assertThat(scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('demonstration_session','demonstration_event','verification_procedure','qa_procedure_usage')")).isEqualTo("0")
        assertThat(flyway("148").migrate().migrationsExecuted).isZero()
    }

    @Test
    fun `객체 삭제 실패는 queue에 남기고 재시도하며 version과 orphan도 삭제한다`(): Unit = runBlocking {
        val storage = CleanupStorage()
        val orphan = RetiredStorageObject("demonstrations/orphan/frame.png","version-1")
        storage.listed = Flux.just(orphan)
        val deleted = mutableListOf<RetiredStorageObject>()
        var fail = true
        storage.onDelete = { obj ->
            if(fail && obj == orphan) Mono.error<Void>(IllegalStateException("temporary storage failure"))
            else { deleted.add(obj); Mono.empty<Void>() }
        }
        val db = DatabaseClient.create(PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
            .host(host).port(port.toInt()).database(database).username(user).password(password).build()))
        val cleanup = RetiredDemonstrationCleanup(db,storage)
        cleanup.cleanup()
        assertThat(scalar("SELECT count(*) FROM retired_demonstration_object WHERE object_key='${orphan.key}' AND deleted_at IS NULL")).isEqualTo("1")
        fail = false
        cleanup.cleanup()
        assertThat(deleted).contains(orphan)
        assertThat(deleted.all { it.key.startsWith("demonstrations/") }).isTrue()
        assertThat(scalar("SELECT count(*) FROM retired_demonstration_object WHERE deleted_at IS NULL")).isEqualTo("0")
        val count = deleted.size
        storage.listed = Flux.empty()
        cleanup.cleanup()
        assertThat(deleted).hasSize(count)
    }

    @Test
    fun `계속 실패하는 첫 500개 때문에 다음 객체 삭제가 밀리지 않는다`(): Unit = runBlocking {
        connection().use { c -> c.createStatement().execute("""INSERT INTO retired_demonstration_object(object_key)
            SELECT 'demonstrations/fair/'||lpad(n::text,4,'0') FROM generate_series(1,501) AS n""") }
        try {
            val storage = CleanupStorage()
            val attempted = mutableSetOf<String>()
            storage.listed = Flux.empty()
            storage.onDelete = { obj ->
                attempted.add(obj.key)
                Mono.error<Void>(IllegalStateException("temporary storage failure"))
            }
            val db = DatabaseClient.create(PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(host).port(port.toInt()).database(database).username(user).password(password).build()))
            val cleanup = RetiredDemonstrationCleanup(db,storage)
            cleanup.cleanup()
            cleanup.cleanup()
            assertThat(attempted).contains("demonstrations/fair/0501")
        } finally {
            connection().use { c -> c.createStatement().execute("DELETE FROM retired_demonstration_object WHERE object_key LIKE 'demonstrations/fair/%'") }
        }
    }
    @Test
    fun `중첩 snapshot의 시연 필드만 제거하고 배열 순서와 일반 값을 보존한다`() {
        val result = scalar("""SELECT remove_retired_demonstration_fields('{"steps":[{"case":{"verification_procedure":{"old":1},"expected":"first"}},{"verificationProcedure":{"old":2},"expected":"second"}],"note":"verification_procedure"}'::jsonb)::text""")
        val json = jacksonObjectMapper().readTree(result)
        assertThat(json["steps"][0]["case"].has("verification_procedure")).isFalse()
        assertThat(json["steps"][0]["case"]["expected"].asText()).isEqualTo("first")
        assertThat(json["steps"][1].has("verificationProcedure")).isFalse()
        assertThat(json["steps"][1]["expected"].asText()).isEqualTo("second")
        assertThat(json["note"].asText()).isEqualTo("verification_procedure")
    }
    @Test
    fun `이전 snapshot의 승인 절차를 agent 계약에서 무시한다`() {
        val mapper = jacksonObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        val result = mapper.readValue("""{"id":1,"scene":"test","precondition":null,"test_step":"go","expected":"keep","verification_procedure":{"old":"discard"}}""",AgentCase::class.java)
        assertThat(result.expected).isEqualTo("keep")
        assertThat(mapper.writeValueAsString(result)).doesNotContain("verification_procedure")
    }
}