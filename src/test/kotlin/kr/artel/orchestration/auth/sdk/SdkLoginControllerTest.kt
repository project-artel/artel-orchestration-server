package kr.artel.orchestration.auth.sdk

import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.common.error.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

class SdkLoginControllerTest {
    private val controller = SdkLoginController(AuthProperties(
        frontendUrl = "https://console.example/", issuer = "test", audience = "test", jwtSecret = "t".repeat(32)
    ))
    private val state = "s".repeat(43)
    private val challenge = "c".repeat(43)

    @Test
    fun `HTTP 요청에서 임의의 redirect 주소를 받지 않는다`() {
        WebTestClient.bindToController(controller).build().get()
            .uri("/api/auth/sdk/login?port=51234&state=$state&challenge=$challenge&redirect_uri=https://other.example")
            .exchange()
            .expectStatus().isFound
            .expectHeader().valueEquals("Location",
                "https://console.example/sdk-login?port=51234&state=$state&challenge=$challenge")
    }

    @Test
    fun `서버에 설정된 console로 PKCE 요청을 전달한다`() {
        val response = controller.login(51234, state, challenge)
        assertThat(response.statusCode.value()).isEqualTo(302)
        assertThat(response.headers.location.toString()).isEqualTo(
            "https://console.example/sdk-login?port=51234&state=$state&challenge=$challenge"
        )
        assertThat(response.headers.cacheControl).isEqualTo("no-store")
    }

    @Test
    fun `잘못된 port와 state와 challenge를 거절한다`() {
        for (port in listOf(0, 65536)) {
            assertThrows(BadRequestException::class.java) { controller.login(port, state, challenge) }
        }
        assertThrows(BadRequestException::class.java) { controller.login(51234, "", challenge) }
        assertThrows(BadRequestException::class.java) { controller.login(51234, state, "https://other.example") }
    }
}
