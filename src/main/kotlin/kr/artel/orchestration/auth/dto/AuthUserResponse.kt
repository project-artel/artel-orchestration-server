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
    /** 사용자가 고른 이름. null이면 아직 고르지 않은 것이다. */
    val nickname: String?,
    /** BattleTag. 선택 값이라 null일 수 있다. */
    val battleTag: String?,
    /** 최근 로그인한 제공자가 앞에 오도록 정렬된다. */
    val identities: List<LinkedIdentityResponse>
)

/** `PUT /api/auth/me/locale` 요청 본문. */
data class UpdateLocaleRequest(
    val locale: String
)

/**
 * `PUT /api/auth/me/profile` 요청 본문. 두 필드 모두 nullable이고, null을 보내면 그 값을 지운다.
 * 필드를 아예 안 보내도 기본값이 null이라 같은 결과다 — 이 엔드포인트는 있는 값을 통째로 덮어쓴다.
 */
data class UpdateProfileRequest(
    val nickname: String? = null,
    val battleTag: String? = null
)

data class LinkedIdentityResponse(
    val provider: String,
    val login: String,
    val displayName: String,
    val avatarUrl: String?
)
