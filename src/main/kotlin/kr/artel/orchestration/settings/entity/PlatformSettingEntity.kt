package kr.artel.orchestration.settings.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 설치 단위 설정 한 줄. [encrypted] 가 true 면 [settingValue] 는 `SecretCipher` 의 암호문이다. */
@Table("platform_setting")
data class PlatformSettingEntity(
    @Id
    @Column("setting_key")
    val settingKey: String,

    @Column("setting_value")
    val settingValue: String,

    @Column("encrypted")
    val encrypted: Boolean,

    @Column("updated_by")
    val updatedBy: Long?,

    @Column("updated_at")
    val updatedAt: Instant
) {
    /** 암호문도 로그로 내보내지 않는다. */
    override fun toString(): String = "PlatformSettingEntity(settingKey=$settingKey, encrypted=$encrypted)"
}
