package kr.artel.orchestration.tracker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.tracker.client.IssueTrackerClientRegistry
import kr.artel.orchestration.tracker.entity.ProjectTrackerLinkEntity
import kr.artel.orchestration.tracker.entity.TrackerProvider
import kr.artel.orchestration.tracker.repository.IssueTrackerLinkRepository
import kr.artel.orchestration.tracker.repository.ProjectTrackerLinkRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant

/**
 * tracker 연결 API 의 HTTP 표면(ARTEL-671).
 *
 * 이 파일이 [IssueTrackerSyncTest] 와 별개로 필요한 이유는 이 기능의 산출물이 곧 **경로와 상태 코드와
 * JSON 모양**이기 때문이다 — artel-home 이 의존하는 계약의 전부가 그 셋이다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectTrackerLinkHttpIntegrationTest {

    @TestConfiguration
    class FakeTrackerConfig {
        @Bean fun fakeIssueTrackerClient() = FakeIssueTrackerClient()

        @Bean
        @Primary
        fun fakeRegistry(fake: FakeIssueTrackerClient): IssueTrackerClientRegistry = registryOf(fake)
    }

    @LocalServerPort private val port: Int = 0

    @Autowired private lateinit var fake: FakeIssueTrackerClient
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var issueLinkRepository: IssueTrackerLinkRepository
    @Autowired private lateinit var projectLinkRepository: ProjectTrackerLinkRepository
    @Autowired private lateinit var issueRepository: IssueRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    private lateinit var seeder: TrackerSeeder

    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        fake.reset()
        issueLinkRepository.deleteAll()
        issueRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        scenarioRepository.deleteAll()
        projectLinkRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
        seeder = TrackerSeeder(
            projectRepository, memberRepository, scenarioRepository,
            gameInstanceRepository, qaTryRepository, issueRepository
        )
    }

    @Test
    fun `answers a null link when nothing is connected`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)

        val body = get(owner.token, "/api/projects/${seed.projectId}/tracker-link")

        // 빈 객체가 아니라 null 이어야 화면이 "연결 안 됨"을 그린다.
        assertThat(body["link"].isNull).isTrue()
    }

    @Test
    fun `the owner connects a repository and reads the contract shape back`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        install(seed.projectId, owner.userId)

        val put = put(
            owner.token,
            "/api/projects/${seed.projectId}/tracker-link",
            """{"provider":"GITHUB","workspace":"artel","repository":"game","autoSyncSeverities":["BLOCKER"]}"""
        )

        val link = put["link"]
        assertThat(link["provider"].asText()).isEqualTo("GITHUB")
        assertThat(link["installed"].asBoolean()).isTrue()
        assertThat(link["workspace"].asText()).isEqualTo("artel")
        assertThat(link["repository"].asText()).isEqualTo("game")
        assertThat(link["htmlUrl"].asText()).isEqualTo("https://github.test/artel/game")
        assertThat(link["autoSyncSeverities"].map { it.asText() }).containsExactly("BLOCKER")
        assertThat(link["updatedAt"].isNull).isFalse()

        val read = get(owner.token, "/api/projects/${seed.projectId}/tracker-link")
        assertThat(read["link"]["repository"].asText()).isEqualTo("game")
    }

    @Test
    fun `refuses to save a repository it cannot reach`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        install(seed.projectId, owner.userId)
        fake.failVerify = true

        val status = statusOf {
            put(
                owner.token,
                "/api/projects/${seed.projectId}/tracker-link",
                """{"provider":"GITHUB","workspace":"artel","repository":"typo"}"""
            )
        }

        // 저장한 뒤 첫 결함에서야 오타를 알게 되는 것을 막는다.
        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(projectLinkRepository
            .findByProjectIdAndProvider(seed.projectId, TrackerProvider.GITHUB.name)!!
            .externalRepository).isNull()
    }

    @Test
    fun `refuses to connect before the app is installed`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)

        val status = statusOf {
            put(
                owner.token,
                "/api/projects/${seed.projectId}/tracker-link",
                """{"provider":"GITHUB","workspace":"artel","repository":"game"}"""
            )
        }

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a member reads but cannot write and a stranger sees nothing`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val member = signIn("2", "hubot")
        val stranger = signIn("3", "monalisa")
        val seed = seeder.seed(owner.userId)
        seeder.join(seed.projectId, member.userId, ProjectRole.MEMBER)
        install(seed.projectId, owner.userId)

        assertThat(statusOf { get(member.token, "/api/projects/${seed.projectId}/tracker-link") })
            .isEqualTo(HttpStatus.OK)
        assertThat(
            statusOf {
                put(
                    member.token,
                    "/api/projects/${seed.projectId}/tracker-link",
                    """{"provider":"GITHUB","workspace":"artel","repository":"game"}"""
                )
            }
        ).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(statusOf { delete(member.token, "/api/projects/${seed.projectId}/tracker-link") })
            .isEqualTo(HttpStatus.FORBIDDEN)

        // 비참여자에게는 프로젝트의 존재조차 알려주지 않는다.
        assertThat(statusOf { get(stranger.token, "/api/projects/${seed.projectId}/tracker-link") })
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(
            statusOf {
                put(
                    stranger.token,
                    "/api/projects/${seed.projectId}/tracker-link",
                    """{"provider":"GITHUB","workspace":"artel","repository":"game"}"""
                )
            }
        ).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(statusOf { delete(stranger.token, "/api/projects/${seed.projectId}/tracker-link") })
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `deleting the link leaves the issues that already went out alone`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        connect(seed.projectId, owner.userId)
        val issueId = seeder.issue(seed.qaTryId, "BLOCKER")
        postStatus(owner.token, "/api/issues/$issueId/tracker-sync")

        assertThat(deleteStatus(owner.token, "/api/projects/${seed.projectId}/tracker-link"))
            .isEqualTo(HttpStatus.NO_CONTENT)

        assertThat(projectLinkRepository.findByProjectIdAndProvider(seed.projectId, "GITHUB")).isNull()
        // 이미 나간 외부 이슈의 흔적은 남는다 — 저쪽에서 사람이 처리 중일 수 있다.
        assertThat(issueLinkRepository.findByIssueIdAndProvider(issueId, "GITHUB")).isNotNull()
    }

    @Test
    fun `the manual export answers 202 with the tracker state`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        connect(seed.projectId, owner.userId)
        val issueId = seeder.issue(seed.qaTryId, "MINOR")

        val response = post(owner.token, "/api/issues/$issueId/tracker-sync")

        assertThat(response.status).isEqualTo(HttpStatus.ACCEPTED)
        val tracker = objectMapper.readTree(response.body)["tracker"]
        assertThat(tracker["provider"].asText()).isEqualTo("GITHUB")
        assertThat(tracker["syncState"].asText()).isEqualTo("SYNCED")
        assertThat(tracker["externalKey"].isNull).isFalse()
        assertThat(tracker["url"].asText()).startsWith("https://github.test/issues/")
        assertThat(tracker["syncError"].isNull).isTrue()
    }

    @Test
    fun `a failed export comes back as 202 carrying the failure`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        connect(seed.projectId, owner.userId)
        val issueId = seeder.issue(seed.qaTryId, "MINOR")
        fake.failNextCreate = true

        val response = post(owner.token, "/api/issues/$issueId/tracker-sync")

        assertThat(response.status).isEqualTo(HttpStatus.ACCEPTED)
        val tracker = objectMapper.readTree(response.body)["tracker"]
        assertThat(tracker["syncState"].asText()).isEqualTo("FAILED")
        assertThat(tracker["syncError"].isNull).isFalse()
    }

    @Test
    fun `the github endpoints say plainly that the app is not configured`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)

        // 테스트 프로파일에는 App 설정이 없다. 그래도 기동은 성공했고 기존 경로는 그대로 돈다.
        assertThat(statusOf { get(owner.token, "/api/projects/${seed.projectId}/tracker/github/install-url") })
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        // 설치가 없으므로 저장소 목록은 App 설정에 닿기 전에 400 이다.
        assertThat(statusOf { get(owner.token, "/api/projects/${seed.projectId}/tracker/github/repositories") })
            .isEqualTo(HttpStatus.BAD_REQUEST)
        // 기존 QA 경로는 영향을 받지 않는다.
        assertThat(statusOf { get(owner.token, "/api/projects/${seed.projectId}/issues") })
            .isEqualTo(HttpStatus.OK)
    }

    // --- helpers ---

    private data class Signed(val userId: Long, val token: String)

    private data class Response(val status: HttpStatus, val body: String?)

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

    /** App 설치만 끝난 상태(저장소는 아직). 콜백이 만드는 것과 같은 행이다. */
    private suspend fun install(projectId: Long, userId: Long) {
        projectLinkRepository.attachInstallation(
            projectId = projectId,
            provider = TrackerProvider.GITHUB.name,
            installationRef = "4242",
            connectedBy = userId,
            now = Instant.now()
        )
    }

    private suspend fun connect(projectId: Long, userId: Long) {
        projectLinkRepository.save(
            ProjectTrackerLinkEntity(
                projectId = projectId,
                provider = TrackerProvider.GITHUB.name,
                externalWorkspace = "artel",
                externalRepository = "game",
                installationRef = "4242",
                connectedBy = userId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )
    }

    private fun get(token: String, uri: String): JsonNode = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun put(token: String, uri: String, body: String): JsonNode = objectMapper.readTree(
        client().put().uri(uri).cookie("artel_access_token", token)
            .header("Content-Type", "application/json").bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun delete(token: String, uri: String) =
        client().delete().uri(uri).cookie("artel_access_token", token)
            .retrieve().toBodilessEntity().block()

    private fun deleteStatus(token: String, uri: String): HttpStatus =
        HttpStatus.valueOf(delete(token, uri)!!.statusCode.value())

    private fun post(token: String, uri: String): Response {
        val entity = client().post().uri(uri).cookie("artel_access_token", token)
            .retrieve().toEntity(String::class.java).block()!!
        return Response(HttpStatus.valueOf(entity.statusCode.value()), entity.body)
    }

    private fun postStatus(token: String, uri: String): HttpStatus = post(token, uri).status

    private fun client() = WebClient.create("http://localhost:$port")

    private fun statusOf(call: () -> Any?): HttpStatus =
        try {
            call()
            HttpStatus.OK
        } catch (error: WebClientResponseException) {
            HttpStatus.valueOf(error.statusCode.value())
        }
}
