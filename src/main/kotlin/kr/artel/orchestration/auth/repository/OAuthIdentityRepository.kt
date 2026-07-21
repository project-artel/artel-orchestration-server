package kr.artel.orchestration.auth.repository

import kr.artel.orchestration.auth.entity.OAuthIdentityEntity
import org.springframework.data.jpa.repository.JpaRepository

interface OAuthIdentityRepository : JpaRepository<OAuthIdentityEntity, Long> {
    fun findByProviderAndProviderUserId(
        provider: String,
        providerUserId: String
    ): OAuthIdentityEntity?

    /** 최근 로그인한 신원이 앞에 오도록 정렬해 반환한다. */
    fun findByAppUserIdOrderByLastLoginAtDesc(appUserId: Long): List<OAuthIdentityEntity>
}
