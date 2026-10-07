package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.config.GitHubOAuthProperties
import org.springframework.stereotype.Service

/**
 * OAuth 로그인 성공 뒤, 쿠키를 주기 전에 거치는 판정. `SecurityConfig` 의 성공 핸들러가 부른다.
 *
 * - GitHub 가입이 닫혀 있으면 처음 보는 계정은 [OAuthSignupClosedException] 이다
 *   ([GitHubOAuthProperties.signupOpen], [NewAccountPolicy]).
 * - ADMIN 이 막은 계정은 [AccountDisabledException] 이다.
 *
 * 핸들러 안에 두지 않고 여기 두는 이유는 테스트다. 핸들러는 GitHub 왕복 없이는 부를 수 없지만 이
 * 판정은 신원 하나로 확인할 수 있다.
 */
@Service
class OAuthLoginService(
    private val oauthUserService: OAuthUserService,
    private val accountStateService: AccountStateService,
    private val gitHubOAuthProperties: GitHubOAuthProperties
) {
    suspend fun signIn(identity: OAuthIdentity): AuthenticatedUser {
        val policy = if (gitHubOAuthProperties.signupOpen) {
            NewAccountPolicy.CREATE
        } else {
            NewAccountPolicy.ADMIN_REGISTERED_EMAIL_ONLY
        }
        val user = oauthUserService.upsert(identity, policy)
        if (accountStateService.blockOf(user.userId.toLong()) == AccountBlock.DISABLED) {
            throw AccountDisabledException()
        }
        return user
    }
}
