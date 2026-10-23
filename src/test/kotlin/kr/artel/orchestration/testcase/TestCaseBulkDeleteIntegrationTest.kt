package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant
import java.util.UUID

/**
 * `POST /api/projects/{projectId}/test-cases/bulk-delete` 통합 테스트(ARTEL-967).
 *
 * HTTP 로 부른다. 400 중 일부(배열이 아닌 `caseIds`)는 서비스에 닿기 전에 WebFlux 가 막기 때문에,
 * 서비스만 불러서는 상태 코드를 확인할 수 없다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestCaseBulkDeleteIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var db: DatabaseClient

    @Test
    fun `자기 프로젝트 케이스만 지우고 다른 프로젝트 id 와 없는 id 는 세지 않는다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val otherProjectId = newProject(signIn().first)
        val first = newCase(projectId)
        val second = newCase(projectId)
        val kept = newCase(projectId)
        val foreign = newCase(otherProjectId)

        val response = bulkDelete(token, projectId, """{"caseIds":["$first","$second","$foreign","999999999"]}""")

        assertThat(response["deleted"].asInt()).isEqualTo(2)
        assertThat(testCaseRepository.existsById(first)).isFalse()
        assertThat(testCaseRepository.existsById(second)).isFalse()
        assertThat(testCaseRepository.existsById(kept)).isTrue()
        assertThat(testCaseRepository.existsById(foreign)).isTrue()
    }

    @Test
    fun `같은 id 를 두 번 보내도 한 건으로 센다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val caseId = newCase(projectId)

        val response = bulkDelete(token, projectId, """{"caseIds":["$caseId","$caseId"]}""")

        assertThat(response["deleted"].asInt()).isEqualTo(1)
    }

    @Test
    fun `500건은 받고 501건과 빈 배열은 400 이다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val caseId = newCase(projectId)
        // 나머지 499건은 일부러 없는 id 다. 상한은 지울 수 있는 건수가 아니라 배열 길이로 센다.
        val fiveHundred = listOf(caseId.toString()) + (1..499).map { (Long.MAX_VALUE - it).toString() }

        assertThat(statusOf { bulkDelete(token, projectId, body(fiveHundred + "1")) }).isEqualTo(400)
        assertThat(statusOf { bulkDelete(token, projectId, """{"caseIds":[]}""") }).isEqualTo(400)
        assertThat(testCaseRepository.existsById(caseId)).isTrue()

        assertThat(bulkDelete(token, projectId, body(fiveHundred))["deleted"].asInt()).isEqualTo(1)
    }

    @Test
    fun `본문이 계약과 다르면 400 이다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val caseId = newCase(projectId)

        listOf(
            """{}""",
            """{"caseIds":"$caseId"}""",
            """{"caseIds":["abc"]}""",
            """{"caseIds":[null]}""",
            """{"caseIds":["99999999999999999999"]}""",
        ).forEach { requestBody ->
            assertThat(statusOf { bulkDelete(token, projectId, requestBody) })
                .`as`(requestBody)
                .isEqualTo(400)
        }
        assertThat(testCaseRepository.existsById(caseId)).isTrue()
    }

    @Test
    fun `비멤버는 본문과 무관하게 404 이고 케이스는 남는다`(): Unit = runBlocking {
        val (ownerId, _) = signIn()
        val projectId = newProject(ownerId)
        val caseId = newCase(projectId)
        val (_, outsiderToken) = signIn()

        assertThat(statusOf { bulkDelete(outsiderToken, projectId, """{"caseIds":["$caseId"]}""") }).isEqualTo(404)
        assertThat(statusOf { bulkDelete(outsiderToken, projectId, """{"caseIds":[]}""") }).isEqualTo(404)
        assertThat(testCaseRepository.existsById(caseId)).isTrue()
    }

    @Test
    fun `시나리오가 인용한 케이스도 단건 삭제처럼 지운다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val caseId = newCase(projectId)
        db.sql(
            """
            INSERT INTO test_scenario (project_id, title, steps)
            VALUES ($projectId, 'bulk-delete', '[{"case_id": $caseId}]'::jsonb)
            """
        ).then().block()

        val response = bulkDelete(token, projectId, """{"caseIds":["$caseId"]}""")

        assertThat(response["deleted"].asInt()).isEqualTo(1)
        assertThat(testCaseRepository.existsById(caseId)).isFalse()
    }

    private suspend fun signIn(): Pair<Long, String> {
        val providerUserId = UUID.randomUUID().toString()
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = "bulk-$providerUserId",
                displayName = "Bulk",
                avatarUrl = null,
                email = null,
            )
        )
        return user.userId.toLong() to jwtService.issue(user)
    }

    private suspend fun newProject(memberId: Long): Long {
        val now = Instant.now()
        val projectId = projectRepository.save(
            ProjectEntity(name = "bulk-delete", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = memberId, role = "OWNER", createdAt = now)
        )
        return projectId
    }

    private suspend fun newCase(projectId: Long): Long =
        testCaseRepository.save(
            TestCaseEntity(projectId = projectId, scene = "Title", step = "시작", expectedValue = "넘어간다")
        ).id!!

    private fun body(caseIds: List<String>): String =
        objectMapper.writeValueAsString(mapOf("caseIds" to caseIds))

    private fun bulkDelete(token: String, projectId: Long, requestBody: String) = objectMapper.readTree(
        WebClient.create("http://localhost:$port").post()
            .uri("/api/projects/$projectId/test-cases/bulk-delete")
            .cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun statusOf(call: () -> Any?): Int =
        try {
            call()
            200
        } catch (error: WebClientResponseException) {
            error.statusCode.value()
        }
}
