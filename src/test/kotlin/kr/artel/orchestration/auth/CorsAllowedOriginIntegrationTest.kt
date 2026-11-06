package kr.artel.orchestration.auth

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * `artel.auth.allowed-origins`에 실린 목록이 실제 preflight 응답으로 나타나는지 본다(ARTEL-295).
 *
 * 이 목록은 코드가 아니라 설정이라 컴파일도 기동도 막지 않는다. 빠진 origin은 브라우저에서 본문 없는
 * 403으로만 드러나고, 그 403은 CORS가 아니라 인증 실패처럼 읽힌다 — admin-page가 그 403을 로그인
 * 경계로 처리해 "로그인해도 계속 로그인하라고 한다"로 보였던 것이 이 이슈다.
 *
 * preflight로 단언하는 이유는 그것이 브라우저가 실제로 먼저 보내는 요청이고, 인증 없이 CORS 판정만
 * 분리해서 볼 수 있는 유일한 지점이기 때문이다.
 *
 * 실제 포트를 띄우는 이유는 CORS 판정이 요청 URI의 scheme·host를 읽기 때문이다. 애플리케이션
 * 컨텍스트에 직접 바인딩한 WebTestClient는 상대 URI를 만들어 `Actual request scheme must not be
 * null`로 터진다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsAllowedOriginIntegrationTest {

    @Autowired private lateinit var webTestClient: WebTestClient

    @Test
    fun `admin-page origin의 preflight는 자격증명과 함께 허용된다`() {
        webTestClient.options().uri("/api/auth/me")
            .header(HttpHeaders.ORIGIN, ADMIN_ORIGIN)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectStatus().is2xxSuccessful
            // 자격증명이 실리는 요청이라 `*`로는 통하지 않는다. 반향된 origin 자체를 단언한다.
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ADMIN_ORIGIN)
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
    }

    @Test
    fun `목록에 없는 origin의 preflight는 거절된다`() {
        webTestClient.options().uri("/api/auth/me")
            .header(HttpHeaders.ORIGIN, "https://not-allowed.example.com")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectStatus().isForbidden
    }

    private companion object {
        const val ADMIN_ORIGIN = "https://admin.artel.kr"
    }
}
