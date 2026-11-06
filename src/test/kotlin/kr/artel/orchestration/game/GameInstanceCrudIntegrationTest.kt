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

/**
 * 대시보드에서 하는 일은 조회·이름 변경·삭제뿐이다. 인스턴스를 만드는 것은 SDK 등록이다.
 */
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
    fun `lists the instance the sdk created`(): Unit = runBlocking {
        val webToken = webToken()
        val sdkToken = sdkToken()
        val projectId = createProject(webToken)
        val registered = register(sdkToken, projectId, "sdk-uuid-1", instanceName = "내 맥북")

        val list = get(webToken, "/api/projects/$projectId/game-instances")

        assertThat(list["items"]).hasSize(1)
        val listed = list["items"][0]
        assertThat(listed["id"].asText()).isEqualTo(registered["instanceId"].asText())
        assertThat(listed["name"].asText()).isEqualTo("내 맥북")
        assertThat(listed["platform"].asText()).isEqualTo("UNITY")
        assertThat(listed["connected"].asBoolean()).isFalse()
    }

    @Test
    fun `renames an instance`(): Unit = runBlocking {
        val webToken = webToken()
        val sdkToken = sdkToken()
        val projectId = createProject(webToken)
        val instanceId = register(sdkToken, projectId, "sdk-uuid-1", instanceName = "이전 이름")["instanceId"].asText()

        val renamed = patch(
            webToken,
            "/api/projects/$projectId/game-instances/$instanceId",
            """{"name":"새 이름"}"""
        )

        assertThat(renamed["name"].asText()).isEqualTo("새 이름")
    }

    @Test
    fun `soft-deletes an instance so it disappears from the list`(): Unit = runBlocking {
        val webToken = webToken()
        val sdkToken = sdkToken()
        val projectId = createProject(webToken)
        val instanceId = register(sdkToken, projectId, "sdk-uuid-1", instanceName = "지울 것")["instanceId"].asText()

        val status = statusOf {
            client().delete()
                .uri("/api/projects/$projectId/game-instances/$instanceId")
                .cookie("artel_access_token", webToken)
                .retrieve()
                .toBodilessEntity()
                .block()
        }

        assertThat(status).isEqualTo(HttpStatus.OK)
        assertThat(get(webToken, "/api/projects/$projectId/game-instances")["items"]).isEmpty()
        // 행은 남아 있어야 한다. 지운 인스턴스와 처음부터 없던 인스턴스를 구분할 수 없으면
        // 나중에 들어온 요청이 왜 거절됐는지 설명할 수 없다.
        assertThat(instanceRepository.count()).isEqualTo(1L)
    }

    @Test
    fun `hides a project's instances from someone who is not a member`(): Unit = runBlocking {
        val webToken = webToken()
        val sdkToken = sdkToken()
        val projectId = createProject(webToken)
        register(sdkToken, projectId, "sdk-uuid-1")

        val strangerToken = signIn("77", "stranger").let { jwtService.issue(it) }

        assertThat(statusOf { get(strangerToken, "/api/projects/$projectId/game-instances") })
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    // 각 테스트가 시작할 때 사용자 행을 비우므로 토큰도 매번 새로 만든다. 한 번 만들어 재사용하면
    // sub가 지워진 사용자를 가리킨다.
    private suspend fun webToken() = jwtService.issue(signIn("42", "octocat"))

    private suspend fun sdkToken() = jwtService.issueSdkToken(signIn("42", "octocat").userId).token

    private fun createProject(token: String): String =
        post(token, "/api/projects", """{"name":"게임 인스턴스 테스트","genre":"ACTION"}""")["id"].asText()

    private fun register(
        token: String,
        projectId: String,
        sdkUuid: String,
        instanceName: String? = null
    ) = objectMapper.readTree(
        client().post()
            .uri("/api/sdk/registrations")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                buildString {
                    append("""{"projectId":"$projectId","sdkUuid":"$sdkUuid","gameVersion":"1.0.0"""")
                    if (instanceName != null) append(""","instanceName":"$instanceName"""")
                    append("}")
                }
            )
            .retrieve()
            .bodyToMono(String::class.java)
            .block()
    )

    private suspend fun signIn(providerUserId: String, login: String) =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        )

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
}
