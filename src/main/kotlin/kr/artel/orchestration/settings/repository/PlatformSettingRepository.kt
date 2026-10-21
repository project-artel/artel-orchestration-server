package kr.artel.orchestration.settings.repository

import kr.artel.orchestration.settings.entity.PlatformSettingEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface PlatformSettingRepository : CoroutineCrudRepository<PlatformSettingEntity, String> {

    /**
     * 있으면 덮어쓰고 없으면 넣는다. id 가 생성되는 값이 아니라 `save` 로는 새 행을 만들 수 없다.
     *
     * [updatedBy] 가 null 인 것은 사람이 아니라 서버가 쓴 값이다(telemetry scheduler 가 남기는 마지막 전송 날짜).
     * `updated_by` 컬럼은 V97 부터 nullable 이다.
     */
    @Modifying
    @Query(
        """
        INSERT INTO platform_setting (setting_key, setting_value, encrypted, updated_by, updated_at)
        VALUES (:settingKey, :settingValue, :encrypted, :updatedBy, :updatedAt)
        ON CONFLICT (setting_key) DO UPDATE
           SET setting_value = EXCLUDED.setting_value,
               encrypted = EXCLUDED.encrypted,
               updated_by = EXCLUDED.updated_by,
               updated_at = EXCLUDED.updated_at
        """
    )
    suspend fun upsert(
        settingKey: String,
        settingValue: String,
        encrypted: Boolean,
        updatedBy: Long?,
        updatedAt: Instant
    ): Int

    /** 없을 때만 넣는다. 이미 있으면 아무것도 바꾸지 않고 0 을 돌려준다. 두 요청이 동시에 처음 값을 만들 때 먼저 넣은 쪽이 남는다. */
    @Modifying
    @Query(
        """
        INSERT INTO platform_setting (setting_key, setting_value, encrypted, updated_by, updated_at)
        VALUES (:settingKey, :settingValue, FALSE, :updatedBy, :updatedAt)
        ON CONFLICT (setting_key) DO NOTHING
        """
    )
    suspend fun insertIfAbsent(
        settingKey: String,
        settingValue: String,
        updatedBy: Long?,
        updatedAt: Instant
    ): Int
}
