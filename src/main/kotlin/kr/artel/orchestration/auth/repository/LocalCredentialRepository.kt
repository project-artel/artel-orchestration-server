package kr.artel.orchestration.auth.repository

import kr.artel.orchestration.auth.entity.LocalCredentialEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface LocalCredentialRepository : CoroutineCrudRepository<LocalCredentialEntity, Long> {

    /** 로그인 이메일로 찾는다. `uk_local_credential_email` 이 lower(email) 위에 있어 최대 한 건이다. */
    @Query("SELECT * FROM local_credential WHERE lower(email) = lower(:email)")
    suspend fun findByEmailIgnoringCase(email: String): LocalCredentialEntity?

    /** 새 자격증명을 넣는다. id 가 생성되는 값이 아니라 `save` 를 쓸 수 없다([LocalCredentialEntity]). */
    @Modifying
    @Query(
        """
        INSERT INTO local_credential
            (app_user_id, email, password_hash, must_change_password, password_changed_at, created_at, updated_at)
        VALUES (:appUserId, :email, :passwordHash, :mustChangePassword, :now, :now, :now)
        """
    )
    suspend fun insert(
        appUserId: Long,
        email: String,
        passwordHash: String,
        mustChangePassword: Boolean,
        now: Instant
    ): Int

    /** 비밀번호를 바꾼다. 바뀐 행 수를 돌려준다. 자격증명이 없는 사용자면 0 이다. */
    @Modifying
    @Query(
        """
        UPDATE local_credential
           SET password_hash = :passwordHash,
               must_change_password = :mustChangePassword,
               password_changed_at = :now,
               updated_at = :now
         WHERE app_user_id = :appUserId
        """
    )
    suspend fun updatePassword(
        appUserId: Long,
        passwordHash: String,
        mustChangePassword: Boolean,
        now: Instant
    ): Int
}
