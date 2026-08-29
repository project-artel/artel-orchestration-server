package kr.artel.orchestration.tracker

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.tracker.entity.TrackerProvider
import kr.artel.orchestration.tracker.repository.ProjectTrackerLinkRepository
import kr.artel.orchestration.tracker.service.TrackerSetupStateService
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
import reactor.core.publisher.Mono

/**
 * 설치 복귀 경로의 안전장치(ARTEL-671).
 *
 * 여기서 지키는 것은 둘이다. 하나는 **서명 없는 `state` 로는 아무 프로젝트에도 설치가 붙지 않는다**는
 * 것이고, 다른 하나는 이 경로가 브라우저에게 **JSON 오류를 보이지 않는다**는 것이다.
 *
 * `installation_id` 소유 확인(user token 교환)은 실제 GitHub 을 부르므로 여기서 검증할 수 없다 —
 * 이 스위트가 확인하는 것은 그 확인이 실패했을 때(테스트 환경에서는 항상 실패한다) 행이 만들어지지
 * 않는다는 사실이다. 성공 경로는 수동 확인 항목이다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitHubTrackerSetupCallbackTest {

    @LocalServerPort private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var stateService: TrackerSetupStateService
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
    fun `redirects instead of answering json when the session cookie is gone`(): Unit = runBlocking {
        // access 쿠키 수명은 15분이고 App 설치는 조직 승인까지 그보다 오래 걸릴 수 있다. 만료된 채
        // 돌아온 브라우저가 raw JSON 401 을 보면 안 된다.
        val redirect = call(token = null, query = "installation_id=1&code=c&state=whatever")

        assertThat(redirect.status).isEqualTo(HttpStatus.FOUND)
        assertThat(redirect.location).endsWith("/projects?tracker=failed")
        assertThat(redirect.contentType).doesNotContain("json")
    }

    @Test
    fun `an unsigned state attaches nothing`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)

        val redirect = call(owner.token, "installation_id=99&code=c&state=${seed.projectId}")

        assertThat(redirect.status).isEqualTo(HttpStatus.FOUND)
        assertThat(redirect.location).endsWith("/projects?tracker=failed")
        assertThat(projectLinkRepository.findByProjectIdAndProvider(seed.projectId, "GITHUB")).isNull()
    }

    @Test
    fun `a state signed for someone else's session attaches nothing`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val attacker = signIn("2", "hubot")
        val seed = seeder.seed(owner.userId)
        // 공격자가 피해자의 프로젝트를 가리키는 state 를 손에 넣었다고 가정한다.
        val state = stateService.issue(seed.projectId, owner.userId, TrackerProvider.GITHUB)

        val redirect = call(attacker.token, "installation_id=99&code=c&state=$state")

        assertThat(redirect.location).endsWith("/settings?tracker=failed")
        assertThat(projectLinkRepository.findByProjectIdAndProvider(seed.projectId, "GITHUB")).isNull()
    }

    @Test
    fun `a callback without a code attaches nothing`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        val state = stateService.issue(seed.projectId, owner.userId, TrackerProvider.GITHUB)

        // code 가 없으면 installation_id 의 소유를 확인할 방법이 없다. 파라미터를 빼는 것만으로
        // 확인을 건너뛸 수 있으면 확인이 없는 것과 같다.
        val redirect = call(owner.token, "installation_id=99&state=$state")

        assertThat(redirect.location).endsWith("/settings?tracker=failed")
        assertThat(projectLinkRepository.findByProjectIdAndProvider(seed.projectId, "GITHUB")).isNull()
    }

    @Test
    fun `an installation the caller cannot prove they own attaches nothing`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        val state = stateService.issue(seed.projectId, owner.userId, TrackerProvider.GITHUB)

        // 테스트 환경에는 App 설정이 없어 교환이 실패한다 — 확인이 실패하면 붙지 않는다는 것이 요점이다.
        val redirect = call(owner.token, "installation_id=999999&code=stolen&state=$state")

        assertThat(redirect.location).endsWith("/settings?tracker=failed")
        assertThat(projectLinkRepository.findByProjectIdAndProvider(seed.projectId, "GITHUB")).isNull()
    }

    @Test
    fun `a valid session reaches the controller rather than the entry point`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner.userId)
        val state = stateService.issue(seed.projectId, owner.userId, TrackerProvider.GITHUB)

        val redirect = call(owner.token, "installation_id=1&code=c&state=$state")

        // 전용 체인이 쿠키 인증을 함께 옮겨 싣지 않으면 여기가 /projects?tracker=failed 로 튕긴다.
        // 프로젝트별 경로로 돌아왔다는 것이 controller 에 닿았다는 증거다.
        assertThat(redirect.location).contains("/projects/${seed.projectId}/settings")
    }

    // --- helpers ---

    private data class Signed(val userId: Long, val token: String)

    private data class Redirect(val status: HttpStatus, val location: String, val contentType: String)

    private suspend fun call(token: String?, query: String): Redirect {
        val request = WebClient.create("http://localhost:$port")
            .get()
            .uri("/api/tracker/github/setup?$query")
        token?.let { request.cookie("artel_access_token", it) }
        return request.exchangeToMono { response ->
            Mono.just(
                Redirect(
                    status = HttpStatus.valueOf(response.statusCode().value()),
                    location = response.headers().asHttpHeaders().location?.toString().orEmpty(),
                    contentType = response.headers().asHttpHeaders().contentType?.toString().orEmpty()
                )
            )
        }.awaitSingle()
    }

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
