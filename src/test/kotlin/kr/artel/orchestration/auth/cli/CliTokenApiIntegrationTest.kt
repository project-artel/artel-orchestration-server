package kr.artel.orchestration.auth.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.CliTokenRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
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
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

/**
 * CLI 토큰은 이 서버가 처음으로 내는 **상태 있는** 자격증명이다. 그래서 여기서 보는 것은 발급된
 * 값의 모양만이 아니라, 그 값이 어느 경로를 열고 어느 경로를 열지 않으며 폐기가 언제 듣는지다.
 *
 * 회귀 하나가 함께 있다. `cookieTokenConverter` 가 이 작업의 유일한 광역 변경이고 브라우저 체인의
 * 모든 요청이 그 함수를 지나므로, 세션 쿠키와 `Authorization` 헤더의 JWT 가 그대로 통하는지를
 * 같은 파일에서 본다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CliTokenApiIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var cliTokenRepository: CliTokenRepository
    @Autowired private lateinit var documentRepository: ProjectDocumentRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    /** 리액티브 트랜잭션은 롤백되지 않고 DB를 공유하므로 FK 순서대로 직접 비운다. */
    @BeforeEach
    fun clean(): Unit = runBlocking {
        cliTokenRepository.deleteAll()
        documentRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `hands the raw token out once and never again`(): Unit = runBlocking {
        val session = signIn("42", "octocat")
        val created = issue(session, """{"name":"노트북","expiresInDays":30}""")

        assertThat(created["token"].asText()).matches("^artel_[A-Za-z0-9_-]{43}$")

        val listed = body(get("/api/auth/cli-tokens").cookie(COOKIE, session))
        assertThat(listed.size()).isEqualTo(1)
        assertThat(listed[0].has("token")).isFalse()
        assertThat(listed[0]["id"].asText()).isEqualTo(created["id"].asText())
    }

    @Test
    fun `stores only the hash of the token`(): Unit = runBlocking {
        val session = signIn("42", "octocat")
        val created = issue(session, """{"name":"노트북","expiresInDays":30}""")

        val row = cliTokenRepository.findAll().first()
        assertThat(row.tokenHash).hasSize(64)
        assertThat(row.tokenHash).isNotEqualTo(created["token"].asText())
        assertThat(row.tokenHash).matches("^[0-9a-f]{64}$")
    }

    /**
     * 두 principal 경로를 한 테스트에 함께 둔다. principal 결정이 깨지면 둘 중 하나만 깨지는 일이
     * 실제로 가능하기 때문이다 — `@CurrentUserId` 는 타입만 보고 `@AuthenticationPrincipal` 은
     * 컨트롤러가 직접 클레임을 읽는다.
     */
    @Test
    fun `opens both the CurrentUserId path and the AuthenticationPrincipal path`(): Unit = runBlocking {
        val owner = upsert("42", "octocat")
        val session = jwtService.issue(owner)
        createProject(session)
        val token = issue(session, """{"name":"노트북","expiresInDays":30}""")["token"].asText()

        val projects = body(get("/api/projects").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
        assertThat(projects["total"].asLong()).isEqualTo(1)

        val me = body(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
        assertThat(me["id"].asText()).isEqualTo(owner.userId)
    }

    @Test
    fun `answers 401 with a revoked token`(): Unit = runBlocking {
        val session = signIn("42", "octocat")
        val created = issue(session, """{"name":"노트북","expiresInDays":30}""")
        val token = created["token"].asText()

        assertThat(statusWithToken(token, "/api/auth/me")).isEqualTo(HttpStatus.OK.value())

        val deleted = client().delete().uri("/api/auth/cli-tokens/${created["id"].asText()}")
            .cookie(COOKIE, session)
        assertThat(status(deleted)).isEqualTo(HttpStatus.NO_CONTENT.value())

        // 캐시가 없다는 사실이 곧 이 단정이다. 폐기는 다음 요청에 바로 듣는다.
        assertThat(statusWithToken(token, "/api/auth/me")).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun `answers 401 with an expired token`(): Unit = runBlocking {
        val session = signIn("42", "octocat")
        val token = issue(session, """{"name":"노트북","expiresInDays":30}""")["token"].asText()

        // clock 을 흔들지 않고 행을 과거로 민다. 만료 판정이 어디에 있는지가 이 테스트의 대상이다.
        val row = cliTokenRepository.findAll().first()
        cliTokenRepository.save(row.copy(expiresAt = Instant.now().minus(Duration.ofDays(1))))

        assertThat(statusWithToken(token, "/api/auth/me")).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun `answers 404 to revoking someone else's token and leaves it working`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val stranger = signIn("43", "hubot")
        val created = issue(owner, """{"name":"노트북","expiresInDays":30}""")

        val deleted = client().delete().uri("/api/auth/cli-tokens/${created["id"].asText()}")
            .cookie(COOKIE, stranger)
        assertThat(status(deleted)).isEqualTo(HttpStatus.NOT_FOUND.value())

        // 뒷절이 없으면 404 를 돌려주면서 실제로는 폐기해 버리는 구현도 통과한다.
        assertThat(statusWithToken(created["token"].asText(), "/api/auth/me"))
            .isEqualTo(HttpStatus.OK.value())
    }

    @Test
    fun `lists only the caller's own tokens`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val stranger = signIn("43", "hubot")
        issue(owner, """{"name":"노트북","expiresInDays":30}""")
        issue(stranger, """{"name":"CI","expiresInDays":30}""")

        val listed = body(get("/api/auth/cli-tokens").cookie(COOKIE, owner))
        assertThat(listed.size()).isEqualTo(1)
        assertThat(listed[0]["name"].asText()).isEqualTo("노트북")
    }

    @Test
    fun `treats a null expiresInDays as no expiry`(): Unit = runBlocking {
        val session = signIn("42", "octocat")
        val created = issue(session, """{"name":"노트북","expiresInDays":null}""")

        assertThat(created["expiresAt"].isNull).isTrue()
        assertThat(statusWithToken(created["token"].asText(), "/api/auth/me"))
            .isEqualTo(HttpStatus.OK.value())
    }

    @Test
    fun `rejects an expiresInDays outside one to 365`(): Unit = runBlocking {
        val session = signIn("42", "octocat")

        assertThat(issueStatus(session, """{"name":"노트북","expiresInDays":0}"""))
            .isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(issueStatus(session, """{"name":"노트북","expiresInDays":366}"""))
            .isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(issueStatus(session, """{"name":"노트북","expiresInDays":365}"""))
            .isEqualTo(HttpStatus.CREATED.value())
    }

    /**
     * 만료 없음은 명시적 선택이어야 한다. 키를 빠뜨린 요청을 만료 없음으로 읽으면, 값을 실어
     * 보내는 것을 잊은 클라이언트가 영구 토큰을 찍어내게 된다.
     */
    @Test
    fun `rejects a request that omits expiresInDays`(): Unit = runBlocking {
        val session = signIn("42", "octocat")

        assertThat(issueStatus(session, """{"name":"노트북"}"""))
            .isEqualTo(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun `lets a CLI token list and revoke but not issue`(): Unit = runBlocking {
        val session = signIn("42", "octocat")
        val created = issue(session, """{"name":"노트북","expiresInDays":30}""")
        val token = created["token"].asText()

        // 새어나간 토큰 하나로 새 토큰을 만들어 둘 수 있으면 폐기가 의미를 잃는다.
        val issuing = client().post().uri("/api/auth/cli-tokens")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"두 번째","expiresInDays":30}""")
        assertThat(status(issuing)).isEqualTo(HttpStatus.FORBIDDEN.value())

        assertThat(statusWithToken(token, "/api/auth/cli-tokens")).isEqualTo(HttpStatus.OK.value())

        val deleting = client().delete().uri("/api/auth/cli-tokens/${created["id"].asText()}")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        assertThat(status(deleting)).isEqualTo(HttpStatus.NO_CONTENT.value())
    }

    @Test
    fun `fills last used at once and leaves it alone within five minutes`(): Unit = runBlocking {
        val session = signIn("42", "octocat")
        val token = issue(session, """{"name":"노트북","expiresInDays":30}""")["token"].asText()

        assertThat(cliTokenRepository.findAll().first().lastUsedAt).isNull()

        statusWithToken(token, "/api/auth/me")
        val firstUse = cliTokenRepository.findAll().first().lastUsedAt
        assertThat(firstUse).isNotNull()

        statusWithToken(token, "/api/auth/me")
        // 5분 해상도다. 두 번째 요청은 UPDATE 를 아예 내지 않는다.
        assertThat(cliTokenRepository.findAll().first().lastUsedAt).isEqualTo(firstUse)
    }

    @Test
    fun `does not open the sdk chain`(): Unit = runBlocking {
        val session = signIn("42", "octocat")
        val token = issue(session, """{"name":"노트북","expiresInDays":30}""")["token"].asText()

        // SDK 체인은 리소스 서버 기본 converter 를 쓰므로 sdkJwtDecoder 가 이 값을 떨어뜨린다.
        // "CLI 토큰은 SDK 경로를 못 연다"가 코드 추가 없이 성립한다는 것이 이 단정이다.
        assertThat(statusWithToken(token, "/api/sdk/projects"))
            .isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    /**
     * 회귀. `cookieTokenConverter` 를 고쳤으므로 두 방향을 함께 본다. 헤더 bearer 경로는 이 변경
     * 전까지 어느 테스트도 지나지 않았다.
     */
    @Test
    fun `still accepts a session cookie and a browser JWT in the Authorization header`(): Unit = runBlocking {
        val user = upsert("42", "octocat")
        val session = jwtService.issue(user)

        assertThat(body(get("/api/auth/me").cookie(COOKIE, session))["id"].asText())
            .isEqualTo(user.userId)
        assertThat(
            body(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer $session"))["id"].asText()
        ).isEqualTo(user.userId)
    }

    // --- helpers ---

    /** 로그인해 세션 JWT 를 얻는다. */
    private suspend fun signIn(providerUserId: String, login: String): String =
        jwtService.issue(upsert(providerUserId, login))

    private suspend fun upsert(providerUserId: String, login: String) = oauthUserService.upsert(
        OAuthIdentity(
            provider = "github",
            providerUserId = providerUserId,
            login = login,
            displayName = login,
            avatarUrl = null,
            email = "$login@example.com"
        )
    )

    private fun issue(session: String, requestBody: String): JsonNode =
        objectMapper.readTree(
            createRequest(session, requestBody)
                .retrieve().bodyToMono(String::class.java).block()
        )

    private fun issueStatus(session: String, requestBody: String): Int =
        status(createRequest(session, requestBody))

    private fun createRequest(session: String, requestBody: String) =
        client().post().uri("/api/auth/cli-tokens").cookie(COOKIE, session)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)

    private fun createProject(session: String) {
        client().post().uri("/api/projects").cookie(COOKIE, session)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"Demo Day","genre":"ACTION"}""")
            .retrieve().bodyToMono(String::class.java).block()
    }

    private fun get(uri: String) = client().get().uri(uri)

    private fun body(request: WebClient.RequestHeadersSpec<*>): JsonNode =
        objectMapper.readTree(request.retrieve().bodyToMono(String::class.java).block() ?: "{}")

    private fun status(request: WebClient.RequestHeadersSpec<*>): Int =
        request.exchangeToMono { Mono.just(it.statusCode().value()) }.block()!!

    private fun statusWithToken(token: String, uri: String): Int =
        status(get(uri).header(HttpHeaders.AUTHORIZATION, "Bearer $token"))

    private fun client() = WebClient.create("http://localhost:$port")

    private companion object {
        const val COOKIE = "artel_access_token"
    }
}
