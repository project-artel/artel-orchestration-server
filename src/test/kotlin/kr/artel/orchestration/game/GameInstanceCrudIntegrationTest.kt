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

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameInstanceCrudIntegrationTest {

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

    /**
     * 리액티브 트랜잭션은 구독 컨텍스트에 묶여 있어 @Transactional 테스트 롤백이 동작하지 않는다.
     * 인메모리 H2를 다른 테스트와 공유하므로 각 테스트 시작 시 직접 비운다.
     */
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
    fun `creates an instance with a key and lists it`() {
        val token = signIn("42", "octocat")
        val projectId = createProject(token)

        val created = post(
            token,
            "/api/projects/$projectId/game-instances",
            """{"name":"내 맥북","platform":"UNITY"}"""
        )

        assertThat(created["name"].asText()).isEqualTo("내 맥북")
        assertThat(created["platform"].asText()).isEqualTo("UNITY")
        assertThat(created["projectId"].asText()).isEqualTo(projectId)
        assertThat(created["instanceKey"].asText()).isNotBlank()
        assertThat(created["connected"].asBoolean()).isFalse()
        assertThat(created["lastConnectedAt"].isNull).isTrue()

        val list = get(token, "/api/projects/$projectId/game-instances")
        assertThat(list["items"]).hasSize(1)
        assertThat(list["items"][0]["id"].asText()).isEqualTo(created["id"].asText())
    }

    @Test
    fun `issues a different key to every instance`() {
        val token = signIn("42", "octocat")
        val projectId = createProject(token)

        val first = post(token, "/api/projects/$projectId/game-instances", """{"name":"A","platform":"UNITY"}""")
        val second = post(token, "/api/projects/$projectId/game-instances", """{"name":"B","platform":"UNITY"}""")

        assertThat(first["instanceKey"].asText()).isNotEqualTo(second["instanceKey"].asText())
    }

    @Test
    fun `renames an instance without changing its key`() {
        val token = signIn("42", "octocat")
        val projectId = createProject(token)
        val created = post(token, "/api/projects/$projectId/game-instances", """{"name":"이전 이름","platform":"UNITY"}""")
        val instanceId = created["id"].asText()

        val renamed = patch(
            token,
            "/api/projects/$projectId/game-instances/$instanceId",
            """{"name":"새 이름"}"""
        )

        assertThat(renamed["name"].asText()).isEqualTo("새 이름")
        assertThat(renamed["instanceKey"].asText()).isEqualTo(created["instanceKey"].asText())
    }

    @Test
    fun `soft-deletes an instance so it disappears from the list`() {
        val token = signIn("42", "octocat")
        val projectId = createProject(token)
        val instanceId = post(
            token,
            "/api/projects/$projectId/game-instances",
            """{"name":"지울 것","platform":"UNITY"}"""
        )["id"].asText()

        val status = statusOf {
            client().delete()
                .uri("/api/projects/$projectId/game-instances/$instanceId")
                .cookie("artel_access_token", token)
                .retrieve()
                .toBodilessEntity()
                .block()
        }

        assertThat(status).isEqualTo(HttpStatus.OK)
        assertThat(get(token, "/api/projects/$projectId/game-instances")["items"]).isEmpty()
        // 행은 남아 있어야 한다. 지운 인스턴스와 처음부터 없던 인스턴스를 구분할 수 없으면
        // 나중에 그 키로 들어온 요청이 왜 거절됐는지 설명할 수 없다.
        assertThat(instanceRepository.count().block()).isEqualTo(1L)
    }

    @Test
    fun `hides a project's instances from someone who is not a member`() {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        post(ownerToken, "/api/projects/$projectId/game-instances", """{"name":"내 것","platform":"UNITY"}""")

        val strangerToken = signIn("77", "stranger")

        assertThat(statusOf { get(strangerToken, "/api/projects/$projectId/game-instances") })
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `rejects a platform the server does not support`() {
        val token = signIn("42", "octocat")
        val projectId = createProject(token)

        val error = errorOf {
            post(token, "/api/projects/$projectId/game-instances", """{"name":"콘솔","platform":"PLAYSTATION"}""")
        }

        assertThat(error.statusCode.value()).isEqualTo(HttpStatus.BAD_REQUEST.value())
    }

    private fun createProject(token: String): String =
        post(token, "/api/projects", """{"name":"게임 인스턴스 테스트","genre":"ACTION"}""")["id"].asText()

    private fun signIn(providerUserId: String, login: String): String {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
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

    private fun patch(token: String, uri: String, body: String) = objectMapper.readTree(
        client().patch().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun client() = WebClient.create("http://localhost:$port")

    private fun statusOf(call: () -> Any?): HttpStatus =
        try {
            call()
            HttpStatus.OK
        } catch (error: WebClientResponseException) {
            HttpStatus.valueOf(error.statusCode.value())
        }

    private fun errorOf(call: () -> Any?): WebClientResponseException =
        try {
            call()
            error("요청이 실패해야 하는데 성공했다")
        } catch (error: WebClientResponseException) {
            error
        }
}
