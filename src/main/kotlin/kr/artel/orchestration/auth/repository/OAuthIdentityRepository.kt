package kr.artel.orchestration.auth.repository

import kr.artel.orchestration.auth.entity.OAuthIdentityEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface OAuthIdentityRepository : ReactiveCrudRepository<OAuthIdentityEntity, Long> {
    fun findByProviderAndProviderUserId(
        provider: String,
        providerUserId: String
    ): Mono<OAuthIdentityEntity>

    /** 최근 로그인한 신원이 앞에 오도록 정렬해 반환한다. */
    fun findByAppUserIdOrderByLastLoginAtDesc(appUserId: Long): Flux<OAuthIdentityEntity>
}
