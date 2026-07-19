package kr.artel.orchestration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocumentationIntegrationTest {
    @LocalServerPort
    private val port: Int = 0

    @Test
    fun `publishes the orchestration API contract`() {
        val response = WebClient.create("http://localhost:$port")
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .bodyToMono(String::class.java)
            .block(Duration.ofSeconds(5))

        assertThat(response).contains("Artel Orchestration Server API")
        assertThat(response).contains("/api/sdkId")
        assertThat(response).contains("/api/orchestration/action/{sdkId}")
        assertThat(response).contains("Register an SDK client ID")
        assertThat(response).contains("Deliver agent actions to an SDK client")
    }
}
