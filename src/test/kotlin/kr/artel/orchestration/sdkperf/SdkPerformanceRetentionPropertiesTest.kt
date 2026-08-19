package kr.artel.orchestration.sdkperf

import kr.artel.orchestration.sdkperf.config.SdkPerformanceRetentionProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 보존 설정의 제약은 기동 시점에 깨져야 한다.
 *
 * 이 잡은 운영 데이터를 지운다. 잘못된 값이 런타임 한참 뒤에 "왜인지 기준선이 안 밀린다"로만
 * 드러나면 아무도 원인을 찾지 못한다.
 */
class SdkPerformanceRetentionPropertiesTest {

    @Test
    fun `defaults keep the job off and a tick able to outpace one batch`() {
        val properties = SdkPerformanceRetentionProperties()

        // 배포의 부수 효과로 운영 데이터가 지워지면 되돌릴 수 없다.
        assertThat(properties.enabled).isFalse()
        assertThat(properties.maxRowsPerTick).isGreaterThan(properties.batchSize)
    }

    @Test
    fun `a tick ceiling below the batch size is rejected`() {
        // 이 조합이면 tick마다 한 배치도 다 지우지 못해 기준선이 영영 전진하지 않는다.
        assertThatThrownBy { SdkPerformanceRetentionProperties(batchSize = 5_000, maxRowsPerTick = 100) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("max-rows-per-tick")
    }

    @Test
    fun `a non positive retention window or batch size is rejected`() {
        assertThatThrownBy { SdkPerformanceRetentionProperties(days = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SdkPerformanceRetentionProperties(batchSize = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
