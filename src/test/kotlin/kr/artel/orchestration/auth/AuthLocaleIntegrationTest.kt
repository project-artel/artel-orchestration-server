package kr.artel.orchestration.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthLocaleIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    /**
     * 리액티브 트랜잭션은 구독 컨텍스트에 묶여 있어 @Transactional 테스트 롤백이 동작하지 않는다.
     * 인메모리 H2를 다른 테스트와 공유하므로 각 테스트 시작 시 직접 비운다.
     */
    @BeforeEach
    fun clean(): Unit = runBlocking {
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `me has no locale until the user picks one`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val me = get(token, "/api/auth/me")

        assertThat(me["locale"].isNull).isTrue()
    }

    @Test
    fun `stores a supported locale and exposes it through me`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val status = putLocale(token, """{"locale":"ko"}""")

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(get(token, "/api/auth/me")["locale"].asText()).isEqualTo("ko")
    }

    @Test
    fun `rejects a locale the UI does not translate`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val status = putLocale(token, """{"locale":"fr"}""")

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(get(token, "/api/auth/me")["locale"].isNull).isTrue()
    }

    @Test
    fun `rejects the update without a session`(): Unit = runBlocking {
        val status = putLocale(token = null, body = """{"locale":"ko"}""")

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

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

    private fun putLocale(token: String?, body: String): HttpStatus =
        try {
            val request = client().put().uri("/api/auth/me/locale")
            val withSession = if (token != null) request.cookie("artel_access_token", token) else request
            val response = withSession
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().toBodilessEntity().block()!!
            HttpStatus.valueOf(response.statusCode.value())
        } catch (error: WebClientResponseException) {
            HttpStatus.valueOf(error.statusCode.value())
        }

    private fun client() = WebClient.create("http://localhost:$port")
}
