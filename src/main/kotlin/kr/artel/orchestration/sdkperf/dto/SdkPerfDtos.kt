package kr.artel.orchestration.sdkperf.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

data class PerformanceRunDetailResponse(
    val runId: Long,
    val gameInstanceId: Long,
    val gameBuildId: Long?,
    val startedAt: Instant,
    val completedAt: Instant?,
    val summary: PerformanceSummaryResponse?,
    val device: PerformanceDeviceResponse?,
    val series: PerformanceSeriesResponse
)

data class PerformanceSummaryResponse(
    val sampleCount: Long,
    val coveredMs: Double,
    val coverageRatio: Double,
    val frameMeanMs: Double?,
    val frameP95Ms: Double?,
    val frameP99Ms: Double?,
    val onePercentLowFps: Double?,
    val hitchCount: Long,
    val hitchesPerMinute: Double?,
    val budgetMs: Double?,
    val cpuPercentMean: Double?,
    val cpuPercentMax: Double?,
    val workingSetBytesMax: Long?,
    val gcCollections: GcCollectionsResponse,
    val dischargingRatio: Double,
    val processSampleRatio: Double,
    val groups: Map<String, PerformanceGroupResponse>
)

/**
 * 지표군 하나의 런 단위 요약 (ARTEL-435).
 *
 * `null`(값 하나 없음)과 `0`(재봤더니 0) 위의 세 번째 상태를 [availability]가 담는다.
 */
data class PerformanceGroupResponse(
    val availability: MetricGroupAvailability,
    /** 이 군의 값을 실은 표본 / 전체 표본. `processSampleRatio`와 같은 성격이고 임계는 없다. */
    val sampleRatio: Double,
    /** [MetricGroupAvailability.MEASURED]가 아니면 `null`. */
    val metrics: Map<String, Any?>?,
    /** `renderCounters`에만 있다. 출처가 다른 같은 이름의 값을 한 선에 잇지 않기 위한 것이다. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val source: String? = null
)

/**
 * 왜 이 군의 값이 없는가.
 *
 * [UNSUPPORTED]와 [NOT_REPORTED]를 뭉개면 값이 사라졌을 때 게임 코드 탓인지 SDK 탓인지 알 수
 * 없어 빌드 간 회귀 판단이 성립하지 않는다.
 */
enum class MetricGroupAvailability {
    /** 쟀다. 표본 중 하나 이상이 실제 값을 실었다. `metrics` 안의 `0`은 "재봤더니 0"이다. */
    MEASURED,

    /** 재려 했으나 못 쟀다. SDK가 수집 대상으로 선언했는데 이 플랫폼·빌드에 카운터가 없었다. */
    UNSUPPORTED,

    /** 이 SDK는 이 군을 모른다. 수집 대상으로 선언조차 하지 않았다 (구버전 SDK). */
    NOT_REPORTED
}

data class GcCollectionsResponse(val gen0: Long?, val gen1: Long?, val gen2: Long?)

data class PerformanceDeviceResponse(
    val isEditor: Boolean?, val scriptingBackend: String?, val sdkVersion: String?,
    val deviceModel: String?, val processorType: String?, val processorCount: Int?,
    val graphicsDeviceName: String?, val graphicsDeviceType: String?, val operatingSystem: String?,
    val targetFrameRate: Int?, val vSyncCount: Int?, val refreshRateHz: Double?
)

data class PerformanceSeriesResponse(val bucketMs: Long, val points: List<PerformancePointResponse>)

data class PerformancePointResponse(
    val atMs: Long,
    val frameMeanMs: Double?,
    val frameP95Ms: Double?,
    val frameMaxMs: Double?,
    val hitchCount: Long?,
    val cpuPercent: Double?,
    val workingSetBytes: Long?,
    val isFocused: Boolean,
    /**
     * 이 버킷의 지표군 값. 점 단위에서는 `availability`를 반복하지 않는다 — 런 전체의 판정이지
     * 버킷마다 달라지는 값이 아니다. 이 버킷에 표본이 없는 군은 키 자체가 빠진다.
     */
    val groups: Map<String, PerformancePointGroupResponse>
)

data class PerformancePointGroupResponse(val metrics: Map<String, Any?>)

data class PerformanceBuildTrendResponse(
    val gameBuildId: Long,
    val projectId: Long,
    val runs: List<PerformanceBuildRunResponse>
)

data class PerformanceBuildRunResponse(
    val runId: Long,
    val startedAt: Instant,
    val durationMs: Long,
    val status: String,
    val frameMeanMs: Double?, val frameP95Ms: Double?, val frameP99Ms: Double?,
    val onePercentLowFps: Double?, val hitchesPerMinute: Double?, val budgetMs: Double?,
    val cpuPercentMean: Double?, val workingSetBytesMax: Long?,
    val coverageRatio: Double, val dischargingRatio: Double, val processSampleRatio: Double,
    val groups: Map<String, PerformanceGroupResponse>
)
