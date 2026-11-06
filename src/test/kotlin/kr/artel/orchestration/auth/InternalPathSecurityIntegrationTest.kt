package kr.artel.orchestration.auth

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * 내부 서버-투-서버 경로를 `/internal/`로 옮기면서 버린 옛 주소들이 실제로 닫혔는지 본다(ARTEL-265).
 *
 * 이 변경이 만들어내는 새로운 동작은 이것 하나다. 반대편("`/internal/`가 무인증으로 열려 있다")은
 * 경로만 갈아끼운 기존 통합 테스트들이 401이 아님을 넘어 실제 2xx까지 단언하므로 여기서 다시 쓰지
 * 않는다. 사용자용 `/api/projects/{id}/test-case-spec/download`의 인증 유지도
 * `TestCaseSpecHttpIntegrationTest`가 이미 덮는다.
 *
 * 기대 상태코드는 **정확히 401**이다. `authorizeExchange`는 `DispatcherHandler`가 라우팅하기 전에
 * 도는 `WebFilter`라, 핸들러가 사라진 경로여도 `anyExchange().authenticated()`에 먼저 걸린다.
 * "401 또는 404"로 느슨하게 단언하면 누군가 옛 경로를 permitAll에 되돌려 놓아도 404로 통과해버린다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureWebTestClient
class InternalPathSecurityIntegrationTest {

    @Autowired private lateinit var webTestClient: WebTestClient

    @Test
    fun `옛 llm-usage 수집 경로는 더 이상 열려 있지 않다`() {
        webTestClient.post().uri("/api/orchestration/llm-usage")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"records":[]}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `옛 액션 전달 경로는 더 이상 열려 있지 않다`() {
        webTestClient.post().uri("/api/orchestration/action/any-instance")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"type":"ACTION","id":1,"actions":[]}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `옛 knowledge 조회 경로는 더 이상 열려 있지 않다`() {
        webTestClient.get().uri("/api/knowledge?projectId=1")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `옛 테스트 명세 적재 경로는 더 이상 열려 있지 않다`() {
        webTestClient.post().uri("/api/test-case-spec/1")
            .contentType(MediaType.parseMediaType("text/csv"))
            .bodyValue("headerA,headerB\n1,2\n".toByteArray())
            .exchange()
            .expectStatus().isUnauthorized
    }
}
