package kr.artel.orchestration.auth.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * Artel 사용자 본체. 연결된 OAuth 제공자와 무관하게 안정적으로 유지되며,
 * 이 id가 JWT의 sub 클레임이 된다.
 */
@Table("app_user")
data class AppUserEntity(
    @Id
    val id: Long? = null,

    @Column("display_name")
    val displayName: String,

    @Column("email")
    val email: String? = null,

    /**
     * [email] 이 이 계정의 것으로 확정된 시각. null 이면 아직 확인되지 않았고, 초대 수신 판정에서
     * 그 계정은 이메일이 없는 것과 같이 다뤄진다.
     *
     * 제공자가 준 주소는 가입 시점에 확인된 것으로 본다. GitHub 은 자기가 확인을 마친 주소만
     * 공개 이메일로 고를 수 있게 하므로, 사용자가 적어 넣은 값이 아니다.
     */
    @Column("email_verified_at")
    val emailVerifiedAt: Instant? = null,

    /** 홈 UI 표시 언어. null이면 아직 고르지 않았고 클라이언트 기본 로직을 따른다. */
    @Column("locale")
    val locale: String? = null,

    /**
     * 사용자가 고른 이름. [displayName]과 달리 로그인해도 덮어써지지 않는다.
     * null이면 아직 고르지 않은 것이다.
     */
    @Column("nickname")
    val nickname: String? = null,

    /** BattleTag(예: `Name#1234`). 선택 값이라 null일 수 있다. 형식 검증은 API 경계에서 끝낸다. */
    @Column("battle_tag")
    val battleTag: String? = null,

    @Column("created_at")
    val createdAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant
)
