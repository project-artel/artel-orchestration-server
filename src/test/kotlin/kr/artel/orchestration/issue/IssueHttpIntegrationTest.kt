package kr.artel.orchestration.issue

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant
import java.util.UUID

/**
 * 이슈 API의 HTTP 표면(ARTEL-245).
 *
 * [IssueIntegrationTest]가 서비스 규칙을 덮는 것과 별개로 이 파일이 필요한 이유는, 이 기능의
 * 산출물이 곧 **경로와 상태 코드와 JSON 모양**이기 때문이다. 라우팅 경로가 틀리거나 204여야 할
 * 응답이 200이거나 id가 숫자로 나가도 서비스 테스트는 초록으로 통과한다 — 그 셋이 home이
 * 의존하는 계약의 전부다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IssueHttpIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var issueRepository: IssueRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    /** 리액티브 트랜잭션은 롤백되지 않고 DB를 공유하므로 FK 순서대로 직접 비운다. */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        issueRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `serves a project's issues with string ids and the fields the console reads`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seed(owner.userId)
        val issueId = seedIssue(seed.qaTryId, "MAJOR", "상점에서 산 아이템이 사라진다")

        val page = get(owner.token, "/api/projects/${seed.projectId}/issues")

        assertThat(page["items"]).hasSize(1)
        val item = page["items"][0]
        // 문자열 id는 계약이다: 숫자로 나가면 64비트 값이 클라이언트에서 정밀도를 잃는다.
        assertThat(item["id"].isTextual).isTrue()
        assertThat(item["id"].asText()).isEqualTo(issueId.toString())
        assertThat(item["qaTryId"].asText()).isEqualTo(seed.qaTryId.toString())
        assertThat(item["severity"].asText()).isEqualTo("MAJOR")
        assertThat(item["status"].asText()).isEqualTo("OPEN")
        assertThat(item["resolvedAt"].isNull).isTrue()
        assertThat(page["hasMore"].asBoolean()).isFalse()
    }

    @Test
    fun `serves one run's issues under the run's own path`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seed(owner.userId)
        seedIssue(seed.qaTryId, "BLOCKER", "레벨 진입에서 튕긴다")

        val page = get(owner.token, "/api/qa-tries/${seed.qaTryId}/issues")

        assertThat(page["items"]).hasSize(1)
        assertThat(page["items"][0]["severity"].asText()).isEqualTo("BLOCKER")
    }

    @Test
    fun `answers 204 to both transitions and leaves the row in the asked-for state`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seed(owner.userId)
        val issueId = seedIssue(seed.qaTryId, "MINOR", "제목이 잘린다")

        assertThat(postStatus(owner.token, "/api/issues/$issueId/resolve")).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(issueRepository.findById(issueId)!!.status).isEqualTo("RESOLVED")

        assertThat(postStatus(owner.token, "/api/issues/$issueId/reopen")).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(issueRepository.findById(issueId)!!.status).isEqualTo("OPEN")
    }

    @Test
    fun `rejects a size outside the page bounds`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seed(owner.userId)

        assertThat(statusOf { get(owner.token, "/api/projects/${seed.projectId}/issues?size=0") })
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(statusOf { get(owner.token, "/api/qa-tries/${seed.qaTryId}/issues?size=101") })
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `answers 404 to a stranger and 401 without a session`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val stranger = signIn("43", "hubot")
        val seed = seed(owner.userId)
        val issueId = seedIssue(seed.qaTryId, "CRITICAL", "남의 프로젝트 이슈")

        assertThat(statusOf { get(stranger.token, "/api/projects/${seed.projectId}/issues") })
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(statusOf { postStatus(stranger.token, "/api/issues/$issueId/resolve") })
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(statusOf { client().get().uri("/api/projects/${seed.projectId}/issues").retrieve().bodyToMono(String::class.java).block() })
            .isEqualTo(HttpStatus.UNAUTHORIZED)

        assertThat(issueRepository.findById(issueId)!!.status).isEqualTo("OPEN")
    }

    // --- helpers ---

    private data class Signed(val userId: Long, val token: String)

    private data class Seed(val projectId: Long, val qaTryId: Long)

    private suspend fun signIn(providerUserId: String, login: String): Signed {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        )
        return Signed(user.userId.toLong(), jwtService.issue(user))
    }

    private suspend fun seed(ownerId: Long): Seed {
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "issue-http", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = project.id!!)
        )!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        val qaTry = qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = "COMPLETED",
                startedAt = now,
                completedAt = now
            )
        )!!
        return Seed(project.id!!, qaTry.id!!)
    }

    private suspend fun seedIssue(qaTryId: Long, severity: String, title: String): Long =
        issueRepository.save(
            IssueEntity(
                qaTryId = qaTryId,
                messageId = UUID.randomUUID().toString(),
                severity = severity,
                title = title,
                detail = Json.of("""{"expected":"보인다","actual":"안 보인다"}"""),
                reportedAt = Instant.parse("2026-07-28T12:34:56.123456Z"),
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )!!.id!!

    private fun get(token: String, uri: String) = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    /** 본문 없는 204를 그대로 확인하려고 상태 코드만 꺼낸다. */
    private fun postStatus(token: String, uri: String): HttpStatus =
        HttpStatus.valueOf(
            client().post().uri(uri).cookie("artel_access_token", token)
                .retrieve().toBodilessEntity().block()!!
                .statusCode.value()
        )

    private fun client() = WebClient.create("http://localhost:$port")

    private fun statusOf(call: () -> Any?): HttpStatus =
        try {
            call()
            HttpStatus.OK
        } catch (error: WebClientResponseException) {
            HttpStatus.valueOf(error.statusCode.value())
        }
}
