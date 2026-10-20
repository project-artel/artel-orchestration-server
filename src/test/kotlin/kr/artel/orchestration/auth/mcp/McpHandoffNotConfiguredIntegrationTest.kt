package kr.artel.orchestration.auth.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

/**
 * MCP 설정이 하나도 없는 기본 테스트 설정에서의 handoff(ARTEL-1088). MCP 를 배포하지 않은 환경이 이 모양이다.
 *
 * 설정을 덮어쓰지 않으므로 다른 기본 설정 테스트와 Spring 컨텍스트를 같이 쓴다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpHandoffNotConfiguredIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUsers: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `서명 키가 없으면 handoff 발급은 503 mcp_handoff_not_configured 이다`() {
        val session = runBlocking {
            val seed = UUID.randomUUID().toString().take(8)
            jwtService.issue(
                oauthUsers.upsert(
                    OAuthIdentity(
                        provider = "github",
                        providerUserId = "handoff-off-$seed",
                        login = "handoff-off-$seed",
                        displayName = "handoff-off-$seed",
                        avatarUrl = null,
                        email = "handoff-off-$seed@example.com",
                    )
                )
            )
        }

        val (status, body) = client().post().uri("/api/mcp/handoffs")
            .cookie("artel_access_token", session)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"requestId":"r1"}""")
            .exchangeToMono { response ->
                response.bodyToMono(String::class.java).map { response.statusCode().value() to objectMapper.readTree(it) }
            }.block()!!

        assertThat(status).isEqualTo(503)
        assertThat(body["code"].asText()).isEqualTo("mcp_handoff_not_configured")
    }

    @Test
    fun `서명 키가 없어도 JWKS 는 빈 keys 로 200 이다`() {
        val body = client().get().uri("/.well-known/artel-handoff-jwks.json")
            .exchangeToMono { response ->
                assertThat(response.statusCode().value()).isEqualTo(200)
                response.bodyToMono(String::class.java)
            }.block()!!

        assertThat(objectMapper.readTree(body)["keys"].size()).isEqualTo(0)
    }

    private fun client() = WebClient.create("http://localhost:$port")
}
