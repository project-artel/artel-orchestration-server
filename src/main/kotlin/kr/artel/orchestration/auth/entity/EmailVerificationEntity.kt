package kr.artel.orchestration.auth.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 한 번의 이메일 확인 발급.
 *
 * 주소마다 한 건이 아니라 발급마다 한 건이다. 같은 주소로 여러 번 요청해도 앞의 것을 지우지
 * 않는다 — 마지막 것만 남기면 먼저 받은 메일로 확인하려던 사람이 실패한다.
 *
 * 토큰 원문은 여기 없다. [tokenHash] 만 담고, 원문은 발급 응답을 지나 [MailSender] 로만 나간다.
 * 원문을 저장하면 이 테이블을 읽을 수 있는 사람이 남의 주소를 확정할 수 있어, 확인이 확인이
 * 아니게 된다.
 *
 * R2DBC 는 연관관계 매핑을 지원하지 않으므로 사용자 참조는 외래키 값([appUserId])으로 갖는다.
 */
@Table("email_verification")
data class EmailVerificationEntity(
    @Id
    val id: Long? = null,

    @Column("app_user_id")
    val appUserId: Long,

    /** 확인하려는 주소. 소문자로 정규화해 저장한다. */
    @Column("email")
    val email: String,

    /** 토큰 원문의 SHA-256 을 hex 로 담는다. 64자다. */
    @Column("token_hash")
    val tokenHash: String,

    @Column("created_at")
    val createdAt: Instant,

    @Column("expires_at")
    val expiresAt: Instant,

    /** 이 토큰으로 주소가 확정된 시각. 한 번 쓴 토큰은 다시 통하지 않는다. */
    @Column("consumed_at")
    val consumedAt: Instant? = null
)
