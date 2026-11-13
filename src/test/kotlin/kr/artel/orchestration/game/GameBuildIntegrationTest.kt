package kr.artel.orchestration.game

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameBuildIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var instanceRepository: GameInstanceRepository
    @Autowired private lateinit var buildRepository: GameBuildRepository
    @Autowired private lateinit var documentRepository: ProjectDocumentRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    @BeforeEach
    fun clean(): Unit = runBlocking {
        instanceRepository.deleteAll()
        buildRepository.deleteAll()
        documentRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `edits the label and notes a person wrote`(): Unit = runBlocking {
        val token = signIn("42", "octocat")
        val projectId = createProjectWithBuild(token, "1.2.3")
        val buildId = get(token, "/api/projects/$projectId/game-builds")["items"][0]["id"].asText()

        val patched = patch(
            token,
            "/api/projects/$projectId/game-builds/$buildId",
            """{"label":"1차 QA 빌드","notes":"튜토리얼까지만 확인"}"""
        )

        assertThat(patched["label"].asText()).isEqualTo("1차 QA 빌드")
        assertThat(patched["notes"].asText()).isEqualTo("튜토리얼까지만 확인")
        assertThat(patched["version"].asText()).isEqualTo("1.2.3")
    }

    @Test
    fun `leaves the observed version alone even when the request tries to change it`(): Unit = runBlocking {
        val token = signIn("42", "octocat")
        val projectId = createProjectWithBuild(token, "1.2.3")
        val buildId = get(token, "/api/projects/$projectId/game-builds")["items"][0]["id"].asText()

        val patched = patch(
            token,
            "/api/projects/$projectId/game-builds/$buildId",
            """{"label":"라벨","version":"9.9.9"}"""
        )

        assertThat(patched["version"].asText()).isEqualTo("1.2.3")
    }

    @Test
    fun `clears a note with an empty string, not with null`(): Unit = runBlocking {
        val token = signIn("42", "octocat")
        val projectId = createProjectWithBuild(token, "1.2.3")
        val buildId = get(token, "/api/projects/$projectId/game-builds")["items"][0]["id"].asText()
        patch(token, "/api/projects/$projectId/game-builds/$buildId", """{"label":"라벨","notes":"메모"}""")

        val keptLabel = patch(token, "/api/projects/$projectId/game-builds/$buildId", """{"notes":""}""")

        assertThat(keptLabel["notes"].isNull).isTrue()
        assertThat(keptLabel["label"].asText()).isEqualTo("라벨")
    }

    @Test
    fun `hides a project's builds from someone who is not a member`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProjectWithBuild(ownerToken, "1.2.3")

        val strangerToken = signIn("77", "stranger")

        val error = errorOf { get(strangerToken, "/api/projects/$projectId/game-builds") }
        assertThat(error.statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun `declares a build by version before the game runs, and the game later joins that build`(): Unit = runBlocking {
        val token = signIn("42", "octocat")
        val projectId = post(token, "/api/projects", """{"name":"선언 테스트","genre":"ACTION"}""")["id"].asText()

        val first = exchange(token, "/api/projects/$projectId/game-builds", """{"version":" 1.5.0 "}""")
        assertThat(first.first).isEqualTo(HttpStatus.CREATED.value())
        assertThat(first.second["version"].asText()).isEqualTo("1.5.0")
        assertThat(first.second["isEditor"].asBoolean()).isFalse()
        val buildId = first.second["id"].asText()

        val again = exchange(token, "/api/projects/$projectId/game-builds", """{"version":"1.5.0"}""")
        assertThat(again.first).isEqualTo(HttpStatus.OK.value())
        assertThat(again.second["id"].asText()).isEqualTo(buildId)

        registerSdk(projectId, "1.5.0")

        val builds = get(token, "/api/projects/$projectId/game-builds")["items"]
        assertThat(builds.size()).isEqualTo(1)
        assertThat(builds[0]["id"].asText()).isEqualTo(buildId)
    }

    @Test
    fun `refuses a blank version and hides the project from someone who is not a member`(): Unit = runBlocking {
        val token = signIn("42", "octocat")
        val projectId = post(token, "/api/projects", """{"name":"선언 테스트","genre":"ACTION"}""")["id"].asText()

        val blank = errorOf { post(token, "/api/projects/$projectId/game-builds", """{"version":"  "}""") }
        assertThat(blank.statusCode.value()).isEqualTo(HttpStatus.BAD_REQUEST.value())

        val strangerToken = signIn("77", "stranger")
        val hidden = errorOf { post(strangerToken, "/api/projects/$projectId/game-builds", """{"version":"1.5.0"}""") }
        assertThat(hidden.statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    /** SDK 등록을 한 번 거쳐 실제 경로로 빌드를 만든다. */
    private suspend fun createProjectWithBuild(
        token: String,
        version: String,
        providerUserId: String = "42",
        login: String = "octocat"
    ): String {
        val projectId = post(token, "/api/projects", """{"name":"게임 빌드 테스트","genre":"ACTION"}""")["id"].asText()
        registerSdk(projectId, version, providerUserId, login)
        return projectId
    }

    /** 그 버전의 development build 로 SDK 등록을 한 번 한다. */
    private suspend fun registerSdk(projectId: String, version: String, providerUserId: String = "42", login: String = "octocat") {
        client().post()
            .uri("/api/sdk/registrations")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${sdkTokenFor(providerUserId, login)}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"projectId":"$projectId","sdkUuid":"sdk-uuid-1","gameVersion":"$version"}""")
            .retrieve()
            .bodyToMono(String::class.java)
            .block()
    }

    /** 상태 코드와 본문을 함께 돌려준다. 201 과 200 을 가려야 하는 요청에 쓴다. */
    private fun exchange(token: String, uri: String, body: String): Pair<Int, com.fasterxml.jackson.databind.JsonNode> =
        client().post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono { response ->
                response.bodyToMono(String::class.java).map { response.statusCode().value() to objectMapper.readTree(it) }
            }
            .block()!!

    /** 브라우저 세션과 같은 사용자로 SDK 토큰을 만든다. upsert라 사용자가 새로 생기지 않는다. */
    private suspend fun sdkTokenFor(providerUserId: String, login: String): String =
        jwtService.issueSdkToken(
            oauthUserService.upsert(
                OAuthIdentity(
                    provider = "github",
                    providerUserId = providerUserId,
                    login = login,
                    displayName = login,
                    avatarUrl = null,
                    email = "$login@example.com"
                )
            ).userId
        ).token

    private suspend fun signIn(providerUserId: String, login: String): String {
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
        return jwtService.issue(user)
    }

    private fun get(token: String, uri: String) = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun post(token: String, uri: String, body: String) = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun patch(token: String, uri: String, body: String) = objectMapper.readTree(
        client().patch().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun client() = WebClient.create("http://localhost:$port")

    private fun errorOf(call: () -> Any?): WebClientResponseException =
        try {
            call()
            error("요청이 실패해야 하는데 성공했다")
        } catch (error: WebClientResponseException) {
            error
        }
}
