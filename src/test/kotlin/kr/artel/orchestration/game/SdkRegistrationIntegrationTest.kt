package kr.artel.orchestration.game

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * SDK 등록은 로그인 세션 없이 instanceKey 하나로 통과한다. 그 키가 곧 인증이자 소속 정보이고,
 * 같은 호출이 게임 버전 보고까지 겸한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SdkRegistrationIntegrationTest {

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
    fun clean() {
        instanceRepository.deleteAll().block()
        buildRepository.deleteAll().block()
        documentRepository.deleteAll().block()
        memberRepository.deleteAll().block()
        projectRepository.deleteAll().block()
        identityRepository.deleteAll().block()
        appUserRepository.deleteAll().block()
    }

    @Test
    fun `registers with a valid key and records the reported version as a build`() {
        val token = signIn()
        val projectId = createProject(token)
        val instance = createInstance(token, projectId)

        val registered = register(instance["instanceKey"].asText(), "sdk-uuid-1", "1.2.3")

        assertThat(registered["instanceId"].asText()).isEqualTo(instance["id"].asText())
        assertThat(registered["projectId"].asText()).isEqualTo(projectId)
        assertThat(registered["gameVersion"].asText()).isEqualTo("1.2.3")

        val builds = get(token, "/api/projects/$projectId/game-builds")
        assertThat(builds["items"]).hasSize(1)
        assertThat(builds["items"][0]["version"].asText()).isEqualTo("1.2.3")
        assertThat(builds["items"][0]["label"].isNull).isTrue()
    }

    @Test
    fun `reuses the same build when the version has not changed`() {
        val token = signIn()
        val projectId = createProject(token)
        val key = createInstance(token, projectId)["instanceKey"].asText()

        val first = register(key, "sdk-uuid-1", "1.2.3")
        val second = register(key, "sdk-uuid-1", "1.2.3")

        assertThat(second["gameBuildId"].asText()).isEqualTo(first["gameBuildId"].asText())
        assertThat(buildRepository.count().block()).isEqualTo(1L)
    }

    @Test
    fun `creates a second build when the reported version changes`() {
        val token = signIn()
        val projectId = createProject(token)
        val key = createInstance(token, projectId)["instanceKey"].asText()

        register(key, "sdk-uuid-1", "1.2.3")
        register(key, "sdk-uuid-1", "1.3.0")

        assertThat(get(token, "/api/projects/$projectId/game-builds")["items"]).hasSize(2)
    }

    @Test
    fun `records the runtime that registered so the dashboard can show it`() {
        val token = signIn()
        val projectId = createProject(token)
        val instance = createInstance(token, projectId)

        register(instance["instanceKey"].asText(), "sdk-uuid-9", "1.0.0")

        val listed = get(token, "/api/projects/$projectId/game-instances")["items"][0]
        assertThat(listed["lastConnectedAt"].isNull).isFalse()

        val stored = instanceRepository.findById(instance["id"].asText().toLong()).block()!!
        assertThat(stored.lastSdkUuid).isEqualTo("sdk-uuid-9")
    }

    @Test
    fun `refuses an unknown key`() {
        val error = errorOf { register("NOSUCH-KEY-00000-00000", "sdk-uuid-1", "1.0.0") }

        assertThat(error.statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun `stops accepting a key once its instance is deleted`() {
        val token = signIn()
        val projectId = createProject(token)
        val instance = createInstance(token, projectId)
        val key = instance["instanceKey"].asText()

        register(key, "sdk-uuid-1", "1.0.0")

        client().delete()
            .uri("/api/projects/$projectId/game-instances/${instance["id"].asText()}")
            .cookie("artel_access_token", token)
            .retrieve()
            .toBodilessEntity()
            .block()

        val error = errorOf { register(key, "sdk-uuid-1", "1.0.0") }
        assertThat(error.statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    private fun register(instanceKey: String, sdkUuid: String, gameVersion: String) =
        objectMapper.readTree(
            client().post()
                .uri("/api/sdk/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"instanceKey":"$instanceKey","sdkUuid":"$sdkUuid","gameVersion":"$gameVersion"}""")
                .retrieve()
                .bodyToMono(String::class.java)
                .block()
        )

    private fun createProject(token: String): String =
        post(token, "/api/projects", """{"name":"SDK 등록 테스트","genre":"ACTION"}""")["id"].asText()

    private fun createInstance(token: String, projectId: String) =
        post(token, "/api/projects/$projectId/game-instances", """{"name":"내 맥북","platform":"UNITY"}""")

    private fun signIn(): String {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "42",
                login = "octocat",
                displayName = "octocat",
                avatarUrl = null,
                email = "octocat@example.com"
            )
        ).block()!!
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

    private fun client() = WebClient.create("http://localhost:$port")

    private fun errorOf(call: () -> Any?): WebClientResponseException =
        try {
            call()
            error("요청이 실패해야 하는데 성공했다")
        } catch (error: WebClientResponseException) {
            error
        }
}
