package kr.artel.orchestration.wishlist.controller

import kr.artel.orchestration.auth.service.UNKNOWN_CLIENT_ADDRESS
import kr.artel.orchestration.wishlist.dto.WishlistSignupRequest
import kr.artel.orchestration.wishlist.service.WishlistSignupLimiter
import kr.artel.orchestration.wishlist.service.WishlistSignupService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * 소개 사이트(artel.kr)의 Wishlist 버튼. 방문자가 세션 없이 이메일을 남긴다.
 *
 * 인증이 없으므로 `/api` 아래에 두지 않는다(`.agents/docs/project.md` 규칙 2). 보안 체인도 따로다
 * (`SecurityConfig.wishlistSecurityWebFilterChain`) — 소개 사이트는 `credentials: 'include'` 로 부를 수 있어, 기본 체인에
 * 두면 만료된 세션 쿠키를 실은 방문자가 401 을 받는다.
 *
 * 처음 남긴 주소와 이미 있는 주소가 같은 204 다. honeypot(`website`)이 채워진 요청도 204 이고 저장하지 않는다.
 * 클라이언트 주소마다 한 시간 30번을 넘으면 429 다. 한도는 본문 검사보다 먼저라 honeypot 요청도 센다.
 */
@RestController
@RequestMapping("/wishlist")
class WishlistSignupController(
    private val wishlistSignupService: WishlistSignupService,
    private val limiter: WishlistSignupLimiter
) {
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun joinWishlist(@RequestBody request: WishlistSignupRequest, exchange: ServerWebExchange) {
        limiter.acquire(clientAddressOf(exchange))
        wishlistSignupService.signup(request.email, request.locale, request.consent, request.website)
    }

    /** `PasswordAuthController.clientAddressOf` 와 같은 규칙이다. 주소를 모르면 한 묶음으로 센다. */
    private fun clientAddressOf(exchange: ServerWebExchange): String =
        exchange.request.remoteAddress?.hostString?.takeIf { it.isNotBlank() } ?: UNKNOWN_CLIENT_ADDRESS
}
