package kr.artel.orchestration.tracker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
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
import kr.artel.orchestration.tracker.config.GitHubAppProperties
import kr.artel.orchestration.tracker.dto.TrackerAccountType
import kr.artel.orchestration.tracker.entity.ProjectTrackerLinkEntity
import kr.artel.orchestration.tracker.entity.TrackerProvider
import kr.artel.orchestration.tracker.github.GitHubUserInstallation
import kr.artel.orchestration.tracker.github.GitHubUserInstallationClient
import kr.artel.orchestration.tracker.github.GitHubUserInstallationReader
import kr.artel.orchestration.tracker.repository.ProjectTrackerLinkRepository
import kr.artel.orchestration.tracker.service.INSTALLATION_CHOICE_KEY_PREFIX
import kr.artel.orchestration.tracker.service.TrackerInstallationChoiceStore
import kr.artel.orchestration.tracker.service.TrackerSetupStateService
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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 이미 설치된 GitHub App installation 을 골라 프로젝트에 붙이는 흐름(ARTEL-980).
 *
 * user token 교환은 실제 github.com 을 부르므로 [FakeUserInstallationReader] 로 바꾼다. 그래서 이
 * 파일은 `GitHubTrackerSetupCallbackTest` 가 검증하지 못하는 성공 경로까지 확인한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitHubInstallationChoiceHttpIntegrationTest {

    /** `code` 별로 돌려줄 목록을 들고, 모르는 `code` 에는 null 을 돌려준다(교환 실패와 같다). */
    class FakeUserInstallationReader : GitHubUserInstallationReader {
        val installationsByCode = ConcurrentHashMap<String, List<GitHubUserInstallation>>()

        override suspend fun installationsOf(code: String): List<GitHubUserInstallation>? =
            installationsByCode[code]
    }

    @TestConfiguration
    class FakeConfig {
        @Bean fun fakeIssueTrackerClient() = FakeIssueTrackerClient()

        @Bean
        @Primary
        fun fakeRegistry(fake: FakeIssueTrackerClient): IssueTrackerClientRegistry = registryOf(fake)

        @Bean
        @Primary
        fun fakeUserInstallationReader() = FakeUserInstallationReader()
    }

    @LocalServerPort private val port: Int = 0

    @Autowired private lateinit var reader: FakeUserInstallationReader
    @Autowired private lateinit var fake: FakeIssueTrackerClient
    @Autowired private lateinit var redis: ReactiveStringRedisTemplate
    @Autowired private lateinit var choiceStore: TrackerInstallationChoiceStore
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var stateService: TrackerSetupStateService
    @Autowired private lateinit var objectMapper: ObjectMapper
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
        reader.installationsByCode.clear()
        fake.reset()
        // Redis 는 스위트 전체가 공유하고 목록 TTL 이 10분이라, 앞 테스트의 키가 다음 테스트에 남는다.
        val keys = redis.keys("$INSTALLATION_CHOICE_KEY_PREFIX*").collectList().awaitSingle()
        if (keys.isNotEmpty()) redis.delete(*keys.toTypedArray()).awaitSingle()
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
    fun `a return carrying only a code stores the list and redirects to choose`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        reader.installationsByCode["c-owner"] = listOf(installation(5151, "project-artel", TrackerAccountType.ORGANIZATION))

        val redirect = setup(owner, seed.projectId, "code=c-owner")

        assertThat(redirect.status).isEqualTo(HttpStatus.FOUND)
        assertThat(redirect.location).endsWith("/projects/${seed.projectId}/settings?tracker=choose")
        val items = get(owner.token, installationsPath(seed.projectId)).body!!["items"]
        assertThat(items).hasSize(1)
        assertThat(items[0]["installationId"].asText()).isEqualTo("5151")
        assertThat(items[0]["installationId"].isTextual).isTrue()
        assertThat(items[0]["account"].asText()).isEqualTo("project-artel")
        assertThat(items[0]["accountType"].asText()).isEqualTo("ORGANIZATION")
    }

    @Test
    fun `an empty list is stored as an empty list`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        reader.installationsByCode["c-owner"] = emptyList()

        setup(owner, seed.projectId, "code=c-owner")

        // App 이 아직 어디에도 설치되지 않은 사람이다. 409 가 아니라 빈 배열이어야 화면이 설치로 안내한다.
        val response = get(owner.token, installationsPath(seed.projectId))
        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["items"]).isEmpty()
    }

    @Test
    fun `a failed read redirects to failed and stores nothing`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)

        val redirect = setup(owner, seed.projectId, "code=unknown")

        assertThat(redirect.location).endsWith("/settings?tracker=failed")
        val response = get(owner.token, installationsPath(seed.projectId))
        assertThat(response.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.code).isEqualTo("tracker_authorization_required")
    }

    @Test
    fun `a return with neither installation_id nor code fails`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)

        val redirect = setup(owner, seed.projectId, "")

        assertThat(redirect.location).endsWith("/settings?tracker=failed")
    }

    @Test
    fun `a cancelled authorization fails`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)

        // 인가 화면에서 취소하면 GitHub 은 error 와 state 만 실어 돌려보낸다.
        val redirect = setup(owner, seed.projectId, "error=access_denied")

        assertThat(redirect.location).endsWith("/settings?tracker=failed")
    }

    @Test
    fun `a code return from someone who is not an owner fails and stores nothing`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val member = signIn("2", "hubot")
        val seed = seeder.seed(owner.userId)
        seeder.join(seed.projectId, member.userId, ProjectRole.MEMBER)
        reader.installationsByCode["c-member"] = listOf(installation(5151, "project-artel", TrackerAccountType.ORGANIZATION))

        // state 서비스는 권한을 보지 않는다. 복귀 경로가 OWNER 인지 다시 판정해야 한다.
        val redirect = setup(member, seed.projectId, "code=c-member")

        assertThat(redirect.location).endsWith("/settings?tracker=failed")
        assertThat(choiceStore.read(seed.projectId, member.userId)).isNull()
    }

    @Test
    fun `account types map User to USER and anything else to ORGANIZATION`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        reader.installationsByCode["c-owner"] = listOf(
            installation(1, "octocat", TrackerAccountType.USER),
            installation(2, "project-artel", TrackerAccountType.ORGANIZATION)
        )

        setup(owner, seed.projectId, "code=c-owner")

        val items = get(owner.token, installationsPath(seed.projectId)).body!!["items"]
        assertThat(items.map { it["accountType"].asText() }).containsExactly("USER", "ORGANIZATION")
    }

    /**
     * `account.type` 의 해석은 fake 를 지나지 않으므로 실제 구현을 직접 부른다. github.com 대신
     * `exchangeFunction` 이 token 교환과 `GET /user/installations` 에 답한다.
     */
    @Test
    fun `the GitHub client maps User to USER and Organization or Enterprise to ORGANIZATION`(): Unit = runBlocking {
        val installationsJson = """
            {"total_count":4,"installations":[
              {"id":1,"account":{"login":"octocat","type":"User"}},
              {"id":2,"account":{"login":"project-artel","type":"Organization"}},
              {"id":3,"account":{"login":"artel-enterprise","type":"Enterprise"}},
              {"id":4}
            ]}
        """.trimIndent()
        val stubbedWebClient = WebClient.builder()
            .exchangeFunction { request ->
                val body = if (request.url().path.endsWith("/login/oauth/access_token")) {
                    """{"access_token":"user-token"}"""
                } else {
                    installationsJson
                }
                Mono.just(
                    ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()
                )
            }
            .build()
        val client = GitHubUserInstallationClient(
            stubbedWebClient,
            GitHubAppProperties(
                appId = "1",
                appSlug = "artel",
                privateKey = "unused",
                clientId = "client",
                clientSecret = "secret",
                apiBaseUrl = "https://api.github.test",
                webBaseUrl = "https://github.test"
            )
        )

        val installations = client.installationsOf("code")!!

        // account 가 없는 항목(id 4)은 화면에 보일 이름이 없어 빠진다.
        assertThat(installations.map { it.id to it.accountType }).containsExactly(
            1L to TrackerAccountType.USER,
            2L to TrackerAccountType.ORGANIZATION,
            3L to TrackerAccountType.ORGANIZATION
        )
    }

    @Test
    fun `putting an id from the list attaches it and consumes the list`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        authorize(owner, seed.projectId, 5151)

        val response = put(owner.token, installationPath(seed.projectId), """{"installationId":"5151"}""")

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        val link = response.body!!["link"]
        assertThat(link["provider"].asText()).isEqualTo("GITHUB")
        assertThat(link["installed"].asBoolean()).isTrue()
        assertThat(link["repository"].isNull).isTrue()
        assertThat(installationRefOf(seed.projectId)).isEqualTo("5151")

        // 목록은 한 번 쓰면 지워진다.
        val second = put(owner.token, installationPath(seed.projectId), """{"installationId":"5151"}""")
        assertThat(second.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(second.code).isEqualTo("tracker_authorization_required")
    }

    @Test
    fun `putting an id outside the list is refused`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        authorize(owner, seed.projectId, 5151)

        val response = put(owner.token, installationPath(seed.projectId), """{"installationId":"9999"}""")

        assertThat(response.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.code).isEqualTo("tracker_installation_not_verified")
        assertThat(projectLinkRepository.findByProjectIdAndProvider(seed.projectId, "GITHUB")).isNull()
        // 잘못된 PUT 하나가 목록을 지우면 안 된다.
        assertThat(choiceStore.read(seed.projectId, owner.userId)).isNotNull()
    }

    @Test
    fun `putting without a list asks for authorization`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)

        val response = put(owner.token, installationPath(seed.projectId), """{"installationId":"5151"}""")

        assertThat(response.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.code).isEqualTo("tracker_authorization_required")
    }

    @Test
    fun `a blank or non numeric installationId is a bad request`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        authorize(owner, seed.projectId, 5151)

        val blank = put(owner.token, installationPath(seed.projectId), """{"installationId":"   "}""")
        assertThat(blank.status).isEqualTo(HttpStatus.BAD_REQUEST)

        val notNumber = put(owner.token, installationPath(seed.projectId), """{"installationId":"abc"}""")
        assertThat(notNumber.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(notNumber.code).isEqualTo("tracker_installation_not_verified")
    }

    @Test
    fun `another owner cannot use someone else's list`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val coOwner = signIn("2", "hubot")
        val seed = seeder.seed(owner.userId)
        seeder.join(seed.projectId, coOwner.userId, ProjectRole.OWNER)
        authorize(owner, seed.projectId, 5151)

        val response = put(coOwner.token, installationPath(seed.projectId), """{"installationId":"5151"}""")

        assertThat(response.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.code).isEqualTo("tracker_authorization_required")
        assertThat(projectLinkRepository.findByProjectIdAndProvider(seed.projectId, "GITHUB")).isNull()
    }

    @Test
    fun `attaching a different installation clears the chosen repository`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        connect(seed.projectId, owner.userId, installationRef = "4242")
        authorize(owner, seed.projectId, 5151)

        put(owner.token, installationPath(seed.projectId), """{"installationId":"5151"}""")

        val link = get(owner.token, trackerLinkPath(seed.projectId)).body!!["link"]
        assertThat(link["installed"].asBoolean()).isTrue()
        assertThat(link["workspace"].isNull).isTrue()
        assertThat(link["repository"].isNull).isTrue()
    }

    @Test
    fun `attaching the same installation again keeps the repository`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        connect(seed.projectId, owner.userId, installationRef = "5151")
        authorize(owner, seed.projectId, 5151)

        put(owner.token, installationPath(seed.projectId), """{"installationId":"5151"}""")

        val link = get(owner.token, trackerLinkPath(seed.projectId)).body!!["link"]
        assertThat(link["workspace"].asText()).isEqualTo("artel")
        assertThat(link["repository"].asText()).isEqualTo("game")
    }

    @Test
    fun `a row without an installation keeps its repository when one is attached`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        // 지금 흐름에서는 생기지 않는 행이다. NULL 을 "다른 installation" 으로 읽으면 안 된다.
        connect(seed.projectId, owner.userId, installationRef = null)
        authorize(owner, seed.projectId, 5151)

        put(owner.token, installationPath(seed.projectId), """{"installationId":"5151"}""")

        val link = get(owner.token, trackerLinkPath(seed.projectId)).body!!["link"]
        assertThat(link["installed"].asBoolean()).isTrue()
        assertThat(link["repository"].asText()).isEqualTo("game")
    }

    @Test
    fun `the install return attaching a different installation also clears the repository`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        connect(seed.projectId, owner.userId, installationRef = "4242")
        reader.installationsByCode["c-owner"] = listOf(installation(5151, "project-artel", TrackerAccountType.ORGANIZATION))

        val redirect = setup(owner, seed.projectId, "installation_id=5151&code=c-owner")

        assertThat(redirect.location).endsWith("/settings?tracker=connected")
        val link = get(owner.token, trackerLinkPath(seed.projectId)).body!!["link"]
        assertThat(installationRefOf(seed.projectId)).isEqualTo("5151")
        assertThat(link["repository"].isNull).isTrue()
    }

    @Test
    fun `a member is forbidden and a stranger sees nothing on all three endpoints`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val member = signIn("2", "hubot")
        val stranger = signIn("3", "monalisa")
        val seed = seeder.seed(owner.userId)
        seeder.join(seed.projectId, member.userId, ProjectRole.MEMBER)
        val body = """{"installationId":"5151"}"""

        assertThat(get(member.token, authorizeUrlPath(seed.projectId)).status).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(get(member.token, installationsPath(seed.projectId)).status).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(put(member.token, installationPath(seed.projectId), body).status).isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(get(stranger.token, authorizeUrlPath(seed.projectId)).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get(stranger.token, installationsPath(seed.projectId)).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(put(stranger.token, installationPath(seed.projectId), body).status).isEqualTo(HttpStatus.NOT_FOUND)
        // 형식이 틀린 본문도 404 가 먼저다.
        assertThat(put(stranger.token, installationPath(seed.projectId), """{"installationId":"abc"}""").status)
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `authorize-url says plainly that the app is not configured`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)

        // 테스트 profile 에는 App 설정이 없다. client_id 와 state 를 싣는지는 설정이 있어야 볼 수 있다.
        val response = get(owner.token, authorizeUrlPath(seed.projectId))

        assertThat(response.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.code).isEqualTo("tracker_not_configured")
    }

    // --- helpers ---

    private data class Signed(val userId: Long, val token: String)

    private data class Redirect(val status: HttpStatus, val location: String)

    private data class Response(val status: HttpStatus, val body: JsonNode?) {
        val code: String? get() = body?.get("code")?.asText()
    }

    private fun installation(id: Long, account: String, type: TrackerAccountType) =
        GitHubUserInstallation(id = id, account = account, accountType = type)

    /** 인가 복귀를 거쳐 [installationId] 하나가 든 목록을 저장한다. */
    private suspend fun authorize(user: Signed, projectId: Long, installationId: Long) {
        val code = "c-${user.userId}"
        reader.installationsByCode[code] = listOf(
            installation(installationId, "project-artel", TrackerAccountType.ORGANIZATION)
        )
        val redirect = setup(user, projectId, "code=$code")
        assertThat(redirect.location).endsWith("tracker=choose")
    }

    private suspend fun connect(projectId: Long, userId: Long, installationRef: String?) {
        projectLinkRepository.save(
            ProjectTrackerLinkEntity(
                projectId = projectId,
                provider = TrackerProvider.GITHUB.name,
                externalWorkspace = "artel",
                externalRepository = "game",
                installationRef = installationRef,
                connectedBy = userId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )
    }

    private suspend fun installationRefOf(projectId: Long): String? =
        projectLinkRepository.findByProjectIdAndProvider(projectId, "GITHUB")?.installationRef

    private fun authorizeUrlPath(projectId: Long) = "/api/projects/$projectId/tracker/github/authorize-url"

    private fun installationsPath(projectId: Long) = "/api/projects/$projectId/tracker/github/installations"

    private fun installationPath(projectId: Long) = "/api/projects/$projectId/tracker/github/installation"

    private fun trackerLinkPath(projectId: Long) = "/api/projects/$projectId/tracker-link"

    private suspend fun setup(user: Signed, projectId: Long, query: String): Redirect {
        val state = stateService.issue(projectId, user.userId, TrackerProvider.GITHUB)
        val separator = if (query.isEmpty()) "" else "&"
        return client().get()
            .uri("/api/tracker/github/setup?$query${separator}state=$state")
            .cookie("artel_access_token", user.token)
            .exchangeToMono { response ->
                Mono.just(
                    Redirect(
                        status = HttpStatus.valueOf(response.statusCode().value()),
                        location = response.headers().asHttpHeaders().location?.toString().orEmpty()
                    )
                )
            }.awaitSingle()
    }

    private suspend fun get(token: String, uri: String): Response =
        client().get().uri(uri).cookie("artel_access_token", token)
            .exchangeToMono { toResponse(it) }.awaitSingle()

    private suspend fun put(token: String, uri: String, body: String): Response =
        client().put().uri(uri).cookie("artel_access_token", token)
            .header("Content-Type", "application/json").bodyValue(body)
            .exchangeToMono { toResponse(it) }.awaitSingle()

    private fun toResponse(response: ClientResponse) =
        response.bodyToMono(String::class.java).defaultIfEmpty("").map { text ->
            Response(
                status = HttpStatus.valueOf(response.statusCode().value()),
                body = text.takeIf { it.isNotBlank() }?.let(objectMapper::readTree)
            )
        }

    private fun client() = WebClient.create("http://localhost:$port")

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
}
