package kr.artel.orchestration.auth.oauth

import kr.artel.orchestration.auth.service.OAuthIdentity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Service

@Service
class OAuthIdentityResolver(mappers: List<OAuthIdentityMapper>) {
    private val mappersByRegistrationId = mappers.associateBy { it.registrationId }

    init {
        require(mappersByRegistrationId.size == mappers.size) {
            "OAuth identity mapper registration IDs must be unique"
        }
    }

    fun resolve(authentication: OAuth2AuthenticationToken): OAuthIdentity {
        val registrationId = authentication.authorizedClientRegistrationId
        val mapper = mappersByRegistrationId[registrationId]
            ?: error("Unsupported OAuth provider: $registrationId")
        return mapper.map(authentication)
    }
}
