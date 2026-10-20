package kr.artel.orchestration.userllmkey.repository

import kr.artel.orchestration.userllmkey.entity.UserLlmKeyEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/**
 * PK 가 (`app_user_id`, `service`) 라 `save()` 로 쓸 수 없다. 이유와 형식상의 id 타입은
 * `ScreenCapabilityRepository` 와 같다.
 */
interface UserLlmKeyRepository : CoroutineCrudRepository<UserLlmKeyEntity, Long> {

    @Query("SELECT * FROM user_llm_key WHERE app_user_id = :appUserId ORDER BY service ASC")
    suspend fun findAllOfUser(appUserId: Long): List<UserLlmKeyEntity>

    @Query("SELECT * FROM user_llm_key WHERE app_user_id = :appUserId AND service = :service")
    suspend fun findOne(appUserId: Long, service: String): UserLlmKeyEntity?

    /** 있으면 덮어쓰고 없으면 넣는다. */
    @Modifying
    @Query(
        """
        INSERT INTO user_llm_key (app_user_id, service, encrypted_key, key_hint, verified_at, updated_at)
        VALUES (:appUserId, :service, :encryptedKey, :keyHint, :verifiedAt, :updatedAt)
        ON CONFLICT (app_user_id, service) DO UPDATE
           SET encrypted_key = EXCLUDED.encrypted_key,
               key_hint = EXCLUDED.key_hint,
               verified_at = EXCLUDED.verified_at,
               updated_at = EXCLUDED.updated_at
        """
    )
    suspend fun upsert(
        appUserId: Long,
        service: String,
        encryptedKey: String,
        keyHint: String,
        verifiedAt: Instant,
        updatedAt: Instant
    ): Int

    /** 지운 행 수. 없으면 0 이다. */
    @Modifying
    @Query("DELETE FROM user_llm_key WHERE app_user_id = :appUserId AND service = :service")
    suspend fun deleteOne(appUserId: Long, service: String): Int
}
