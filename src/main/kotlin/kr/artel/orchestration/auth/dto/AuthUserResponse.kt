package kr.artel.orchestration.auth.dto

data class AuthUserResponse(
    val id: String,
    val provider: String,
    val login: String,
    val displayName: String,
    val avatarUrl: String?
)
