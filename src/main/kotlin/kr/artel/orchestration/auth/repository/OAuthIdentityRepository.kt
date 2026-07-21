package kr.artel.orchestration.auth.repository

import kr.artel.orchestration.auth.entity.OAuthIdentityEntity
import org.springframework.data.jpa.repository.JpaRepository

interface OAuthIdentityRepository : JpaRepository<OAuthIdentityEntity, Long> {
    fun findByProviderAndProviderUserId(
        provider: String,
        providerUserId: String
    ): OAuthIdentityEntity?
}
