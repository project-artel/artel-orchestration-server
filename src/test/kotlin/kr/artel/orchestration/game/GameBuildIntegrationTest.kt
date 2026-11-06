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

    /** 빌드는 사람이 만들 수 없으므로, SDK 등록을 한 번 거쳐 실제 경로로 만든다. */
    private suspend fun createProjectWithBuild(
        token: String,
        version: String,
        providerUserId: String = "42",
        login: String = "octocat"
    ): String {
        val projectId = post(token, "/api/projects", """{"name":"게임 빌드 테스트","genre":"ACTION"}""")["id"].asText()
        val sdkToken = sdkTokenFor(providerUserId, login)

        client().post()
            .uri("/api/sdk/registrations")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $sdkToken")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"projectId":"$projectId","sdkUuid":"sdk-uuid-1","gameVersion":"$version"}""")
            .retrieve()
            .bodyToMono(String::class.java)
            .block()

        return projectId
    }

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
