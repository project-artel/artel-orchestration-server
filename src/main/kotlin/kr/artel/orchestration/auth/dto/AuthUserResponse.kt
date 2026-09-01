package kr.artel.orchestration.auth.dto

/**
 * 현재 세션의 사용자. 프로필을 토큰이 아니라 DB에서 읽으므로 항상 최신이며,
 * 연결된 제공자 신원을 모두 담는다.
 */
data class AuthUserResponse(
    val id: String,
    val displayName: String,
    val email: String?,
    /** 홈 UI 표시 언어. null이면 사용자가 아직 고르지 않은 것이다. */
    val locale: String?,
    /**
     * 프로젝트 밖의 등급. `USER` 또는 `DEVELOPER`.
     *
     * 화면은 이 값으로 인가를 판단하지 않는다. 무엇을 요청할지 고르는 데만 쓴다 — 판단은 서버가
     * 하고, `DEVELOPER`라고 적힌 응답을 받아도 서버가 열지 않은 것은 열리지 않는다.
     */
    val platformRole: String,
    /** 최근 로그인한 제공자가 앞에 오도록 정렬된다. */
    val identities: List<LinkedIdentityResponse>
)

/** `PUT /api/auth/me/locale` 요청 본문. */
data class UpdateLocaleRequest(
    val locale: String
)

data class LinkedIdentityResponse(
    val provider: String,
    val login: String,
    val displayName: String,
    val avatarUrl: String?
)
