package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.util.UUID

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestCaseIdentifiersIntegrationTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var database: DatabaseClient
    @Autowired private lateinit var cases: TestCaseRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var users: OAuthUserService
    @Autowired private lateinit var jwt: JwtService
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var identifiers: TestCaseIdentifiers
    @Autowired private lateinit var builds: GameBuildRepository
    @Autowired private lateinit var versions: TestCaseVersionRepository

    @Test
    fun `프로젝트마다 1부터 번호를 부여하고 API는 내부 ID와 번호를 문자열로 반환한다`(): Unit = runBlocking {
        val owner = owner()
        val projectA = project(owner.id)
        val projectB = project(owner.id)
        val first = newCase(projectA)
        val second = newCase(projectA)
        val other = newCase(projectB)
        assertThat(identifiers.list(projectA, owner.id)).containsExactly(
            TestCaseIdentifierResponse(first.id.toString(), "1"), TestCaseIdentifierResponse(second.id.toString(), "2"))
        assertThat(identifiers.list(projectB, owner.id)).containsExactly(TestCaseIdentifierResponse(other.id.toString(), "1"))
        val response = get(projectA, owner.token)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body[0]["testCaseId"].isTextual).isTrue()
        assertThat(response.body[0]["caseNumber"].isTextual).isTrue()
        assertThat(response.body[0].fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("testCaseId", "caseNumber")
    }

    @Test
    fun `동시 생성한 TC 번호는 프로젝트 안에서 중복되지 않는다`(): Unit = runBlocking {
        val owner = owner()
        val projectId = project(owner.id)
        coroutineScope { (1..24).map { async { newCase(projectId) } }.awaitAll() }
        val rows = identifiers.list(projectId, owner.id)
        assertThat(rows.map { it.testCaseId }.toSet()).hasSize(24)
        assertThat(rows.map { it.caseNumber.toLong() }).containsExactlyElementsOf((1L..24L).toList())
    }

    @Test
    fun `삭제한 TC 번호는 조회에 남고 새 TC는 다음 번호를 받는다`(): Unit = runBlocking {
        val owner = owner()
        val projectId = project(owner.id)
        val first = newCase(projectId)
        val second = newCase(projectId)
        cases.delete(first)
        val third = newCase(projectId)
        assertThat(identifiers.list(projectId, owner.id)).containsExactly(
            TestCaseIdentifierResponse(first.id.toString(), "1"), TestCaseIdentifierResponse(second.id.toString(), "2"),
            TestCaseIdentifierResponse(third.id.toString(), "3"))
        assertThat(get(projectId, owner.token).body.map { it["testCaseId"].asText() }).contains(first.id.toString())
    }

    @Test
    fun `동일 ID 재전송은 번호를 유지하고 ID 변경과 프로젝트 이동 및 삭제 ID 재사용은 거절한다`(): Unit = runBlocking {
        val owner = owner()
        val projectId = project(owner.id)
        val otherProject = project(owner.id)
        val row = newCase(projectId)
        database.sql("""INSERT INTO test_case(id, project_id, scene, step, expected_value)
            VALUES (:id, :project, 'S', '재전송', 'x') ON CONFLICT (id) DO UPDATE SET step = EXCLUDED.step""")
            .bind("id", row.id!!).bind("project", projectId).fetch().awaitRowsUpdated()
        assertThat(identifiers.list(projectId, owner.id)).containsExactly(TestCaseIdentifierResponse(row.id.toString(), "1"))
        assertSqlRejected("UPDATE test_case SET project_id = $otherProject WHERE id = ${row.id}")
        assertSqlRejected("UPDATE test_case SET id = id + 1000000000 WHERE id = ${row.id}")
        assertSqlRejected("INSERT INTO test_case_identifier(test_case_id, project_id, case_number) VALUES (-1, $projectId, 1)")
        cases.deleteById(row.id)
        assertSqlRejected("INSERT INTO test_case(id, project_id, scene, step, expected_value) VALUES (${row.id}, $projectId, 'S', '재사용', 'x')")
        assertSqlRejected("INSERT INTO test_case(id, project_id, scene, step, expected_value) VALUES (${row.id}, $otherProject, 'S', '이동', 'x')")
    }

    @Test
    fun `프로젝트 멤버만 번호를 조회하고 다른 프로젝트 및 삭제 프로젝트는 보이지 않는다`(): Unit = runBlocking {
        val owner = owner()
        val stranger = owner()
        val projectId = project(owner.id)
        val foreignProject = project(stranger.id)
        newCase(projectId)
        newCase(foreignProject)
        assertThat(get(projectId, stranger.token).status).isEqualTo(404)
        assertThat(get(foreignProject, owner.token).status).isEqualTo(404)
        assertThat(get(projectId, null).status).isEqualTo(401)
        database.sql("UPDATE project SET deleted_at = now() WHERE id = :id").bind("id", projectId).fetch().awaitRowsUpdated()
        assertThat(get(projectId, owner.token).status).isEqualTo(404)
    }

    @Test
    fun `큰 내부 ID와 프로젝트 번호를 Number 손실 없이 반환한다`(): Unit = runBlocking {
        val owner = owner()
        val projectId = project(owner.id)
        database.sql("INSERT INTO test_case_number_counter(project_id, last_number) VALUES (:id, 9007199254740992)")
            .bind("id", projectId).fetch().awaitRowsUpdated()
        database.sql("""INSERT INTO test_case(id, project_id, scene, step, expected_value)
            VALUES (9007199254740995, :project, 'S', '큰 ID', 'x')""")
            .bind("project", projectId).fetch().awaitRowsUpdated()
        val response = get(projectId, owner.token).body.single()
        assertThat(response["testCaseId"].asText()).isEqualTo("9007199254740995")
        assertThat(response["caseNumber"].asText()).isEqualTo("9007199254740993")
    }

    @Test
    fun `한 TC의 여러 build는 같은 프로젝트 번호를 유지한다`(): Unit = runBlocking {
        val owner = owner()
        val projectId = project(owner.id)
        val row = newCase(projectId)
        for (version in listOf("1.0", "2.0")) {
            val build = builds.save(GameBuildEntity(projectId = projectId, version = version, createdAt = Instant.now(), updatedAt = Instant.now()))
            versions.save(TestCaseVersionEntity.snapshotOf(row, build.id!!))
        }
        assertThat(identifiers.list(projectId, owner.id)).containsExactly(TestCaseIdentifierResponse(row.id.toString(), "1"))
    }

    @Test
    fun `저장에 실패한 TC는 사용된 번호로 남지 않는다`(): Unit = runBlocking {
        val owner = owner()
        val projectId = project(owner.id)
        assertSqlRejected("INSERT INTO test_case(project_id, scene, step, expected_value) VALUES ($projectId, 'S', '실패', NULL)")
        val row = newCase(projectId)
        assertThat(identifiers.list(projectId, owner.id)).containsExactly(TestCaseIdentifierResponse(row.id.toString(), "1"))
    }

    private fun assertSqlRejected(sql: String) {
        assertThatThrownBy { runBlocking { database.sql(sql).fetch().awaitRowsUpdated() } }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private suspend fun newCase(projectId: Long) = cases.save(TestCaseEntity(projectId = projectId, scene = "S", step = "절차", expectedValue = "결과"))
    private data class Owner(val id: Long, val token: String)
    private suspend fun owner(): Owner {
        val key = UUID.randomUUID().toString()
        val user = users.upsert(OAuthIdentity("github", key, "tc-$key", "TC", null, null))
        return Owner(user.userId.toLong(), jwt.issue(user))
    }
    private suspend fun project(ownerId: Long): Long {
        val id = projects.save(ProjectEntity(name = "tc-identifiers", genre = "RPG", createdAt = Instant.now(), updatedAt = Instant.now())).id!!
        members.save(ProjectMemberEntity(projectId = id, appUserId = ownerId, role = "OWNER", createdAt = Instant.now()))
        return id
    }
    private data class Reply(val status: Int, val body: JsonNode)
    private fun get(projectId: Long, token: String?): Reply {
        val request = WebClient.create("http://localhost:$port").get().uri("/api/projects/$projectId/test-case-identifiers")
        token?.let { request.cookie("artel_access_token", it) }
        return request.exchangeToMono { response -> response.bodyToMono(String::class.java).defaultIfEmpty("null")
            .map { Reply(response.statusCode().value(), mapper.readTree(it)) } }.block()!!
    }
}
