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
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * SDK가 실제로 보내는 프레임 모양과 런 귀속 규칙을 고정한다 (ARTEL-372).
 */
class SdkPerformanceContractTest {

    private val mapper = ObjectMapper().registerKotlinModule()
    private val receivedAt = Instant.parse("2026-08-13T00:00:00Z")
    private val clock = Clock.fixed(receivedAt, ZoneOffset.UTC)

    /** `process`가 빠진 프레임. 읽을 수 없는 플랫폼에서 오는 실제 모양이다. */
    private val withoutProcess = """
        {"type":"PERFORMANCE","id":2,
         "frameTimes":{"frameCount":59,"sampledMs":998.4,"meanMs":16.92,"minMs":15.1,"maxMs":118.7,
                       "p95Ms":17.8,"p99Ms":118.7,"onePercentLowFps":8.4,"pointOnePercentLowFps":8.4,
                       "hitchCount":1,"hitchThresholdMs":33.33,"budgetMs":16.67},
         "status":{"isFocused":true,"batteryStatus":"Charging"}}
    """.trimIndent()

    /** 같은 프레임에 `process`가 전부 0으로 실린 경우. 없음과 뒤섞이면 안 된다. */
    private val withZeroedProcess = """
        {"type":"PERFORMANCE","id":3,
         "frameTimes":{"frameCount":59,"sampledMs":998.4,"meanMs":16.92,"minMs":15.1,"maxMs":118.7,
                       "p95Ms":17.8,"p99Ms":118.7,"onePercentLowFps":8.4,"pointOnePercentLowFps":8.4,
                       "hitchCount":1,"hitchThresholdMs":33.33,"budgetMs":16.67},
         "status":{"isFocused":false,"batteryStatus":"Discharging"},
         "process":{"cpuPercent":0.0,"workingSetBytes":0,"privateBytes":0,"managedHeapBytes":0,
                    "gen0Collections":0,"gen1Collections":0,"gen2Collections":0,"sampledMs":1000.2}}
    """.trimIndent()

    @Test
    fun `a missing process block stays missing rather than becoming zero`() {
        assertThat(mapper.readValue(withoutProcess, SdkPerformanceMessage::class.java).process).isNull()

        val zeroed = mapper.readValue(withZeroedProcess, SdkPerformanceMessage::class.java).process
        assertThat(zeroed).isNotNull
        assertThat(zeroed!!.cpuPercent).isEqualTo(0.0)
        assertThat(zeroed.workingSetBytes).isEqualTo(0)
    }

    /** `isFocused`/`batteryStatus`는 와이어 이름 그대로 읽혀야 한다. */
    @Test
    fun `status flags are read from the wire names the sdk uses`() {
        val focused = mapper.readValue(withoutProcess, SdkPerformanceMessage::class.java).status
        assertThat(focused.isFocused).isTrue()
        assertThat(focused.batteryStatus).isEqualTo("Charging")

        val unfocused = mapper.readValue(withZeroedProcess, SdkPerformanceMessage::class.java).status
        assertThat(unfocused.isFocused).isFalse()
        assertThat(unfocused.batteryStatus).isEqualTo("Discharging")
    }

    /** SDK가 필드를 더해도 초당 한 번씩 파싱이 깨지면 안 된다. */
    @Test
    fun `an unexpected field added by a newer sdk does not break parsing`() {
        val withExtras = """
            {"type":"PERFORMANCE","id":4,
             "frameTimes":{"frameCount":59,"sampledMs":998.4,"meanMs":16.92,"minMs":15.1,"maxMs":118.7,
                           "p95Ms":17.8,"p99Ms":118.7,"onePercentLowFps":8.4,"pointOnePercentLowFps":8.4,
                           "hitchCount":1,"hitchThresholdMs":33.33,"budgetMs":16.67,"inventedLater":1},
             "status":{"isFocused":true,"batteryStatus":"Charging","alsoNew":"x"},
             "topLevelNovelty":[1,2]}
        """.trimIndent()

        val message = mapper.readValue(withExtras, SdkPerformanceMessage::class.java)

        assertThat(message.frameTimes.frameCount).isEqualTo(59)
    }

    @Test
    fun `a sample outside a qa run is preserved without aggregation`(): Unit = runBlocking {
        val repository = mock(SdkPerformanceRepository::class.java)
        `when`(repository.activeRunId(7)).thenReturn(null)
        val message = mapper.readValue(withoutProcess, SdkPerformanceMessage::class.java)

        SdkPerfIngestService(repository, clock).recordPerformance(7L, "ws-1", message)

        verify(repository).activeRunId(7L)
        verify(repository).insertSample(7L, "ws-1", null, receivedAt, message)
        // 집계는 호출되지 않아야 한다. 런 밖 성능은 어떤 런의 요약에도 섞이면 안 된다.
        verifyNoMoreInteractions(repository)
    }

    @Test
    fun `a sample is attributed to the run active at receipt time`(): Unit = runBlocking {
        val repository = mock(SdkPerformanceRepository::class.java)
        `when`(repository.activeRunId(7)).thenReturn(372L)
        val message = mapper.readValue(withoutProcess, SdkPerformanceMessage::class.java)

        SdkPerfIngestService(repository, clock).recordPerformance(7L, "ws-1", message)

        verify(repository).insertSample(7L, "ws-1", 372L, receivedAt, message)
        verify(repository).aggregateSample(372L, 7L, receivedAt, message)
    }
}
