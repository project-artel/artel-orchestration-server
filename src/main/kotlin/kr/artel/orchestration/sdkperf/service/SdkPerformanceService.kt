package kr.artel.orchestration.sdkperf.service

import kr.artel.orchestration.sdkperf.dto.*
import kr.artel.orchestration.sdkperf.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max

@Service
class SdkPerfIngestService(private val repository:SdkPerformanceRepository,private val clock:Clock) {
    @Transactional
    suspend fun recordPerformance(instanceId:Long,sessionId:String,message:SdkPerformanceMessage) {
        val at=Instant.now(clock)
        val runId=repository.activeRunId(instanceId)
        repository.insertSample(instanceId,sessionId,runId,at,message)
        if(runId!=null) repository.aggregateSample(runId,instanceId,at,message)
    }
    @Transactional
    suspend fun recordDeviceContext(instanceId:Long,sessionId:String,message:SdkDeviceContextMessage) =
        repository.saveDevice(instanceId,sessionId,Instant.now(clock),message.device)
}

@Service
class SdkPerfQueryService(private val repository:SdkPerformanceRepository,private val clock:Clock) {
    suspend fun runDetail(runId:Long,userId:Long):PerformanceRunDetailResponse? {
        val r=repository.findRun(runId,userId) ?: return null
        val end=r.completedAt ?: Instant.now(clock)
        val duration=max(0,Duration.between(r.startedAt,end).toMillis())
        val bucketMs=max(1000L,ceil(duration.coerceAtLeast(1)/1000.0/1000.0).toLong()*1000L)
        val rows=if(r.sampleCount==null) emptyMap() else repository.findSeries(runId,bucketMs/1000)
            .associateBy { Duration.between(Instant.EPOCH,it.at).toMillis()/bucketMs }
        val points=mutableListOf<PerformancePointResponse>()
        var atMs=0L
        while(r.sampleCount!=null && atMs<=duration) {
            val instant=r.startedAt.plusMillis(atMs)
            val row=rows[Duration.between(Instant.EPOCH,instant).toMillis()/bucketMs]
            points += if(row==null) PerformancePointResponse(atMs,null,null,null,null,null,null,false)
            else PerformancePointResponse(atMs,ratio(row.frameTimeSum,row.frames.toDouble()),ratio(row.p95Sum,row.frames.toDouble()),row.frameMax,row.hitches,ratio(row.cpuSum,row.cpuMs),row.workingSet,row.focused>0)
            atMs += bucketMs
        }
        val device=repository.findDevice(r.gameInstanceId,end)?.toResponse()
        return PerformanceRunDetailResponse(r.runId,r.gameInstanceId,r.gameBuildId,r.startedAt,r.completedAt,r.summary(duration),device,PerformanceSeriesResponse(bucketMs,points))
    }

    suspend fun buildTrend(projectId:Long,buildId:Long,userId:Long):PerformanceBuildTrendResponse? {
        if(!repository.buildAccessible(projectId,buildId,userId)) return null
        return PerformanceBuildTrendResponse(buildId,projectId,repository.findBuildRuns(projectId,buildId,userId).map { r ->
            val duration=max(0,Duration.between(r.startedAt,r.completedAt ?: Instant.now(clock)).toMillis())
            val s=r.summary(duration)!!
            PerformanceBuildRunResponse(r.runId,r.startedAt,duration,r.status ?: "RUNNING",s.frameMeanMs,s.frameP95Ms,s.frameP99Ms,s.onePercentLowFps,s.hitchesPerMinute,s.budgetMs,s.cpuPercentMean,s.workingSetBytesMax,s.coverageRatio,s.dischargingRatio,s.processSampleRatio)
        })
    }

    private fun RunRow.summary(duration:Long):PerformanceSummaryResponse? {
        val samples=sampleCount ?: return null
        val covered=coveredMs ?: 0.0; val frames=frameCount ?: 0; val processes=processCount ?: 0
        return PerformanceSummaryResponse(samples,covered,if(duration>0) covered/duration else 0.0,
            ratio(frameTimeSum,frames.toDouble()),ratio(p95Sum,frames.toDouble()),ratio(p99Sum,frames.toDouble()),ratio(oneLowSum,frames.toDouble()),
            hitchCount ?: 0,ratio((hitchCount ?: 0)*60000.0,covered),budgetMs,
            ratio(cpuSum,cpuMs ?: 0.0),cpuMax,workingSetMax,GcCollectionsResponse(
                if(processes==0L) null else gen0 ?: 0,
                if(processes==0L) null else gen1 ?: 0,
                if(processes==0L) null else gen2 ?: 0
            ),
            ratio(dischargingCount?.toDouble() ?: 0.0,samples.toDouble()) ?: 0.0,ratio(processes.toDouble(),samples.toDouble()) ?: 0.0)
    }
    private fun DeviceRow.toResponse()=PerformanceDeviceResponse(isEditor,backend,sdk,model,processor,processorCount,graphics,graphicsType,os,target,vsync,refresh)
    private fun ratio(n:Number?,d:Double):Double?=if(n==null||d<=0.0)null else n.toDouble()/d
}
