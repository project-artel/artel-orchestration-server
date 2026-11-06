package kr.artel.orchestration.auth.config

import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

/** refresh 쿠키가 붙는 경로. 재발급 요청 하나에만 실린다. */
const val REFRESH_PATH = "/api/auth/refresh"

/**
 * 세션 쿠키를 만드는 유일한 지점. 로그인 성공 핸들러, 재발급, 로그아웃이 같은 속성을 써야
 * 한다. 셋 중 하나라도 Path나 SameSite가 어긋나면 브라우저가 다른 쿠키로 취급해, 지운 줄 알았던
 * 쿠키가 남거나 재발급한 쿠키가 다음 요청에 실리지 않는다.
 */
@Component
class AuthCookies(private val properties: AuthProperties) {
    fun access(token: String): ResponseCookie =
        cookie(properties.cookieName, token, "/", properties.accessTokenTtl)

    /**
     * refresh 쿠키는 재발급 경로에만 실린다. 모든 API 요청에 30일짜리 자격증명을 함께 보내면
     * 프록시 로그와 서버 접근 로그 어디에나 남는다.
     */
    fun refresh(token: String): ResponseCookie =
        cookie(properties.refreshCookieName, token, REFRESH_PATH, properties.refreshTokenTtl)

    /** 로그아웃용. 두 쿠키를 같은 속성으로 즉시 만료시킨다. */
    fun cleared(): List<ResponseCookie> = listOf(
        cookie(properties.cookieName, "", "/", Duration.ZERO),
        cookie(properties.refreshCookieName, "", REFRESH_PATH, Duration.ZERO)
    )

    private fun cookie(name: String, value: String, path: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(properties.secureCookie)
            .sameSite("Lax")
            .path(path)
            .maxAge(maxAge)
            .build()
}
