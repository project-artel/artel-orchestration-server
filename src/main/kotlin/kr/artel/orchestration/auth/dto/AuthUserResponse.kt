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
    /** 사용자가 고른 이름. 처음 로그인할 때 제공자 이름으로 정해지므로 비어 있지 않다. */
    val nickname: String,
    /** 같은 [nickname]을 쓰는 사람들을 가르는 번호. 화면에 나가는 `nickname#userTag`는 클라이언트가 붙인다. */
    val userTag: String,
    /**
     * [email]이 이 계정의 것으로 확정됐는지. false면 그 주소로는 초대를 받을 수 없다 —
     * `ProjectInvitationService`가 확인을 마친 주소로만 초대함을 낸다.
     */
    val emailVerified: Boolean,
    /**
     * 확인을 기다리는 주소. 사용자가 새 주소를 넣었지만 아직 코드를 넣지 않은 동안 채워진다.
     * [email]과 다를 수 있고, 둘 다 있으면 [email]이 지금 통하는 주소다.
     */
    val pendingEmail: String?,
    /** 최근 로그인한 제공자가 앞에 오도록 정렬된다. */
    val identities: List<LinkedIdentityResponse>
)

/** `PUT /api/auth/me/locale` 요청 본문. */
data class UpdateLocaleRequest(
    val locale: String
)

/**
 * `PUT /api/auth/me/profile` 요청 본문.
 *
 * user_tag는 서버가 배정하므로 여기 담을 수 없다. nickname 타입이 nullable인 것은 지울 수 있어서가
 * 아니라, 빠뜨렸거나 null을 보낸 요청을 파싱 오류가 아니라 400 `invalid_nickname`으로 답하기 위해서다.
 */
data class UpdateProfileRequest(
    val nickname: String? = null
)

/** `POST /api/auth/me/email` 요청 본문. 이 주소는 아직 계정의 것이 아니다. */
data class RegisterEmailRequest(
    val email: String
)

/** `POST /api/auth/me/email/verify` 요청 본문. */
data class VerifyEmailRequest(
    val token: String
)

data class LinkedIdentityResponse(
    val provider: String,
    val login: String,
    val displayName: String,
    val avatarUrl: String?
)
