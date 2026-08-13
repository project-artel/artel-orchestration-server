package kr.artel.orchestration.sdkperf.dto

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
    val processSampleRatio: Double
)

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
    val isFocused: Boolean
)

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
    val coverageRatio: Double, val dischargingRatio: Double, val processSampleRatio: Double
)
