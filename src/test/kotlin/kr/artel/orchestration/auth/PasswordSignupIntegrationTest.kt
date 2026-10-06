package kr.artel.orchestration.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.entity.PlatformRole
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.PasswordAccountService
import kr.artel.orchestration.auth.service.SignupClosedException
import kr.artel.orchestration.support.PrivateTestDatabase
import kotlinx.coroutines.flow.toList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient

/**
 * 첫 가입자가 ADMIN 이 되고, 그 뒤로 공개 가입이 닫히는지(`ARTEL_SIGNUP_OPEN` 기본값 false).
 *
 * 빈 `app_user` 가 있어야 확인할 수 있어 자기 database 에서 돈다([PrivateTestDatabase]).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasswordSignupIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun privateDatabase(registry: DynamicPropertyRegistry) =
            PrivateTestDatabase.register(registry, "signup_closed")
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var passwordAccountService: PasswordAccountService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var authProperties: AuthProperties

    @BeforeEach
    fun emptyDatabase(): Unit = runBlocking {
        databaseClient.sql("TRUNCATE app_user CASCADE").fetch().rowsUpdated().awaitSingle()
    }

    @Test
    fun `first signup becomes ADMIN and gets session cookies`() {
        assertThat(providers()["signupOpen"].asBoolean()).isTrue()

        val response = post("/api/auth/signup", """{"email":"Owner@Example.com","password":"correct horse","name":"Owner"}""")

        assertThat(response.status).isEqualTo(201)
        assertThat(response.body["platformRole"].asText()).isEqualTo("ADMIN")
        assertThat(response.body["hasPassword"].asBoolean()).isTrue()
        assertThat(response.body["mustChangePassword"].asBoolean()).isFalse()
        assertThat(response.cookies).containsKeys(authProperties.cookieName, authProperties.refreshCookieName)
        // 로그인 이메일은 소문자로 정규화된다.
        assertThat(response.body["email"].asText()).isEqualTo("owner@example.com")
    }

    @Test
    fun `signup closes after the first user`() {
        post("/api/auth/signup", """{"email":"owner@example.com","password":"correct horse","name":"Owner"}""")

        val second = post("/api/auth/signup", """{"email":"guest@example.com","password":"correct horse","name":"Guest"}""")

        assertThat(second.status).isEqualTo(403)
        assertThat(second.body["code"].asText()).isEqualTo("signup_closed")
        assertThat(providers()["signupOpen"].asBoolean()).isFalse()
    }

    /**
     * 동시에 온 가입 여덟 건 중 하나만 ADMIN 으로 들어가고 나머지는 닫힌 가입으로 거절된다.
     *
     * 잠금이 없으면 여러 건이 동시에 빈 `app_user` 를 보고 ADMIN 이 여럿 생긴다. 이 테스트는 그 경합을
     * 실제 트랜잭션 여러 개로 만든다.
     */
    @Test
    fun `concurrent first signups produce exactly one ADMIN`(): Unit = runBlocking {
        val outcomes = (1..8).map { index ->
            async(Dispatchers.IO) {
                runCatching { passwordAccountService.signup("racer$index@example.com", "correct horse", "Racer $index") }
            }
        }.awaitAll()

        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull() })
            .allMatch { it is SignupClosedException }
        val users = appUserRepository.findAll().toList()
        assertThat(users).hasSize(1)
        assertThat(users.single().platformRole).isEqualTo(PlatformRole.ADMIN.name)
    }

    @Test
    fun `rejects a short password and a malformed email`() {
        val shortPassword = post("/api/auth/signup", """{"email":"owner@example.com","password":"short","name":"Owner"}""")
        val badEmail = post("/api/auth/signup", """{"email":"not-an-email","password":"correct horse","name":"Owner"}""")

        assertThat(shortPassword.status).isEqualTo(400)
        assertThat(shortPassword.body["code"].asText()).isEqualTo("invalid_password")
        assertThat(badEmail.status).isEqualTo(400)
        assertThat(badEmail.body["code"].asText()).isEqualTo("invalid_email")
    }

    private data class HttpResult(val status: Int, val body: JsonNode, val cookies: Map<String, String>)

    private fun providers(): JsonNode = objectMapper.readTree(
        client().get().uri("/api/auth/providers").retrieve().bodyToMono(String::class.java).block()
    )

    private fun post(path: String, json: String): HttpResult =
        client().post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchangeToMono { response ->
                response.bodyToMono(String::class.java).defaultIfEmpty("{}").map { body ->
                    HttpResult(
                        status = response.statusCode().value(),
                        body = objectMapper.readTree(body),
                        cookies = response.cookies().mapValues { it.value.first().value }
                    )
                }
            }
            .block()!!

    private fun client() = WebClient.create("http://localhost:$port")
}
