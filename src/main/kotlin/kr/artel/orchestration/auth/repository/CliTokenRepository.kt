package kr.artel.orchestration.auth.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.entity.CliTokenEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface CliTokenRepository : CoroutineCrudRepository<CliTokenEntity, Long> {

    /**
     * 지금 쓸 수 있는 토큰을 해시로 찾는다. 인증이 매 요청에 부르는 조회다.
     *
     * 해시가 unique 라 최대 한 건이다. 폐기와 만료를 여기 함께 거는 것은, 없는 토큰·폐기된 토큰·
     * 만료된 토큰을 하나의 결과(null)로 묶기 위해서다. 셋을 갈라 답하면 토큰을 찍어 보는 쪽에
     * "그 토큰은 있었다"를 알려 주게 된다.
     */
    @Query(
        """
        SELECT * FROM cli_token
        WHERE token_hash = :tokenHash
          AND revoked_at IS NULL
          AND (expires_at IS NULL OR expires_at > :now)
        """
    )
    suspend fun findUsableByTokenHash(tokenHash: String, now: Instant): CliTokenEntity?

    /** 목록 화면이 그리는 자기 토큰 전부. 폐기된 것도 낸다 — 폐기했다는 사실이 화면의 정보다. */
    @Query("SELECT * FROM cli_token WHERE app_user_id = :appUserId ORDER BY created_at DESC, id DESC")
    fun findAllByOwner(appUserId: Long): Flow<CliTokenEntity>

    /**
     * 토큰을 폐기한다. 영향 행이 0 이면 없는 id 이거나, 남의 토큰이거나, 이미 폐기된 토큰이다.
     * 서비스는 셋을 404 하나로 답한다 — 셋을 가르면 어느 id 가 존재하는지를 알려 주게 된다.
     *
     * 그래서 같은 토큰을 두 번 DELETE 하면 두 번째는 204 가 아니라 404 다. idempotent 하지 않다.
     *
     * `app_user_id` 조건을 서비스가 아니라 UPDATE 에 둔다. 서비스 쪽 검사를 빠뜨리면 남의 행이
     * 조용히 폐기되지만, 여기 있으면 빠뜨릴 수가 없다.
     */
    @Modifying
    @Query(
        """
        UPDATE cli_token SET revoked_at = :revokedAt
        WHERE id = :id AND app_user_id = :appUserId AND revoked_at IS NULL
        """
    )
    suspend fun revoke(id: Long, appUserId: Long, revokedAt: Instant): Int

    /**
     * `last_used_at` 을 앞으로 민다.
     *
     * 부르는 쪽이 방금 읽은 행으로 이미 한 번 거르지만 `WHERE` 를 여기에도 남긴다. 이 메서드를
     * 다른 곳에서 부르게 되어도 갱신 주기가 지켜지고, 같은 토큰으로 동시에 들어온 요청 둘이
     * 서로를 덮어쓰지 않는다.
     */
    @Modifying
    @Query(
        """
        UPDATE cli_token SET last_used_at = :now
        WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :staleBefore)
        """
    )
    suspend fun touchLastUsed(id: Long, now: Instant, staleBefore: Instant): Int
}
