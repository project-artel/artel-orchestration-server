package kr.artel.orchestration.sdkperf

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.sdkperf.dto.SdkPerformanceMessage
import kr.artel.orchestration.sdkperf.repository.SdkPerformanceRepository
import kr.artel.orchestration.sdkperf.service.SdkPerfIngestService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SdkPerformanceContractTest {
    private val mapper=ObjectMapper().registerKotlinModule()
    private val json="""{"type":"PERFORMANCE","id":2,"frameTimes":{"frameCount":59,"sampledMs":998.4,"meanMs":16.92,"minMs":15.1,"maxMs":118.7,"p95Ms":17.8,"p99Ms":118.7,"onePercentLowFps":8.4,"pointOnePercentLowFps":8.4,"hitchCount":1,"hitchThresholdMs":33.33,"budgetMs":16.67},"status":{"isFocused":true,"batteryStatus":"Charging"}}"""

    @Test
    fun `missing process remains missing rather than zero`() {
        val message=mapper.readValue(json,SdkPerformanceMessage::class.java)
        assertThat(message.process).isNull()
    }

    @Test
    fun `sample outside a qa run is preserved without aggregation`():Unit=runBlocking {
        val repository=mock(SdkPerformanceRepository::class.java)
        `when`(repository.activeRunId(7)).thenReturn(null)
        val service=SdkPerfIngestService(repository,Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"),ZoneOffset.UTC))
        val message=mapper.readValue(json,SdkPerformanceMessage::class.java)
        service.recordPerformance(7L,"ws-1",message)
        verify(repository).insertSample(7L,"ws-1",null,Instant.parse("2026-08-13T00:00:00Z"),message)
    }

    @Test
    fun `sample is attributed to active qa run at receipt time`():Unit=runBlocking {
        val repository=mock(SdkPerformanceRepository::class.java)
        `when`(repository.activeRunId(7)).thenReturn(372L)
        val service=SdkPerfIngestService(repository,Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"),ZoneOffset.UTC))
        val message=mapper.readValue(json,SdkPerformanceMessage::class.java)
        service.recordPerformance(7L,"ws-1",message)
        val at=Instant.parse("2026-08-13T00:00:00Z")
        verify(repository).insertSample(7L,"ws-1",372L,at,message)
        verify(repository).aggregateSample(372L,7L,at,message)
    }
}
