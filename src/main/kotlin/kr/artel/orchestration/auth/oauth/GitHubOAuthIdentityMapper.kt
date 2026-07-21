package kr.artel.orchestration.auth.oauth

import kr.artel.orchestration.auth.service.OAuthIdentity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Component

@Component
class GitHubOAuthIdentityMapper : OAuthIdentityMapper {
    override val registrationId: String = "github"

    override fun map(authentication: OAuth2AuthenticationToken): OAuthIdentity {
        val attributes = authentication.principal.attributes
        val providerUserId = attributes["id"]?.toString()
            ?: error("GitHub did not return a user id")
        val login = attributes["login"]?.toString()
            ?: error("GitHub did not return a login")

        return OAuthIdentity(
            provider = registrationId,
            providerUserId = providerUserId,
            login = login,
            displayName = attributes["name"]?.toString()?.takeIf { it.isNotBlank() } ?: login,
            avatarUrl = attributes["avatar_url"]?.toString(),
            email = attributes["email"]?.toString()
        )
    }
}
