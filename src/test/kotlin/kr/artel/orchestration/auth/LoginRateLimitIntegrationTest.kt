package kr.artel.orchestration.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.support.PrivateTestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * `POST /api/auth/login` 의 이메일 한도. 이메일 한도를 3 으로 낮춘 컨텍스트에서 본다. 주소 한도는
 * 기본값(100)이라 여기서는 걸리지 않는다 — 주소 한도는 `LoginAddressLimitIntegrationTest` 가 본다.
 *
 * 카운터가 컨텍스트에 하나라 테스트도 하나다. 테스트를 나누면 앞 테스트의 실패가 같은 주소(127.0.0.1)
 * 카운터에 쌓여 뒤 테스트가 실행 순서에 따라 깨진다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["artel.auth.login-rate-limit.max-failures=3"]
)
class LoginRateLimitIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun privateDatabase(registry: DynamicPropertyRegistry) =
            PrivateTestDatabase.register(registry, "login_limit")
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var objectMapper: ObjectMapper

    private data class LoginResult(val status: Int, val body: String, val retryAfter: String?)

    @Test
    fun `failures answer one 401 shape, then 429 with Retry-After even for the right password`() {
        val signup = post("/api/auth/signup", """{"email":"owner@example.com","password":"correct horse","name":"Owner"}""")
        assertThat(signup.status).isEqualTo(201)

        val unknownEmail = post("/api/auth/login", """{"email":"nobody@example.com","password":"wrong password"}""")
        val wrongPassword = post("/api/auth/login", """{"email":"owner@example.com","password":"wrong password"}""")
        assertThat(unknownEmail.status).isEqualTo(401)
        assertThat(wrongPassword.status).isEqualTo(401)
        assertThat(objectMapper.readTree(unknownEmail.body)).isEqualTo(objectMapper.readTree(wrongPassword.body))
        assertThat(objectMapper.readTree(wrongPassword.body)["code"].asText()).isEqualTo("invalid_credentials")

        // 이 계정의 세 번째 실패로 이메일 한도에 닿는다.
        repeat(2) {
            assertThat(post("/api/auth/login", """{"email":"owner@example.com","password":"wrong again"}""").status)
                .isEqualTo(401)
        }

        val blocked = post("/api/auth/login", """{"email":"owner@example.com","password":"correct horse"}""")
        assertThat(blocked.status).isEqualTo(429)
        assertThat(objectMapper.readTree(blocked.body)["code"].asText()).isEqualTo("too_many_attempts")
        assertThat(blocked.retryAfter?.toLong()).isBetween(1L, 15 * 60L)
    }

    private fun post(path: String, json: String): LoginResult =
        WebClient.create("http://localhost:$port").post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json.toByteArray())
            .exchangeToMono { response ->
                response.bodyToMono(ByteArray::class.java).map { String(it) }.defaultIfEmpty("{}").flatMap { body ->
                    Mono.just(LoginResult(response.statusCode().value(), body, response.headers().asHttpHeaders().getFirst("Retry-After")))
                }
            }
            .block()!!
}
