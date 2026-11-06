package kr.artel.orchestration.auth.repository

import kr.artel.orchestration.auth.entity.EmailVerificationEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface EmailVerificationRepository : CoroutineCrudRepository<EmailVerificationEntity, Long> {

    /**
     * 아직 쓰이지 않았고 기한도 지나지 않은 발급을 토큰 해시로 찾는다.
     *
     * 해시가 unique 라 최대 한 건이다. 조건을 여기 함께 거는 것은, 없는 토큰·만료된 토큰·이미 쓴
     * 토큰을 서비스가 하나의 오류로 답하기 때문이다. 셋을 갈라 답하면 토큰을 찍어 보는 쪽에
     * "이 토큰은 있었다"를 알려 주게 된다.
     */
    @Query(
        """
        SELECT * FROM email_verification
        WHERE token_hash = :tokenHash AND consumed_at IS NULL AND expires_at > :now
        """
    )
    suspend fun findUsableByTokenHash(tokenHash: String, now: Instant): EmailVerificationEntity?

    /**
     * 계정 설정 화면이 그리는 "확인을 기다리는 주소". 가장 최근 발급 하나만 낸다.
     *
     * 발급이 여러 건 살아 있을 수 있다 — 주소를 바꿔 다시 요청해도 앞의 것을 지우지 않기
     * 때문이다. 화면에는 마지막으로 요청한 것을 보여 준다. 나머지 토큰도 여전히 통하고, 그것으로
     * 확인하면 그 주소가 확정된다.
     */
    @Query(
        """
        SELECT * FROM email_verification
        WHERE app_user_id = :appUserId AND consumed_at IS NULL AND expires_at > :now
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun findLatestPending(appUserId: Long, now: Instant): EmailVerificationEntity?

    /**
     * 토큰을 쓴 것으로 표시한다. `@Modifying` 이 있어야 영향 행 수가 돌아온다.
     *
     * 읽고 나서 쓰면 그 사이에 같은 토큰이 한 번 더 들어온다. `WHERE consumed_at IS NULL` 이
     * 직렬화 지점이라, 동시에 들어온 확인 중 하나만 1 을 받는다. `ProjectInvitationRepository.settle`
     * 과 같은 모양이다.
     */
    @Modifying
    @Query(
        """
        UPDATE email_verification
        SET consumed_at = :consumedAt
        WHERE id = :id AND consumed_at IS NULL
        """
    )
    suspend fun consume(id: Long, consumedAt: Instant): Int
}
