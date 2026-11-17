package kr.artel.orchestration.wishlist.repository

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant

/** `wishlist_signup`(V185). 소개 사이트에서 방문자가 남긴 이메일을 둔다. */
@Repository
class WishlistSignupRepository(
    private val databaseClient: DatabaseClient
) {
    /**
     * 없는 주소면 넣고 넣은 행 수(1)를, 이미 있으면 아무것도 바꾸지 않고 0 을 돌려준다.
     *
     * 이미 있는 행은 `locale` 과 `consented_at` 도 덮어쓰지 않는다. 처음 동의한 시각과 그때 화면의 언어를 남긴다.
     * `created_at` 은 [consentedAt] 과 같은 값으로 넣는다 — 동의 없이는 행이 생기지 않는다.
     */
    suspend fun insertIfAbsent(email: String, locale: String?, consentedAt: Instant): Long =
        databaseClient.sql(
            """
            INSERT INTO wishlist_signup (email, locale, consented_at, created_at)
            VALUES (:email, :locale, :consentedAt, :consentedAt)
            ON CONFLICT (email) DO NOTHING
            """
        )
            .bind("email", email)
            .let { spec -> if (locale == null) spec.bindNull("locale", String::class.java) else spec.bind("locale", locale) }
            .bind("consentedAt", consentedAt)
            .fetch().rowsUpdated().awaitSingle()
}
