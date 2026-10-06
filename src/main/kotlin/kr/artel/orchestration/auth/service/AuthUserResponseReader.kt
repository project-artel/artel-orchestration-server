package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.dto.AuthUserResponse
import kr.artel.orchestration.auth.dto.LinkedIdentityResponse
import kr.artel.orchestration.auth.repository.LocalCredentialRepository
import org.springframework.stereotype.Component

/**
 * `GET /api/auth/me` 의 본문을 DB 에서 읽어 만든다. 로그인과 가입 응답도 같은 본문을 돌려주므로,
 * 세 자리가 모양을 따로 만들지 않도록 여기 한 곳에 둔다.
 */
@Component
class AuthUserResponseReader(
    private val oauthUserService: OAuthUserService,
    private val emailVerificationService: EmailVerificationService,
    private val localCredentialRepository: LocalCredentialRepository
) {
    /** 사용자가 없으면 null 이다. 호출부가 401 로 답한다. */
    suspend fun read(userId: Long): AuthUserResponse? {
        val profile = oauthUserService.findProfile(userId) ?: return null
        val credential = localCredentialRepository.findById(userId)
        return AuthUserResponse(
            id = profile.userId,
            displayName = profile.displayName,
            email = profile.email,
            locale = profile.locale,
            platformRole = profile.platformRole,
            nickname = profile.nickname,
            userTag = profile.userTag,
            emailVerified = profile.emailVerifiedAt != null,
            pendingEmail = emailVerificationService.pendingEmail(userId),
            identities = profile.identities.map {
                LinkedIdentityResponse(
                    provider = it.provider,
                    login = it.login,
                    displayName = it.displayName,
                    avatarUrl = it.avatarUrl
                )
            },
            hasPassword = credential != null,
            mustChangePassword = credential?.mustChangePassword == true
        )
    }
}
