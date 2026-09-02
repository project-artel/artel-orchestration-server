package kr.artel.orchestration.auth.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * `app_user.nickname` 컬럼 폭. 요청 검증과 제공자 display name truncation 이 같은 값을 봐야 해서
 * 여기 한 곳에 둔다.
 */
const val MAX_NICKNAME_LENGTH = 64

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

    /** 프로젝트 밖의 등급. 값은 [PlatformRole]이고, 읽지 못하는 값은 [USER]로 다룬다. */
    @Column("platform_role")
    val platformRole: String = PlatformRole.USER.name,
    /**
     * 사용자가 고른 이름. [displayName]과 달리 로그인해도 덮어써지지 않는다.
     * 이름이 없는 사용자는 없다 — 처음 로그인할 때 제공자 display name 에서 만들어 넣는다.
     */
    @Column("nickname")
    val nickname: String,

    /**
     * 같은 [nickname]을 쓰는 사람들을 가르는 번호. 0으로 채운 숫자 문자열이고 기본 네 자리다.
     * 서버가 배정하며 클라이언트는 보낼 수 없다.
     */
    @Column("user_tag")
    val userTag: String,

    @Column("created_at")
    val createdAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant
)

/**
 * 프로젝트 밖의 등급.
 *
 * [kr.artel.orchestration.project.entity.ProjectRole]과 층이 다르다. 저쪽은 한 프로젝트 안에서
 * 무엇을 할 수 있는지를 정하고, 이쪽은 어느 프로젝트를 볼 수 있는지를 정한다. 그래서 프로젝트
 * 역할로는 "모든 프로젝트를 본다"를 쓸 수 없고, 이 등급이 그 자리다.
 *
 * [DEVELOPER]가 여는 것은 조회뿐이다. 쓰기는 이 등급과 무관하게 `project_member`를 그대로
 * 요구한다 — 근거는 [kr.artel.orchestration.auth.service.PlatformAccessService]에 있다.
 */
enum class PlatformRole {
    USER,
    DEVELOPER
}
