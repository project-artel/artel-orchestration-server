package kr.artel.orchestration.auth.repository

import kr.artel.orchestration.auth.entity.OAuthUserEntity
import org.springframework.data.jpa.repository.JpaRepository

interface OAuthUserRepository : JpaRepository<OAuthUserEntity, Long> {
    fun findByProviderAndProviderUserId(provider: String, providerUserId: String): OAuthUserEntity?
}
