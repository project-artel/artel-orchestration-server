package kr.artel.orchestration.auth.dto

/**
 * `GET /api/auth/providers` 응답. 로그인 화면이 무엇을 보일지 고르는 데 쓴다.
 *
 * [password] 는 언제나 true 다. 필드로 두는 이유는 화면이 버튼 목록을 이 응답 하나로 그리게 하려는 것이다.
 */
data class AuthProvidersResponse(
    val password: Boolean,
    /** GitHub OAuth 두 변수가 다 있을 때만 true 다. false 면 `/oauth2/authorization/github` 는 404 다. */
    val github: Boolean,
    /** 지금 `POST /api/auth/signup` 이 받아들여지는지. 첫 사용자가 아직 없거나 `ARTEL_SIGNUP_OPEN=true` 일 때다. */
    val signupOpen: Boolean
)

/**
 * `POST /api/auth/signup` 요청 본문. 값 검증은 `PasswordAccountService` 가 한다.
 *
 * 비밀번호가 로그로 나가지 않도록 [toString] 을 덮어쓴다. `logging.level.org.springframework.web: DEBUG`
 * 에서 Spring 이 디코드한 본문을 `toString` 으로 남기기 때문이다.
 */
data class SignupRequest(
    val email: String,
    val password: String,
    /** 표시 이름이자 nickname. */
    val name: String
) {
    override fun toString(): String = "SignupRequest(email=$email, name=$name)"
}

/** `POST /api/auth/login` 요청 본문. [toString] 은 [SignupRequest] 와 같은 이유로 덮어쓴다. */
data class LoginRequest(
    val email: String,
    val password: String
) {
    override fun toString(): String = "LoginRequest(email=$email)"
}

/** `POST /api/auth/password` 요청 본문. [toString] 은 [SignupRequest] 와 같은 이유로 덮어쓴다. */
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
) {
    override fun toString(): String = "ChangePasswordRequest"
}
