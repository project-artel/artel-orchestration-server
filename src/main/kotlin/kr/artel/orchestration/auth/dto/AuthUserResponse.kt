package kr.artel.orchestration.auth.dto

/**
 * 현재 세션의 사용자. 프로필을 토큰이 아니라 DB에서 읽으므로 항상 최신이며,
 * 연결된 제공자 신원을 모두 담는다.
 */
data class AuthUserResponse(
    val id: String,
    val displayName: String,
    val email: String?,
    /** 최근 로그인한 제공자가 앞에 오도록 정렬된다. */
    val identities: List<LinkedIdentityResponse>
)

data class LinkedIdentityResponse(
    val provider: String,
    val login: String,
    val displayName: String,
    val avatarUrl: String?
)
