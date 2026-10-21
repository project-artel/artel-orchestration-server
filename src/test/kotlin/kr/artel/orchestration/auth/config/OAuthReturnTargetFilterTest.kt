package kr.artel.orchestration.auth.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.mock.web.server.MockWebSession
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URI

class OAuthReturnTargetFilterTest {
    private val properties = AuthProperties(
        frontendUrl = "https://console.artel.kr", issuer = "test", audience = "test", jwtSecret = "t".repeat(32)
    )
    private val filter = OAuthReturnTargetFilter(properties)
    private val chain = WebFilterChain { Mono.empty() }

    @Test
    fun `onboarding origin의 경로와 query와 fragment를 유지한다`() {
        val target = "https://artel.kr/en/sdk?step=2#install"
        assertThat(OAuthReturnTargetFilter.validatedTarget(target, properties.onboardingUrl)).isEqualTo(URI(target))
    }

    @Test
    fun `외부 주소와 유사한 host와 다른 port와 userinfo를 거절한다`() {
        for (value in listOf("https://evil.example", "//artel.kr/sdk", "https://artel.kr.evil.example", "https://artel.kr@evil.example", "https://evil@artel.kr", "http://artel.kr", "https://artel.kr:444", "javascript:alert(1)", "bad uri")) {
            assertThat(OAuthReturnTargetFilter.validatedTarget(value, properties.onboardingUrl)).isNull()
        }
    }

    @Test
    fun `OAuth 시작 시 저장한 복귀 주소를 callback에서 한 번만 사용한다`() {
        val session = MockWebSession()
        val start = MockServerWebExchange.builder(MockServerHttpRequest.get("/oauth2/authorization/github")
            .queryParam("return_to", "https://artel.kr/en/sdk?step=2#install")).session(session).build()
        filter.filter(start, chain).block()
        val callback = MockServerWebExchange.builder(MockServerHttpRequest.get("/login/oauth2/code/github")
            .queryParam("return_to", "https://evil.example")).session(session).build()
        filter.filter(callback, chain).block()
        assertThat(OAuthReturnTargetFilter.destination(callback, properties).host).isEqualTo("artel.kr")
        assertThat(OAuthReturnTargetFilter.destination(callback, properties, "oauth").toString())
            .isEqualTo("https://artel.kr/en/sdk?step=2&auth_error=oauth#install")
        val next = MockServerWebExchange.builder(MockServerHttpRequest.get("/login/oauth2/code/github")).session(session).build()
        filter.filter(next, chain).block()
        assertThat(OAuthReturnTargetFilter.destination(next, properties).toString()).isEqualTo(properties.frontendOrigin)
        assertThat(OAuthReturnTargetFilter.destination(next, properties, "oauth").toString())
            .isEqualTo("https://console.artel.kr/login?error=oauth")
    }

    @Test
    fun `복귀 주소 없이 시작하면 이전 onboarding 복귀 주소를 지운다`() {
        val session = MockWebSession()
        val start = MockServerWebExchange.builder(MockServerHttpRequest.get("/oauth2/authorization/github")
            .queryParam("return_to", "https://artel.kr/")).session(session).build()
        filter.filter(start, chain).block()
        val restart = MockServerWebExchange.builder(MockServerHttpRequest.get("/oauth2/authorization/github")).session(session).build()
        filter.filter(restart, chain).block()
        val callback = MockServerWebExchange.builder(MockServerHttpRequest.get("/login/oauth2/code/github")).session(session).build()
        filter.filter(callback, chain).block()
        assertThat(OAuthReturnTargetFilter.destination(callback, properties).toString()).isEqualTo(properties.frontendOrigin)
    }
}
