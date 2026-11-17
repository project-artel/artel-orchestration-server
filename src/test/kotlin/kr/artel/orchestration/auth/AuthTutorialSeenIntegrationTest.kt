package kr.artel.orchestration.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.support.deleteQaRuns
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant

/** 처음 온 사용자에게만 시작 튜토리얼을 띄우는 표시(ARTEL-1462). */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthTutorialSeenIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var db: DatabaseClient

    @BeforeEach
    fun clean(): Unit = runBlocking {
        db.deleteQaRuns()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `a new user has not seen the tutorial`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        assertThat(get(token, "/api/auth/me")["tutorialSeenAt"].isNull).isTrue()
    }

    @Test
    fun `marking the tutorial seen shows up in me`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val status = post(token, "/api/auth/me/tutorial-seen")

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(get(token, "/api/auth/me")["tutorialSeenAt"].isNull).isFalse()
    }

    @Test
    fun `marking again keeps the first time`(): Unit = runBlocking {
        val token = signIn("42", "octocat")
        post(token, "/api/auth/me/tutorial-seen")
        val first = Instant.parse(get(token, "/api/auth/me")["tutorialSeenAt"].asText())

        val status = post(token, "/api/auth/me/tutorial-seen")

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(Instant.parse(get(token, "/api/auth/me")["tutorialSeenAt"].asText())).isEqualTo(first)
    }

    @Test
    fun `rejects marking without a session`(): Unit = runBlocking {
        assertThat(post(token = null, uri = "/api/auth/me/tutorial-seen")).isEqualTo(HttpStatus.UNAUTHORIZED)
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

    private fun post(token: String?, uri: String): HttpStatus =
        try {
            val request = client().post().uri(uri)
            val withSession = if (token != null) request.cookie("artel_access_token", token) else request
            val response = withSession.retrieve().toBodilessEntity().block()!!
            HttpStatus.valueOf(response.statusCode.value())
        } catch (error: WebClientResponseException) {
            HttpStatus.valueOf(error.statusCode.value())
        }

    private fun client() = WebClient.create("http://localhost:$port")
}
