package kr.artel.orchestration.userllmkey.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 사용자가 넣은 LLM API key 한 줄. PK 는 (`app_user_id`, `service`) 다.
 *
 * [encryptedKey] 는 `SecretCipher` 의 암호문이고, associated data 는 [settingKey] 다.
 */
@Table("user_llm_key")
data class UserLlmKeyEntity(
    @Column("app_user_id")
    val appUserId: Long,

    /** [LlmKeyProvider.wire]. */
    @Column("service")
    val service: String,

    @Column("encrypted_key")
    val encryptedKey: String,

    /** 앞 6 글자 + `…` + 끝 4 글자. */
    @Column("key_hint")
    val keyHint: String,

    @Column("verified_at")
    val verifiedAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant
) {
    /** 암호문도 로그로 내보내지 않는다. */
    override fun toString(): String = "UserLlmKeyEntity(appUserId=$appUserId, service=$service, keyHint=$keyHint)"

    companion object {
        /**
         * `SecretCipher` 에 넘기는 associated data. 사용자와 서비스를 묶어 두면 DB 를 고칠 수 있는 사람이 한
         * 사용자의 암호문을 다른 사용자나 다른 서비스 자리로 옮겨도 열리지 않는다.
         */
        fun settingKey(appUserId: Long, provider: LlmKeyProvider): String =
            "user.$appUserId.llm.${provider.wire}.api_key"
    }
}
