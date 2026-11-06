package kr.artel.orchestration.auth.web

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
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
 * `@CurrentUserId`가 컨트롤러마다 복제돼 있던 `requireUser`를 대체한다(ARTEL-312).
 *
 * 값을 채우는 것만으로는 부족하고 **누구의 id인지**와 **못 채웠을 때 무엇을 답하는지**가 같이
 * 맞아야 한다. 그래서 여기서 보는 것은 세 가지다.
 *
 * 1. 유효한 세션은 자기 사용자 id로 해석된다 — 다른 사용자의 데이터가 섞이지 않는다.
 * 2. 서명은 유효하지만 `sub`가 사용자 id 형식이 아닌 토큰은 401이다. 리팩터링 전
 *    `requireUser`가 실제로 지키던 유일한 경로가 이것이다.
 * 3. 세션이 없으면 401이다.
 *
 * SSE 엔드포인트를 함께 확인한다. 리졸버는 `Mono`를 돌려주므로 suspend 핸들러에서만 되고
 * `Flow`를 돌려주는 non-suspend 핸들러에서 안 되는 실수를 컴파일러가 잡아주지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CurrentUserIdArgumentResolverIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var documentRepository: ProjectDocumentRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    /** 리액티브 트랜잭션은 롤백되지 않고 DB를 공유하므로 FK 순서대로 직접 비운다. */
    @BeforeEach
    fun clean(): Unit = runBlocking {
        documentRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `resolves the caller's own user id, not just any user id`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val stranger = signIn("43", "hubot")
        post(owner, "/api/projects", """{"name":"Demo Day","genre":"ACTION"}""")

        assertThat(get(owner, "/api/projects")["total"].asLong()).isEqualTo(1)
        assertThat(get(stranger, "/api/projects")["total"].asLong()).isZero()
    }

    @Test
    fun `answers 401 to a signed token whose sub is not a user id`() {
        // 식별자 규칙이 바뀌기 전에 발급되어 브라우저에 남아 있는 토큰이 이 모양이다.
        val legacy = jwtService.issue(
            AuthenticatedUser(
                userId = "github:42",
                provider = "github",
                login = "octocat",
                displayName = "octocat",
                avatarUrl = null
            )
        )

        assertThat(statusOf { get(legacy, "/api/projects") }).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `answers 401 without a session`() {
        assertThat(statusOf { client().get().uri("/api/projects").retrieve().bodyToMono(String::class.java).block() })
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `fills the parameter on a streaming handler too`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val legacy = jwtService.issue(
            AuthenticatedUser(
                userId = "github:42",
                provider = "github",
                login = "octocat",
                displayName = "octocat",
                avatarUrl = null
            )
        )

        // 없는 실행이라 스트림은 곧바로 닫히지만, 200이 왔다는 것은 핸들러가 id를 받고 실행됐다는 뜻이다.
        assertThat(statusOf { get(owner, "/api/qa-tries/999999/events") }).isEqualTo(HttpStatus.OK)
        assertThat(statusOf { get(legacy, "/api/qa-tries/999999/events") }).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // --- helpers ---

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
            .retrieve().bodyToMono(String::class.java).block() ?: "{}"
    )

    private fun post(token: String, uri: String, body: String) = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", token)
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
