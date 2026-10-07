package kr.artel.orchestration.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * GitHub OAuth 는 선택이다. 두 변수가 비어도 서버가 뜨고, 그때 GitHub 로그인 경로는 404 이며
 * `GET /api/auth/providers` 가 `github: false` 를 알린다.
 */
class GitHubOAuthOptionalIntegrationTest {

    @Nested
    @ActiveProfiles("test")
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = ["artel.auth.github.client-id=", "artel.auth.github.client-secret="]
    )
    inner class WithoutGitHubVariables {
        @LocalServerPort private val port: Int = 0
        @Autowired private lateinit var objectMapper: ObjectMapper

        @Test
        fun `boots, reports github false, and answers 404 on the GitHub login path`() {
            assertThat(githubFlag(port, objectMapper)).isFalse()
            assertThat(status(port, "/oauth2/authorization/github")).isEqualTo(404)
        }
    }

    @Nested
    @ActiveProfiles("test")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    inner class WithGitHubVariables {
        @LocalServerPort private val port: Int = 0
        @Autowired private lateinit var objectMapper: ObjectMapper

        @Test
        fun `reports github true and redirects to GitHub`() {
            assertThat(githubFlag(port, objectMapper)).isTrue()
            assertThat(status(port, "/oauth2/authorization/github")).isEqualTo(302)
        }
    }
}

private fun githubFlag(port: Int, objectMapper: ObjectMapper): Boolean =
    objectMapper.readTree(
        WebClient.create("http://localhost:$port").get().uri("/api/auth/providers")
            .retrieve().bodyToMono(String::class.java).block()
    )["github"].asBoolean()

private fun status(port: Int, path: String): Int? =
    WebClient.create("http://localhost:$port").get().uri(path)
        .exchangeToMono { Mono.just(it.statusCode().value()) }.block()
