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
 * 주소 한도가 리버스 프록시 뒤에서도 걸리는지.
 *
 * `server.forward-headers-strategy: framework` 에서 요청 주소는 `X-Forwarded-For` 의 맨 왼쪽 값으로 바뀌고,
 * 그 주소는 이름을 풀지 않은 `InetSocketAddress` 라 `.address` 가 null 이다. 처음 구현이 `.address` 를 읽어
 * 프록시 뒤에서는 주소 한도가 한 번도 걸리지 않았다. 이 클래스가 그 경로를 지킨다.
 *
 * 이메일 한도는 기본값(10)으로 두고 주소 한도만 3 으로 낮춘다. 그래야 실패가 주소 한도 때문에 막혔다는 것이
 * 드러난다. 테스트마다 다른 주소를 써서 카운터가 서로 섞이지 않게 한다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "artel.auth.login-rate-limit.max-failures=10",
        "artel.auth.login-rate-limit.address-max-failures=3"
    ]
)
class LoginAddressLimitIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun privateDatabase(registry: DynamicPropertyRegistry) =
            PrivateTestDatabase.register(registry, "login_address")
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var objectMapper: ObjectMapper

    private data class LoginResult(val status: Int, val code: String?)

    @Test
    fun `a single forwarded address is limited across different emails`() {
        val statuses = (1..4).map { login("user$it@example.com", "wrong password", "203.0.113.7").status }

        assertThat(statuses).containsExactly(401, 401, 401, 429)
    }

    /** 쉼표 목록이면 맨 왼쪽 값이 주소다. 오른쪽 값이 바뀌어도 같은 주소로 센다. */
    @Test
    fun `the leftmost value of a forwarded list is the address`() {
        val statuses = (1..4).map {
            login("list$it@example.com", "wrong password", "198.51.100.9, 10.0.0.$it").status
        }

        assertThat(statuses).containsExactly(401, 401, 401, 429)
        // 다른 주소는 막히지 않는다.
        assertThat(login("list9@example.com", "wrong password", "198.51.100.10").status).isEqualTo(401)
    }

    /** 주소 한도는 브레이크일 뿐 잠금이 아니다. 같은 주소 뒤의 사람이 맞는 비밀번호로는 들어온다. */
    @Test
    fun `the right password still logs in once the address limit is reached`() {
        val signup = WebClient.create("http://localhost:$port").post().uri("/api/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"email":"owner@example.com","password":"correct horse","name":"Owner"}""".toByteArray())
            .exchangeToMono { Mono.just(it.statusCode().value()) }.block()
        assertThat(signup).isEqualTo(201)
        repeat(3) { login("crowd$it@example.com", "wrong password", "192.0.2.50") }

        assertThat(login("owner@example.com", "wrong password", "192.0.2.50").code).isEqualTo("too_many_attempts")
        assertThat(login("owner@example.com", "correct horse", "192.0.2.50").status).isEqualTo(200)
    }

    private fun login(email: String, password: String, forwardedFor: String): LoginResult =
        WebClient.create("http://localhost:$port").post().uri("/api/auth/login")
            .header("X-Forwarded-For", forwardedFor)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"email":"$email","password":"$password"}""".toByteArray())
            .exchangeToMono { response ->
                response.bodyToMono(ByteArray::class.java).map { String(it) }.defaultIfEmpty("{}").flatMap { body ->
                    Mono.just(LoginResult(response.statusCode().value(), objectMapper.readTree(body)["code"]?.asText()))
                }
            }
            .block()!!
}
