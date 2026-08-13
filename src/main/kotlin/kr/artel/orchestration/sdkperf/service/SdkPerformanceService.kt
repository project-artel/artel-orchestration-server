package kr.artel.orchestration.sdkperf.service

import kr.artel.orchestration.sdkperf.dto.GcCollectionsResponse
import kr.artel.orchestration.sdkperf.dto.PerformanceBuildRunResponse
import kr.artel.orchestration.sdkperf.dto.PerformanceBuildTrendResponse
import kr.artel.orchestration.sdkperf.dto.PerformanceDeviceResponse
import kr.artel.orchestration.sdkperf.dto.PerformancePointResponse
import kr.artel.orchestration.sdkperf.dto.PerformanceRunDetailResponse
import kr.artel.orchestration.sdkperf.dto.PerformanceSeriesResponse
import kr.artel.orchestration.sdkperf.dto.PerformanceSummaryResponse
import kr.artel.orchestration.sdkperf.dto.SdkDeviceContextMessage
import kr.artel.orchestration.sdkperf.dto.SdkPerformanceMessage
import kr.artel.orchestration.sdkperf.repository.DeviceRow
import kr.artel.orchestration.sdkperf.repository.RunRow
import kr.artel.orchestration.sdkperf.repository.SdkPerformanceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max

/** 원본 셀의 최소 단위. SDK가 1초마다 보고하므로 이보다 잘게 나눌 수 없다. */
private const val BASE_BUCKET_MS = 1000L

/** 시계열 한 응답이 목표로 하는 점 수. 런이 길어지면 버킷을 키워 이 근처로 묶는다. */
private const val TARGET_POINT_COUNT = 1000L

/**
 * SDK가 보낸 성능 표본을 보존하고 활성 QA 런에 귀속한다 (ARTEL-372).
 *
 * 원본은 지우지 않는 것이 결정 사항이라, 조회 쪽이 원본을 훑지 않도록 저장과 같은 트랜잭션에서
 * 런 요약과 시계열 셀을 함께 증분 갱신한다.
 */
@Service
class SdkPerfIngestService(
    private val repository: SdkPerformanceRepository,
    private val clock: Clock
) {
    /**
     * 표본은 언제나 저장하고, 도착 시각에 진행 중이던 런이 있을 때만 집계에 반영한다.
     *
     * 런 밖에서 온 표본을 버리지 않는 이유는 원본 전량 보존이 결정 사항이기 때문이다.
     * 그런 표본은 `qa_run_id`가 비어 있을 뿐 그대로 남는다.
     */
    @Transactional
    suspend fun recordPerformance(instanceId: Long, sessionId: String, message: SdkPerformanceMessage) {
        val receivedAt = Instant.now(clock)
        val runId = repository.activeRunId(instanceId)
        repository.insertSample(instanceId, sessionId, runId, receivedAt, message)
        if (runId != null) repository.aggregateSample(runId, instanceId, receivedAt, message)
    }

    @Transactional
    suspend fun recordDeviceContext(instanceId: Long, sessionId: String, message: SdkDeviceContextMessage) =
        repository.saveDevice(instanceId, sessionId, Instant.now(clock), message.device)
}

/**
 * 확정된 조회 계약(Notion: 성능 지표 런 상세 조회 / 빌드 추세 조회)을 채운다 (ARTEL-378).
 *
 * 모든 읽기는 사전 집계 테이블만 본다. `sdk_performance_sample`은 어느 경로에서도 조회하지
 * 않는다 — 원본이 무한히 쌓이므로 읽을 때마다 훑으면 런이 길수록 느려진다.
 */
@Service
class SdkPerfQueryService(
    private val repository: SdkPerformanceRepository,
    private val clock: Clock
) {
    suspend fun runDetail(runId: Long, userId: Long): PerformanceRunDetailResponse? {
        val run = repository.findRun(runId, userId) ?: return null
        val end = run.completedAt ?: Instant.now(clock)
        val durationMs = max(0L, Duration.between(run.startedAt, end).toMillis())
        val bucketMs = bucketMsFor(durationMs)
        // 표본이 하나도 없는 런은 404가 아니라 summary null + points []다.
        val points =
            if (run.sampleCount == null) emptyList()
            else gapFilledPoints(runId, run.startedAt, durationMs, bucketMs)
        return PerformanceRunDetailResponse(
            runId = run.runId,
            gameInstanceId = run.gameInstanceId,
            gameBuildId = run.gameBuildId,
            startedAt = run.startedAt,
            completedAt = run.completedAt,
            summary = run.summary(durationMs),
            device = repository.findDevice(run.gameInstanceId, end)?.toResponse(),
            series = PerformanceSeriesResponse(bucketMs, points)
        )
    }

    suspend fun buildTrend(projectId: Long, gameBuildId: Long, userId: Long): PerformanceBuildTrendResponse? {
        if (!repository.buildAccessible(projectId, gameBuildId, userId)) return null
        // isEditor 런 제외는 조회 SQL이 한다. 여기서 다시 거르지 않는다.
        val runs = repository.findBuildRuns(projectId, gameBuildId, userId).mapNotNull { run ->
            val durationMs = max(0L, Duration.between(run.startedAt, run.completedAt ?: Instant.now(clock)).toMillis())
            // 이 목록은 요약 테이블에서 나오므로 표본 없는 런은 애초에 들어오지 않는다.
            run.summary(durationMs)?.let { summary ->
                PerformanceBuildRunResponse(
                    runId = run.runId,
                    startedAt = run.startedAt,
                    durationMs = durationMs,
                    status = run.status ?: "RUNNING",
                    frameMeanMs = summary.frameMeanMs,
                    frameP95Ms = summary.frameP95Ms,
                    frameP99Ms = summary.frameP99Ms,
                    onePercentLowFps = summary.onePercentLowFps,
                    hitchesPerMinute = summary.hitchesPerMinute,
                    budgetMs = summary.budgetMs,
                    cpuPercentMean = summary.cpuPercentMean,
                    workingSetBytesMax = summary.workingSetBytesMax,
                    coverageRatio = summary.coverageRatio,
                    dischargingRatio = summary.dischargingRatio,
                    processSampleRatio = summary.processSampleRatio
                )
            }
        }
        return PerformanceBuildTrendResponse(gameBuildId, projectId, runs)
    }

    /** 한 점이 덮는 시간. 런이 길수록 키워 점 수를 [TARGET_POINT_COUNT] 근처로 묶는다. */
    private fun bucketMsFor(durationMs: Long): Long {
        val buckets = durationMs.coerceAtLeast(1).toDouble() / (TARGET_POINT_COUNT * BASE_BUCKET_MS)
        return max(BASE_BUCKET_MS, ceil(buckets).toLong() * BASE_BUCKET_MS)
    }

    /**
     * 측정되지 않은 구간도 점으로 남기고 값만 `null`로 채운다.
     *
     * 점을 빼면 화면이 앞뒤를 직선으로 이어 그려, 아무도 재지 않은 구간을 정상 구간처럼
     * 보여준다. `isFocused: false`가 왜 비었는지를 알려주는 자리다.
     */
    private suspend fun gapFilledPoints(
        runId: Long,
        startedAt: Instant,
        durationMs: Long,
        bucketMs: Long
    ): List<PerformancePointResponse> {
        val cells = repository.findSeries(runId, bucketMs / BASE_BUCKET_MS)
            .associateBy { bucketIndex(it.at, bucketMs) }
        return generateSequence(0L) { it + bucketMs }
            .takeWhile { it <= durationMs }
            .map { atMs ->
                val cell = cells[bucketIndex(startedAt.plusMillis(atMs), bucketMs)]
                    ?: return@map PerformancePointResponse(atMs, null, null, null, null, null, null, false)
                PerformancePointResponse(
                    atMs = atMs,
                    frameMeanMs = ratio(cell.frameTimeSum, cell.frames.toDouble()),
                    frameP95Ms = ratio(cell.p95Sum, cell.frames.toDouble()),
                    frameMaxMs = cell.frameMax,
                    hitchCount = cell.hitches,
                    cpuPercent = ratio(cell.cpuSum, cell.cpuMs),
                    workingSetBytes = cell.workingSet,
                    isFocused = cell.focused > 0
                )
            }
            .toList()
    }

    /** 셀 묶음은 SQL이 epoch 기준으로 하므로 조회 쪽 색인도 같은 기준이어야 짝이 맞는다. */
    private fun bucketIndex(at: Instant, bucketMs: Long): Long = Math.floorDiv(at.toEpochMilli(), bucketMs)

    private fun RunRow.summary(durationMs: Long): PerformanceSummaryResponse? {
        val samples = sampleCount ?: return null
        val covered = coveredMs ?: 0.0
        val frames = (frameCount ?: 0).toDouble()
        val processSamples = processCount ?: 0
        val hitches = hitchCount ?: 0
        return PerformanceSummaryResponse(
            sampleCount = samples,
            coveredMs = covered,
            // 런 길이가 아니라 실제로 덮은 시간(sampledMs 합)의 비율이다.
            coverageRatio = if (durationMs > 0) covered / durationMs else 0.0,
            frameMeanMs = ratio(frameTimeSum, frames),
            frameP95Ms = ratio(p95Sum, frames),
            frameP99Ms = ratio(p99Sum, frames),
            onePercentLowFps = ratio(oneLowSum, frames),
            hitchCount = hitches,
            // 런마다 길이가 달라 총수로는 비교할 수 없다. 분모는 런 길이가 아니라 덮은 시간이다.
            hitchesPerMinute = ratio(hitches * 60_000.0, covered),
            budgetMs = budgetMs,
            // process가 없던 표본은 분모(cpu_weight_ms)에 들어가지 않아 평균을 0으로 끌지 않는다.
            cpuPercentMean = ratio(cpuSum, cpuMs ?: 0.0),
            cpuPercentMax = cpuMax,
            workingSetBytesMax = workingSetMax,
            gcCollections = gcCollections(processSamples),
            dischargingRatio = ratio((dischargingCount ?: 0).toDouble(), samples.toDouble()) ?: 0.0,
            processSampleRatio = ratio(processSamples.toDouble(), samples.toDouble()) ?: 0.0
        )
    }

    /** process를 한 번도 못 읽은 런은 0이 아니라 null이다. 0은 "재 봤더니 0"이다. */
    private fun RunRow.gcCollections(processSamples: Long): GcCollectionsResponse =
        if (processSamples == 0L) GcCollectionsResponse(null, null, null)
        else GcCollectionsResponse(gen0 ?: 0, gen1 ?: 0, gen2 ?: 0)

    private fun DeviceRow.toResponse() = PerformanceDeviceResponse(
        isEditor, backend, sdk, model, processor, processorCount,
        graphics, graphicsType, os, target, vsync, refresh
    )

    /** 분모가 없거나 0이면 `null`. "측정 안 됨"과 "측정했더니 0"을 가르는 자리다. */
    private fun ratio(numerator: Number?, denominator: Double): Double? =
        if (numerator == null || denominator <= 0.0) null else numerator.toDouble() / denominator
}
