package kr.artel.orchestration.auth.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.entity.OAuthIdentityEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface OAuthIdentityRepository : CoroutineCrudRepository<OAuthIdentityEntity, Long> {
    suspend fun findByProviderAndProviderUserId(
        provider: String,
        providerUserId: String
    ): OAuthIdentityEntity?

    /** 최근 로그인한 신원이 앞에 오도록 정렬해 반환한다. */
    fun findByAppUserIdOrderByLastLoginAtDesc(appUserId: Long): Flow<OAuthIdentityEntity>
}
