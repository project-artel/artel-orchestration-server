package kr.artel.orchestration.qa

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import java.util.concurrent.atomic.AtomicInteger

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QaArchOptionsIntegrationTest {
    companion object {
        private val replies = AtomicInteger()
        private val expectedCatalog = """{"options":[{"key":"code_mode","engines":["agent"],"futureField":42}]}"""
        private val agent = HttpServer.create().port(0).route { routes ->
            routes.get("/internal/qa-arch-options") { _, response ->
                when (replies.incrementAndGet()) {
                    1 -> response.header("Content-Type", "application/json").sendString(Mono.just(expectedCatalog)).then()
                    2 -> response.status(404).send().then()
                    else -> response.header("Content-Type", "application/json").sendString(Mono.just("{}")).then()
                }
            }
        }.bindNow()

        @JvmStatic
        @DynamicPropertySource
        fun agentUrl(registry: DynamicPropertyRegistry) {
            registry.add("artel.agent.base-url") { "http://127.0.0.1:${agent.port()}/internal/" }
        }

        @JvmStatic
        @AfterAll
        fun stopAgent() = agent.disposeNow()
    }

    @LocalServerPort private var port = 0
    @Autowired private lateinit var jwt: JwtService
    @Autowired private lateinit var users: OAuthUserService

    @Test
    fun `세션을 확인하고 Agent의 옵션 필드와 조회 실패를 전달한다`() {
        val client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:$port").build()
        val route = "/api/qa-arch-options"
        client.get().uri(route).exchange().expectStatus().isUnauthorized

        val user = runBlocking {
            users.upsert(OAuthIdentity("github", "qa-options-test", "qa-options", "QA Options", null, null))
        }
        val session = jwt.issue(user)
        fun request() = client.get().uri(route).cookie("artel_access_token", session).exchange()

        request().expectStatus().isOk.expectBody().json(expectedCatalog)
        repeat(2) {
            request().expectStatus().isEqualTo(503)
        }
    }
}
