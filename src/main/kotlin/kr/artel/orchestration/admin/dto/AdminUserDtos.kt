package kr.artel.orchestration.admin.dto

import java.time.Instant

/** 관리 화면의 사용자 한 줄. */
data class AdminUserResponse(
    val id: String,
    val displayName: String,
    val nickname: String,
    val userTag: String,
    /** 연락 주소(`app_user.email`). */
    val email: String?,
    /** 비밀번호 로그인에 쓰는 이메일. 비밀번호가 없는 계정이면 null 이다. */
    val loginEmail: String?,
    /** `USER`, `DEVELOPER`, `ADMIN` 중 하나. */
    val platformRole: String,
    val disabled: Boolean,
    val hasPassword: Boolean,
    val mustChangePassword: Boolean,
    /** 연결된 OAuth 제공자 이름(`github`). 이메일로만 가입했으면 비어 있다. */
    val oauthProviders: List<String>,
    val createdAt: Instant
)

/** `POST /api/admin/users` 요청 본문. [platformRole] 을 빠뜨리면 `USER` 다. */
data class CreateUserRequest(
    val email: String,
    val name: String,
    val platformRole: String? = null
)

/**
 * 임시 비밀번호가 실린 응답. 계정 생성과 비밀번호 초기화가 돌려준다. 임시 비밀번호는 이 응답에만
 * 한 번 실리고 서버는 해시만 남긴다.
 *
 * Spring 이 DEBUG 로그에 응답 객체를 `toString` 으로 남기므로 임시 비밀번호를 뺀다.
 */
data class UserWithTemporaryPasswordResponse(
    val user: AdminUserResponse,
    val temporaryPassword: String
) {
    override fun toString(): String = "UserWithTemporaryPasswordResponse(userId=${user.id})"
}

/** `PATCH /api/admin/users/{userId}` 요청 본문. null 인 필드는 바꾸지 않는다. */
data class UpdateUserRequest(
    val platformRole: String? = null,
    val disabled: Boolean? = null
)
