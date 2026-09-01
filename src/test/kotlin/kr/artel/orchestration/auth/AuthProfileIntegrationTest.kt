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
class AuthProfileIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    /** 리액티브 트랜잭션은 구독 컨텍스트에 묶여 있어 @Transactional 테스트 롤백이 동작하지 않는다. */
    @BeforeEach
    fun clean(): Unit = runBlocking {
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `me has no nickname or battleTag until the user sets them`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val me = get(token, "/api/auth/me")

        assertThat(me["nickname"].isNull).isTrue()
        assertThat(me["battleTag"].isNull).isTrue()
    }

    @Test
    fun `sets nickname and battleTag and exposes them through me`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val status = putProfile(token, """{"nickname":"Yuni","battleTag":"Yuni#1234"}""")

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT)
        val me = get(token, "/api/auth/me")
        assertThat(me["nickname"].asText()).isEqualTo("Yuni")
        assertThat(me["battleTag"].asText()).isEqualTo("Yuni#1234")
    }

    @Test
    fun `trims the nickname before storing it`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        putProfile(token, """{"nickname":"  Yuni  ","battleTag":null}""")

        assertThat(get(token, "/api/auth/me")["nickname"].asText()).isEqualTo("Yuni")
    }

    @Test
    fun `clears both fields when null is sent for a value that was set`(): Unit = runBlocking {
        val token = signIn("42", "octocat")
        putProfile(token, """{"nickname":"Yuni","battleTag":"Yuni#1234"}""")

        val status = putProfile(token, """{"nickname":null,"battleTag":null}""")

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT)
        val me = get(token, "/api/auth/me")
        assertThat(me["nickname"].isNull).isTrue()
        assertThat(me["battleTag"].isNull).isTrue()
    }

    @Test
    fun `treats a blank nickname as clearing it`(): Unit = runBlocking {
        val token = signIn("42", "octocat")
        putProfile(token, """{"nickname":"Yuni","battleTag":null}""")

        val status = putProfile(token, """{"nickname":"   ","battleTag":null}""")

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(get(token, "/api/auth/me")["nickname"].isNull).isTrue()
    }

    @Test
    fun `rejects a nickname longer than 64 characters`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val status = putProfile(token, """{"nickname":"${"a".repeat(65)}","battleTag":null}""")

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(get(token, "/api/auth/me")["nickname"].isNull).isTrue()
    }

    @Test
    fun `rejects a malformed battleTag`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val status = putProfile(token, """{"nickname":null,"battleTag":"NoHashHere"}""")

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(get(token, "/api/auth/me")["battleTag"].isNull).isTrue()
    }

    @Test
    fun `rejects a battleTag whose digits exceed 8`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val status = putProfile(token, """{"nickname":null,"battleTag":"Yuni#123456789"}""")

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `rejects the update without a session`(): Unit = runBlocking {
        val status = putProfile(token = null, body = """{"nickname":"Yuni","battleTag":null}""")

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

    private fun putProfile(token: String?, body: String): HttpStatus =
        try {
            val request = client().put().uri("/api/auth/me/profile")
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
