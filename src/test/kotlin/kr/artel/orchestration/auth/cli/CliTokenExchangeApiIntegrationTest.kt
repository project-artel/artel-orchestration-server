package kr.artel.orchestration.auth.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.CliTokenRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
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
import java.util.Base64

/** PKCE verifier 는 43자 이상이어야 한다. 이 값은 46자다. */
private const val VERIFIER = "verifier-that-is-long-enough-for-pkce-43-chars"
private const val OTHER_VERIFIER = "a-different-verifier-that-is-also-long-enough-1"

/**
 * `POST /api/auth/cli-tokens/exchange` — 브라우저 왕복을 마친 CLI 가 코드와 verifier 를 CLI 토큰과
 * 바꾸는 경로.
 *
 * 여기서 보는 것은 두 가지다. 하나는 자격증명 없이 열리는 이 경로가 코드·verifier 말고 아무것도
 * 받지 않는다는 것. 다른 하나는 SDK 로그인과 CLI 로그인이 같은 코드 저장소를 쓰면서도 서로의
 * 코드를 받지 않는다는 것 — 그것이 깨지면 CLI 용 코드가 30일짜리 SDK JWT 로 바뀐다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CliTokenExchangeApiIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var cliTokenRepository: CliTokenRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    /** 리액티브 트랜잭션은 롤백되지 않고 DB를 공유하므로 FK 순서대로 직접 비운다. */
    @BeforeEach
    fun clean(): Unit = runBlocking {
        cliTokenRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `turns a cli login code into a token with no credential of its own`(): Unit = runBlocking {
        val owner = upsert("42", "octocat")
        val code = issueCode(jwtService.issue(owner), "cli")

        val created = objectMapper.readTree(
            exchange("""{"code":"$code","codeVerifier":"$VERIFIER","name":"노트북","expiresInDays":90}""")
                .retrieve().bodyToMono(String::class.java).block()
        )

        // 발급 endpoint 와 같은 201 body 다. CLI 가 이 키 집합에 맞춰 이미 쓰여 있다.
        assertThat(created.fieldNames().asSequence().toSet())
            .isEqualTo(setOf("id", "name", "token", "createdAt", "expiresAt"))
        assertThat(created["token"].asText()).matches("^artel_[A-Za-z0-9_-]{43}$")
        assertThat(created["name"].asText()).isEqualTo("노트북")
        assertThat(created["expiresAt"].isNull).isFalse()

        // 나온 원문이 실제로 그 사람의 자격증명이다.
        val me = body(
            client().get().uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${created["token"].asText()}")
        )
        assertThat(me["id"].asText()).isEqualTo(owner.userId)

        // 행은 코드 발급 때가 아니라 교환 때 만들어진다.
        val rows = cliTokenRepository.findAll().toList()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().tokenHash).isNotEqualTo(created["token"].asText())
    }

    @Test
    fun `refuses a wrong verifier and leaves no row`(): Unit = runBlocking {
        val code = issueCode(signIn("42", "octocat"), "cli")

        assertThat(
            status(exchange("""{"code":"$code","codeVerifier":"$OTHER_VERIFIER","name":"노트북","expiresInDays":90}"""))
        ).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(cliTokenRepository.findAll().toList()).isEmpty()
    }

    @Test
    fun `refuses a code that was already exchanged`(): Unit = runBlocking {
        val code = issueCode(signIn("42", "octocat"), "cli")
        val body = """{"code":"$code","codeVerifier":"$VERIFIER","name":"노트북","expiresInDays":90}"""

        assertThat(status(exchange(body))).isEqualTo(HttpStatus.CREATED.value())
        // 두 번째가 통하면 코드 하나로 토큰을 계속 찍어낼 수 있다.
        assertThat(status(exchange(body))).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(cliTokenRepository.findAll().toList()).hasSize(1)
    }

    @Test
    fun `refuses an sdk code here`(): Unit = runBlocking {
        val code = issueCode(signIn("42", "octocat"), "sdk")

        // verifier 는 맞다. 걸리는 것은 kind 하나이고, 응답은 없는 코드와 구분되지 않는다.
        assertThat(
            status(exchange("""{"code":"$code","codeVerifier":"$VERIFIER","name":"노트북","expiresInDays":90}"""))
        ).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(cliTokenRepository.findAll().toList()).isEmpty()
    }

    @Test
    fun `refuses a cli code at the sdk token path`(): Unit = runBlocking {
        val code = issueCode(signIn("42", "octocat"), "cli")

        // 이것이 열려 있으면 CLI 로그인 코드가 30일짜리 SDK JWT 로 바뀐다.
        assertThat(status(exchangeSdk(code, VERIFIER))).isEqualTo(HttpStatus.BAD_REQUEST.value())
    }

    /**
     * 회귀. 이미 배포된 SDK 와 중계 페이지는 `kind` 를 보내지 않는다. 그 요청이 여전히 SDK 코드를
     * 내고 `/api/auth/sdk/token` 에서 교환되어야 한다.
     */
    @Test
    fun `keeps the sdk flow working when kind is omitted`(): Unit = runBlocking {
        val session = signIn("42", "octocat")
        val code = objectMapper.readTree(
            client().post().uri("/api/auth/sdk/codes").cookie(COOKIE, session)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"codeChallenge":"${challengeOf(VERIFIER)}"}""")
                .retrieve().bodyToMono(String::class.java).block()
        )["code"].asText()

        val exchanged = body(exchangeSdk(code, VERIFIER))
        assertThat(exchanged["token"].asText()).isNotBlank()
        assertThat(exchanged["refreshToken"].asText()).isNotBlank()
    }

    @Test
    fun `applies the same expiresInDays rule as the issue endpoint`(): Unit = runBlocking {
        val session = signIn("42", "octocat")

        val tooLong = issueCode(session, "cli")
        assertThat(
            status(exchange("""{"code":"$tooLong","codeVerifier":"$VERIFIER","name":"노트북","expiresInDays":366}"""))
        ).isEqualTo(HttpStatus.BAD_REQUEST.value())

        val never = issueCode(session, "cli")
        val created = objectMapper.readTree(
            exchange("""{"code":"$never","codeVerifier":"$VERIFIER","name":"노트북","expiresInDays":null}""")
                .retrieve().bodyToMono(String::class.java).block()
        )
        assertThat(created["expiresAt"].isNull).isTrue()
    }

    /** 만료 없음은 명시적 선택이어야 한다. 키를 빠뜨린 요청은 교환 경로에서도 400 이다. */
    @Test
    fun `rejects an exchange that omits expiresInDays`(): Unit = runBlocking {
        val code = issueCode(signIn("42", "octocat"), "cli")

        assertThat(
            status(exchange("""{"code":"$code","codeVerifier":"$VERIFIER","name":"노트북"}"""))
        ).isEqualTo(HttpStatus.BAD_REQUEST.value())
    }

    // --- helpers ---

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

    /** 브라우저 쪽 절반. 중계 페이지가 하는 것과 같은 요청이다. */
    private fun issueCode(session: String, kind: String): String =
        objectMapper.readTree(
            client().post().uri("/api/auth/sdk/codes").cookie(COOKIE, session)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"codeChallenge":"${challengeOf(VERIFIER)}","kind":"$kind"}""")
                .retrieve().bodyToMono(String::class.java).block()
        )["code"].asText()

    /** 자격증명을 싣지 않는다. 이 경로가 열려 있다는 것이 이 요청의 단정이다. */
    private fun exchange(requestBody: String) =
        client().post().uri("/api/auth/cli-tokens/exchange")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)

    private fun exchangeSdk(code: String, verifier: String) =
        client().post().uri("/api/auth/sdk/token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"code":"$code","codeVerifier":"$verifier"}""")

    private fun body(request: WebClient.RequestHeadersSpec<*>): JsonNode =
        objectMapper.readTree(request.retrieve().bodyToMono(String::class.java).block() ?: "{}")

    private fun status(request: WebClient.RequestHeadersSpec<*>): Int =
        request.exchangeToMono { Mono.just(it.statusCode().value()) }.block()!!

    private fun client() = WebClient.create("http://localhost:$port")

    /** PKCE S256. 프로덕션 코드와 같은 계산을 테스트 쪽에서 독립적으로 한다. */
    private fun challengeOf(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )

    private companion object {
        const val COOKIE = "artel_access_token"
    }
}
