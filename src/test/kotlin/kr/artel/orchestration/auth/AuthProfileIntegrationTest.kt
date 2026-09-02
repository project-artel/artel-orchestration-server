package kr.artel.orchestration.auth

import com.fasterxml.jackson.databind.JsonNode
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
    fun `seeds the nickname from the provider name at first login`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val me = get(token, "/api/auth/me")

        assertThat(me["nickname"].asText()).isEqualTo("The Octocat")
        assertThat(me["userTag"].asText()).isEqualTo("0000")
    }

    @Test
    fun `answers the profile update with the stored nickname and its userTag`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val updated = setNickname(token, "Yuni")

        assertThat(updated["nickname"].asText()).isEqualTo("Yuni")
        assertThat(updated["userTag"].asText()).isEqualTo("0000")
        val me = get(token, "/api/auth/me")
        assertThat(me["nickname"].asText()).isEqualTo("Yuni")
        assertThat(me["userTag"].asText()).isEqualTo(updated["userTag"].asText())
    }

    @Test
    fun `keeps the userTag when the same nickname is saved again`(): Unit = runBlocking {
        val token = signIn("42", "octocat")
        val first = setNickname(token, "Yuni")

        val second = setNickname(token, "Yuni")

        assertThat(second["userTag"].asText()).isEqualTo(first["userTag"].asText())
    }

    @Test
    fun `gives two users who pick the same nickname different userTags`(): Unit = runBlocking {
        val first = setNickname(signIn("42", "octocat"), "Yuni")

        val second = setNickname(signIn("99", "hubot"), "Yuni")

        assertThat(second["nickname"].asText()).isEqualTo(first["nickname"].asText())
        assertThat(second["userTag"].asText()).isNotEqualTo(first["userTag"].asText())
    }

    @Test
    fun `carries the number over when only the nickname changes`(): Unit = runBlocking {
        setNickname(signIn("42", "octocat"), "Yuni")
        val token = signIn("99", "hubot")
        val taken = setNickname(token, "Yuni")

        val renamed = setNickname(token, "Zed")

        // "Yuni" 아래에서 쓰던 번호가 "Zed" 아래에서도 비어 있으므로 그대로 따라간다.
        assertThat(taken["userTag"].asText()).isEqualTo("0001")
        assertThat(renamed["userTag"].asText()).isEqualTo("0001")
    }

    @Test
    fun `trims the nickname before storing it`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        assertThat(setNickname(token, "  Yuni  ")["nickname"].asText()).isEqualTo("Yuni")
    }

    @Test
    fun `resolves a user by the nickname and userTag pair`(): Unit = runBlocking {
        val token = signIn("42", "octocat")
        val updated = setNickname(token, "Yuni")

        val found = appUserRepository.findByNicknameAndUserTag("Yuni", updated["userTag"].asText())

        assertThat(found?.id.toString()).isEqualTo(updated["id"].asText())
    }

    @Test
    fun `refuses to clear the nickname`(): Unit = runBlocking {
        val token = signIn("42", "octocat")
        setNickname(token, "Yuni")

        val status = putProfile(token, """{"nickname":null}""")

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(get(token, "/api/auth/me")["nickname"].asText()).isEqualTo("Yuni")
    }

    @Test
    fun `refuses a blank nickname`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val status = putProfile(token, """{"nickname":"   "}""")

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `rejects a nickname longer than 64 characters`(): Unit = runBlocking {
        val token = signIn("42", "octocat")

        val status = putProfile(token, """{"nickname":"${"a".repeat(65)}"}""")

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(get(token, "/api/auth/me")["nickname"].asText()).isEqualTo("The Octocat")
    }

    @Test
    fun `rejects the update without a session`(): Unit = runBlocking {
        val status = putProfile(token = null, body = """{"nickname":"Yuni"}""")

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    private suspend fun signIn(providerUserId: String, login: String): String {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = "The Octocat",
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

    /** 성공한 프로필 갱신의 응답 본문. 200이 아니면 여기서 예외가 난다. */
    private fun setNickname(token: String, nickname: String): JsonNode = objectMapper.readTree(
        client().put().uri("/api/auth/me/profile").cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(mapOf("nickname" to nickname)))
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
