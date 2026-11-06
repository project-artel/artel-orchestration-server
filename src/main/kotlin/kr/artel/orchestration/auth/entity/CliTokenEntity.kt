package kr.artel.orchestration.auth.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** `cli_token.name` 의 컬럼 폭. */
const val MAX_CLI_TOKEN_NAME_LENGTH = 100

/**
 * CLI 가 API 를 부를 때 쓰는, 폐기할 수 있는 자격증명 하나.
 *
 * 상태 있는 토큰이라는 점이 세션 JWT 와 다른 전부다. 인증은 매 요청에 이 행을 읽어
 * [revokedAt] 과 [expiresAt] 을 함께 보므로, [revokedAt] 을 채우면 그 다음 요청이 401 이 된다.
 * 캐시가 없기 때문에 폐기가 곧바로 듣는다.
 *
 * 토큰 원문은 여기 없다. [tokenHash] 만 담고, 원문은 발급 응답 한 번으로만 나간다. 원문을
 * 저장하면 이 테이블을 읽을 수 있는 사람이 남의 계정으로 API 를 부를 수 있어 폐기가 무의미해진다.
 *
 * R2DBC 는 연관관계 매핑을 지원하지 않으므로 사용자 참조는 외래키 값([appUserId])으로 갖는다.
 */
@Table("cli_token")
data class CliTokenEntity(
    @Id
    val id: Long? = null,

    @Column("app_user_id")
    val appUserId: Long,

    /** 사람이 붙인 이름. 어느 토큰을 폐기할지 고르는 유일한 단서다. */
    @Column("name")
    val name: String,

    /** 토큰 원문의 SHA-256 을 hex 로 담는다. 64자다. */
    @Column("token_hash")
    val tokenHash: String,

    /** 이 토큰이 여는 범위. 지금은 `full` 하나뿐이고 아무 코드도 읽지 않는다. */
    @Column("scope")
    val scope: String,

    @Column("created_at")
    val createdAt: Instant,

    /** 마지막으로 이 토큰이 요청을 통과시킨 시각. 5분 해상도다. */
    @Column("last_used_at")
    val lastUsedAt: Instant? = null,

    /** null 이면 만료가 없다. */
    @Column("expires_at")
    val expiresAt: Instant? = null,

    /** 폐기된 시각. 채워지면 이 토큰으로는 더 이상 아무 요청도 통하지 않는다. */
    @Column("revoked_at")
    val revokedAt: Instant? = null
)
