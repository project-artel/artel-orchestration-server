package kr.artel.orchestration.auth.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** `local_credential.email` 컬럼 폭. RFC 5321 이 허용하는 주소 최대 길이다. */
const val MAX_LOGIN_EMAIL_LENGTH = 320

/**
 * 비밀번호 로그인 자격증명. `app_user` 하나에 최대 한 행이다.
 *
 * id 가 `app_user_id` 라 생성되는 값이 아니다. 그래서 새 행은 `save` 가 아니라
 * [kr.artel.orchestration.auth.repository.LocalCredentialRepository.insert] 로 넣는다 — `save` 는 id 가
 * 채워진 엔티티를 UPDATE 로 보내 아무 행도 만들지 않는다.
 */
@Table("local_credential")
data class LocalCredentialEntity(
    @Id
    @Column("app_user_id")
    val appUserId: Long,

    /** 소문자로 정규화된 로그인 이메일. */
    @Column("email")
    val email: String,

    /** BCrypt 해시. 이 값이 응답이나 로그로 나가지 않도록 [toString] 에서 뺀다. */
    @Column("password_hash")
    val passwordHash: String,

    @Column("must_change_password")
    val mustChangePassword: Boolean,

    @Column("password_changed_at")
    val passwordChangedAt: Instant,

    @Column("created_at")
    val createdAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant
) {
    override fun toString(): String =
        "LocalCredentialEntity(appUserId=$appUserId, email=$email, mustChangePassword=$mustChangePassword)"
}
