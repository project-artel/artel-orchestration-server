package kr.artel.orchestration.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * 두 포트의 경계 테스트(ARTEL-266).
 *
 * **실제 소켓으로** 두드리는 것이 이 클래스의 존재 이유다. 컨텍스트에 바인딩하는 방식
 * (`@AutoConfigureWebTestClient`)은 어느 서버도 거치지 않으므로 포트 분리를 볼 수 없다.
 * 다른 통합 테스트들이 엔드포인트의 계약을 보는 동안, 여기서는 **어느 포트에 그것이 있는지**만
 * 본다.
 *
 * 상태 코드는 전부 **정확히** 단언한다. "404 또는 401"로 느슨하게 잡으면 게이트가 빠져
 * 요청이 Security까지 흘러가 401이 되는 회귀를 통과시킨다 — 그것이 바로 경계가 한 겹
 * 밀린 상태다.
 *
 * 내부 경로 대표로 knowledge 조회를 쓴다. 부수효과가 없어 정리 코드가 필요 없고, 라우팅만
 * 보는 이 테스트에 DB 상태를 끌어들이지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalApiPortIntegrationTest {

    @LocalServerPort
    private val publicPort: Int = 0

    @Autowired
    private lateinit var internalApiServer: InternalApiServer

    private fun publicClient() = WebClient.create("http://localhost:$publicPort")
    private fun internalClient() = WebClient.create("http://localhost:${internalApiServer.port}")

    @Test
    fun `내부 API는 공개 포트와 다른 포트에 뜬다`() {
        assertThat(internalApiServer.port).isNotEqualTo(publicPort)
    }

    @Test
    fun `공개 포트에는 내부 경로가 없다`() {
        // 401이 나온다면 게이트가 사라지고 Security가 대신 답한 것이다. 그 경우 "인증만 붙이면
        // 인터넷에서 닿는다"는 뜻이라 404와 의미가 전혀 다르다.
        assertThat(status(publicClient(), KNOWLEDGE_PATH)).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `내부 포트는 내부 경로를 무인증으로 처리한다`() {
        assertThat(status(internalClient(), KNOWLEDGE_PATH)).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `내부 포트에는 엔드유저 API가 없다`() {
        // 인증 대상 경로라 게이트가 없으면 401이 된다.
        assertThat(status(internalClient(), "/api/projects")).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `내부 포트에는 로그인 흐름이 없다`() {
        // 게이트가 없으면 GitHub로 302 리다이렉트가 나간다.
        assertThat(status(internalClient(), "/oauth2/authorization/github"))
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `내부 포트에는 SDK 웹소켓 경로가 없다`() {
        assertThat(status(internalClient(), "/ws/sdk")).isEqualTo(HttpStatus.NOT_FOUND)
    }

    /**
     * 상태 코드만 꺼낸다. `retrieve()`는 4xx만 예외로 바꾸고 3xx는 그냥 통과시켜, 예외 기반으로
     * 짜면 리다이렉트 회귀가 캐스팅 오류로 둔갑한다. 여기서는 어떤 응답이든 코드로 받는다.
     */
    private fun status(client: WebClient, path: String): HttpStatusCode =
        client.get().uri(path)
            .exchangeToMono { Mono.just(it.statusCode()) }
            .block(TIMEOUT)!!

    private companion object {
        /** 부수효과 없는 내부 경로. 프로젝트가 없어도 빈 목록으로 200이다. */
        const val KNOWLEDGE_PATH = "/internal/knowledge?projectId=1"

        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
