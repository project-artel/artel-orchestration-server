package kr.artel.orchestration.auth.sdk

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.CliTokenRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.auth.service.RefreshTokenService
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
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * `POST /api/auth/sdk-tokens` — 로그인한 사용자가 브라우저 왕복 없이 자기 SDK 토큰을 받는 경로.
 *
 * 여기서 보는 것은 셋이다. 쿠키 세션과 `cli_token` 둘 다로 부를 수 있다는 것, 나온 토큰이
 * loopback 이 낸 토큰과 구별되지 않고 실제로 `/api/sdk` 아래를 연다는 것, 그리고 이 경로가 SDK
 * 토큰 자신이나 refresh 토큰으로는 열리지 않는다는 것 — 마지막이 깨지면 30일짜리 토큰이 스스로
 * 후계자를 이어 발급해 사실상 만료가 없어진다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SdkTokenApiIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var refreshTokenService: RefreshTokenService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var properties: AuthProperties
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
    fun `mints an sdk token for a browser session`(): Unit = runBlocking {
        val owner = upsert("42", "octocat")
        val minted = body(mintWithCookie(jwtService.issue(owner)))

        // SDK 는 이 키 집합에 맞춰 이미 쓰여 있다. 하나라도 다르면 그쪽을 고쳐야 한다.
        assertThat(minted.fieldNames().asSequence().toSet()).isEqualTo(
            setOf("token", "expiresAt", "refreshToken", "refreshExpiresAt", "userId", "displayName")
        )
        assertThat(minted["userId"].asText()).isEqualTo(owner.userId)
        assertThat(minted["displayName"].asText()).isEqualTo("octocat")

        // 수명은 loopback 이 내는 것과 같은 설정값이다.
        assertThat(Duration.between(Instant.now(), instant(minted["expiresAt"])))
            .isCloseTo(properties.sdkTokenTtl, Duration.ofMinutes(5))
        assertThat(Duration.between(Instant.now(), instant(minted["refreshExpiresAt"])))
            .isCloseTo(properties.sdkRefreshTokenTtl, Duration.ofMinutes(5))
    }

    @Test
    fun `mints an sdk token for a cli token`(): Unit = runBlocking {
        val owner = upsert("42", "octocat")
        val cliToken = issueCliToken(jwtService.issue(owner))

        val minted = body(mintWithBearer(cliToken))

        assertThat(minted["userId"].asText()).isEqualTo(owner.userId)
        assertThat(minted["token"].asText()).isNotBlank()
    }

    /**
     * 이 작업의 목적 자체다. CLI 가 받은 토큰이 게임 안의 SDK 가 부르는 경로를 실제로 열어야 한다.
     */
    @Test
    fun `opens the sdk chain with the minted token`(): Unit = runBlocking {
        val session = jwtService.issue(upsert("42", "octocat"))
        createProject(session)
        val minted = body(mintWithBearer(issueCliToken(session)))["token"].asText()

        val projects = body(get("/api/sdk/projects").header(HttpHeaders.AUTHORIZATION, "Bearer $minted"))
        assertThat(projects["projects"].map { it["name"].asText() }).containsExactly("Demo Day")
    }

    /**
     * loopback 이 낸 토큰과 이 경로가 낸 토큰을 나란히 놓고 본다. SDK 는 두 토큰을 가르지 못해야
     * 하므로, 클레임 이름과 값이 같고 수명도 같아야 한다. `iat`/`exp` 는 발급 시각이 달라 값 자체가
     * 아니라 간격을 본다.
     */
    @Test
    fun `mints a token indistinguishable from the loopback one`(): Unit = runBlocking {
        val session = jwtService.issue(upsert("42", "octocat"))

        val loopback = claimsOf(body(exchangeSdk(issueCode(session), VERIFIER))["token"].asText())
        val direct = claimsOf(body(mintWithCookie(session))["token"].asText())

        assertThat(direct.fieldNames().asSequence().toSet())
            .isEqualTo(loopback.fieldNames().asSequence().toSet())
        assertThat(direct["iss"]).isEqualTo(loopback["iss"])
        assertThat(direct["aud"]).isEqualTo(loopback["aud"])
        assertThat(direct["sub"]).isEqualTo(loopback["sub"])
        assertThat(direct["exp"].asLong() - direct["iat"].asLong())
            .isEqualTo(loopback["exp"].asLong() - loopback["iat"].asLong())
    }

    /** refresh 경로도 같다. SDK 가 갱신을 위해 부르는 곳이 하나뿐이어야 한다. */
    @Test
    fun `refreshes the minted token through the existing sdk refresh path`(): Unit = runBlocking {
        val session = jwtService.issue(upsert("42", "octocat"))
        val refreshToken = body(mintWithCookie(session))["refreshToken"].asText()

        val refreshed = body(
            client().post().uri("/api/auth/sdk/token/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"refreshToken":"$refreshToken"}""")
        )

        assertThat(refreshed["token"].asText()).isNotBlank()
        assertThat(statusWithBearer(refreshed["token"].asText(), "/api/sdk/projects"))
            .isEqualTo(HttpStatus.OK.value())
    }

    @Test
    fun `answers 401 without a credential`(): Unit = runBlocking {
        assertThat(status(client().post().uri(PATH))).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    /**
     * SDK 토큰 자신으로는 부를 수 없다. 이 경로는 브라우저 체인에 있고 그 디코더는
     * `aud=artel-home` 만 받으므로, 코드를 더하지 않아도 막혀 있다. 열려 있으면 30일짜리 토큰이
     * 스스로 후계자를 이어 발급해 만료가 사실상 사라진다.
     */
    @Test
    fun `refuses the sdk token it just minted`(): Unit = runBlocking {
        val minted = body(mintWithCookie(jwtService.issue(upsert("42", "octocat"))))["token"].asText()

        assertThat(status(mintWithBearer(minted))).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        // 브라우저 API 전체가 같은 이유로 닫혀 있다.
        assertThat(statusWithBearer(minted, "/api/auth/me")).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    /** 브라우저 refresh 토큰도 자격증명이 아니다. bearer 로도 쿠키로도 통하지 않는다. */
    @Test
    fun `refuses a browser refresh token`(): Unit = runBlocking {
        val owner = upsert("42", "octocat")
        val refreshToken = refreshTokenService
            .issue(owner.userId, properties.audience, properties.refreshTokenTtl)
            .token

        assertThat(status(mintWithBearer(refreshToken))).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        assertThat(
            status(client().post().uri(PATH).cookie(properties.refreshCookieName, refreshToken))
        ).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    /** 폐기된 CLI 토큰으로는 더 이상 발급되지 않는다. 폐기는 다음 요청에 바로 듣는다. */
    @Test
    fun `stops minting once the cli token is revoked`(): Unit = runBlocking {
        val session = jwtService.issue(upsert("42", "octocat"))
        val created = objectMapper.readTree(
            client().post().uri("/api/auth/cli-tokens").cookie(COOKIE, session)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"name":"노트북","expiresInDays":30}""")
                .retrieve().bodyToMono(String::class.java).block()
        )
        assertThat(status(mintWithBearer(created["token"].asText()))).isEqualTo(HttpStatus.OK.value())

        status(
            client().delete().uri("/api/auth/cli-tokens/${created["id"].asText()}").cookie(COOKIE, session)
        )

        assertThat(status(mintWithBearer(created["token"].asText())))
            .isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    // --- helpers ---

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

    private fun mintWithCookie(session: String) = client().post().uri(PATH).cookie(COOKIE, session)

    private fun mintWithBearer(token: String) =
        client().post().uri(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer $token")

    private fun issueCliToken(session: String): String =
        objectMapper.readTree(
            client().post().uri("/api/auth/cli-tokens").cookie(COOKIE, session)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"name":"노트북","expiresInDays":30}""")
                .retrieve().bodyToMono(String::class.java).block()
        )["token"].asText()

    /** 브라우저 쪽 절반. 중계 페이지가 하는 것과 같은 요청이다. */
    private fun issueCode(session: String): String =
        objectMapper.readTree(
            client().post().uri("/api/auth/sdk/codes").cookie(COOKIE, session)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"codeChallenge":"${challengeOf(VERIFIER)}","kind":"sdk"}""")
                .retrieve().bodyToMono(String::class.java).block()
        )["code"].asText()

    private fun exchangeSdk(code: String, verifier: String) =
        client().post().uri("/api/auth/sdk/token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"code":"$code","codeVerifier":"$verifier"}""")

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

    private fun statusWithBearer(token: String, uri: String): Int =
        status(get(uri).header(HttpHeaders.AUTHORIZATION, "Bearer $token"))

    private fun instant(node: JsonNode): Instant = objectMapper.treeToValue(node, Instant::class.java)

    /** 서명은 보지 않는다. 이 테스트가 묻는 것은 어떤 클레임이 실렸는지다. */
    private fun claimsOf(token: String): JsonNode =
        objectMapper.readTree(Base64.getUrlDecoder().decode(token.split(".")[1]))

    private fun client() = WebClient.create("http://localhost:$port")

    /** PKCE S256. 프로덕션 코드와 같은 계산을 테스트 쪽에서 독립적으로 한다. */
    private fun challengeOf(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )

    private companion object {
        const val PATH = "/api/auth/sdk-tokens"
        const val COOKIE = "artel_access_token"

        /** PKCE verifier 는 43자 이상이어야 한다. 이 값은 46자다. */
        const val VERIFIER = "verifier-that-is-long-enough-for-pkce-43-chars"
    }
}
