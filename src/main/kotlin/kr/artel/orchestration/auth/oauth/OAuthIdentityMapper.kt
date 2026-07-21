package kr.artel.orchestration.auth.oauth

import kr.artel.orchestration.auth.service.OAuthIdentity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken

interface OAuthIdentityMapper {
    val registrationId: String

    fun map(authentication: OAuth2AuthenticationToken): OAuthIdentity
}
