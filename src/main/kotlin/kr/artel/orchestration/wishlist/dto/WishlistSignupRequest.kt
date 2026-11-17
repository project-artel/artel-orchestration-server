package kr.artel.orchestration.wishlist.dto

/**
 * `POST /wishlist` 요청 본문. 값 검증은 `WishlistSignupService` 가 한다.
 *
 * 필드가 모두 nullable 인 이유는 빠진 `email` 과 `consent` 도 각자의 code(`invalid_email`, `consent_required`)로
 * 답하려는 것이다. non-null 로 두면 Jackson 이 디코드 단계에서 막아 400 `invalid_request` 가 되고, 화면은 같은 실수에
 * 두 가지 code 를 받는다.
 */
data class WishlistSignupRequest(
    val email: String? = null,
    /** `ko` 또는 `en`. 보내지 않으면 null 로 저장한다. */
    val locale: String? = null,
    /** 개인정보 수집·이용 동의. `true` 가 아니면 400 `consent_required` 이고 저장하지 않는다. */
    val consent: Boolean? = null,
    /**
     * honeypot. 소개 사이트는 이 칸을 사람에게 보이지 않게 그린다. 공백이 아닌 값이 오면 204 로 답하고 저장하지 않는다.
     */
    val website: String? = null
)
