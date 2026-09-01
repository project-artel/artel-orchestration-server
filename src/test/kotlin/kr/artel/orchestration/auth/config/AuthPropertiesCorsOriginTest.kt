package kr.artel.orchestration.auth.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `corsAllowedOrigins` 가 설정값에 대체당하지 않는지 본다(ARTEL-702).
 *
 * 이 경계를 따로 두는 이유는 `CorsAllowedOriginIntegrationTest` 가 볼 수 없는 자리이기 때문이다.
 * 그 테스트는 `ARTEL_ALLOWED_ORIGINS` 가 없는 상태로 돌아 `application.yml` 값을 그대로 쓴다.
 * 실제로 깨진 배포는 그 환경변수가 있는 쪽이었고, 환경변수는 목록에 더해지는 것이 아니라 통째로
 * 대체한다. ARTEL-295 가 기본값에 `admin.artel.kr` 을 적고도 stage 에서 무효였던 것이 그래서다.
 */
class AuthPropertiesCorsOriginTest {

    @Test
    fun `설정이 좁아도 first-party origin 은 허용 목록에 남는다`() {
        // 2026-09-01 stage 가 실제로 들고 있던 모양. 통과하는 origin 이 frontend-url 하나뿐이었다.
        val properties = propertiesWith(
            frontendUrl = "https://home.stage.artel.kr",
            allowedOrigins = listOf("https://home.stage.artel.kr")
        )

        assertThat(properties.corsAllowedOrigins)
            .containsAll(AuthProperties.FIRST_PARTY_ORIGINS)
            .containsOnlyOnce("https://home.stage.artel.kr")
    }

    @Test
    fun `설정한 origin 은 first-party 목록에 더해진다`() {
        val properties = propertiesWith(
            frontendUrl = "https://home.stage.artel.kr",
            allowedOrigins = listOf("https://preview.example.com/")
        )

        assertThat(properties.corsAllowedOrigins)
            // 끝의 슬래시는 origin 이 아니다. 붙은 채로는 어떤 Origin 헤더와도 맞지 않는다.
            .contains("https://preview.example.com")
            .contains("https://admin.artel.kr")
    }

    @Test
    fun `빈 값은 허용 목록에 들어가지 않는다`() {
        // application.yml 의 기본값이 비어 있어, 환경변수 없이 뜨면 바인딩이 빈 문자열 하나를 준다.
        val properties = propertiesWith(
            frontendUrl = "http://localhost:5173",
            allowedOrigins = listOf("")
        )

        assertThat(properties.corsAllowedOrigins).doesNotContain("")
    }

    private fun propertiesWith(frontendUrl: String, allowedOrigins: List<String>) = AuthProperties(
        frontendUrl = frontendUrl,
        issuer = "test-issuer",
        audience = "test-audience",
        jwtSecret = "test-only-secret-that-is-at-least-32-bytes-long",
        allowedOrigins = allowedOrigins
    )
}
