package kr.artel.orchestration.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.auth.service.RefreshTokenService
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
import reactor.core.publisher.Mono

/**
 * 재발급 경로는 세션 없이 열려 있어, 무엇이 통과하고 무엇이 떨어지는지가 곧 세션의 경계다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthRefreshIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var refreshTokenService: RefreshTokenService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var properties: AuthProperties
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    /** AuthLocaleIntegrationTest와 같은 이유로 인메모리 H2를 직접 비운다. */
    @BeforeEach
    fun clean(): Unit = runBlocking {
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `refresh cookie yields a working access cookie`(): Unit = runBlocking {
        val userId = signIn()
        val refresh = refreshTokenService
            .issue(userId, properties.audience, properties.refreshTokenTtl)

        val (status, access) = postRefresh(refresh.token)

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT.value())
        assertThat(access).isNotNull()
        // 새 쿠키가 실제로 세션으로 통해야 재발급이 끝난 것이다.
        assertThat(me(access!!)["id"].asText()).isEqualTo(userId)
    }

    @Test
    fun `rejects a request without a refresh cookie`(): Unit = runBlocking {
        assertThat(refreshStatus(token = null)).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `rejects an access token used as a refresh token`(): Unit = runBlocking {
        val userId = signIn()
        val access = jwtService.issue(
            AuthenticatedUser(userId, "github", "octocat", "octocat", null)
        )

        assertThat(refreshStatus(access)).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `rejects a refresh token whose user is gone`(): Unit = runBlocking {
        val userId = signIn()
        val refresh = refreshTokenService
            .issue(userId, properties.audience, properties.refreshTokenTtl)
        identityRepository.deleteAll()
        appUserRepository.deleteAll()

        assertThat(refreshStatus(refresh.token)).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `sdk refresh token yields a new sdk token`(): Unit = runBlocking {
        val userId = signIn()
        val refresh = refreshTokenService
            .issue(userId, properties.sdkAudience, properties.sdkRefreshTokenTtl)

        val body = objectMapper.readTree(
            client().post().uri("/api/auth/sdk/token/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"refreshToken":"${refresh.token}"}""")
                .retrieve().bodyToMono(String::class.java).block()
        )

        assertThat(body["token"].asText()).isNotBlank()
        assertThat(body["expiresAt"].asText()).isNotBlank()
    }

    @Test
    fun `rejects a browser refresh token on the sdk refresh path`(): Unit = runBlocking {
        val userId = signIn()
        // 브라우저 refresh 토큰으로 30일짜리 SDK 토큰을 받아낼 수 있으면 안 된다.
        val refresh = refreshTokenService
            .issue(userId, properties.audience, properties.refreshTokenTtl)

        val status = try {
            client().post().uri("/api/auth/sdk/token/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"refreshToken":"${refresh.token}"}""")
                .retrieve().toBodilessEntity().block()!!.statusCode.value()
        } catch (error: WebClientResponseException) {
            error.statusCode.value()
        }

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    private suspend fun signIn(): String = oauthUserService.upsert(
        OAuthIdentity(
            provider = "github",
            providerUserId = "42",
            login = "octocat",
            displayName = "The Octocat",
            avatarUrl = null,
            email = "octocat@example.com"
        )
    ).userId

    /** 상태 코드와 새로 내려온 access 쿠키. 오류 응답도 예외 없이 그대로 받는다. */
    private fun postRefresh(token: String?): Pair<Int, String?> {
        val request = client().post().uri("/api/auth/refresh")
        val withCookie =
            if (token != null) request.cookie(properties.refreshCookieName, token) else request
        return withCookie.exchangeToMono { response ->
            Mono.just(
                response.statusCode().value() to
                    response.cookies().getFirst(properties.cookieName)?.value
            )
        }.block()!!
    }

    private fun refreshStatus(token: String?): HttpStatus =
        HttpStatus.valueOf(postRefresh(token).first)

    private fun me(accessToken: String) = objectMapper.readTree(
        client().get().uri("/api/auth/me").cookie(properties.cookieName, accessToken)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun client() = WebClient.create("http://localhost:$port")
}
