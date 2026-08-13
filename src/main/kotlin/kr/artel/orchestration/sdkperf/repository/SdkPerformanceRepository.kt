package kr.artel.orchestration.sdkperf.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.sdkperf.dto.SdkDeviceInfo
import kr.artel.orchestration.sdkperf.dto.SdkPerformanceMessage
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant

private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?,
    type: Class<out T>
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)

@Repository
class SdkPerformanceRepository(private val db: DatabaseClient) {
    suspend fun activeRunId(instanceId: Long): Long? = db.sql(
        "SELECT id FROM qa_run WHERE game_instance_id=:instanceId AND status IN ('STARTING','RUNNING')"
    ).bind("instanceId", instanceId).map { row, _ -> row.long("id") }.flow().toList().firstOrNull()

    suspend fun insertSample(instanceId: Long, sessionId: String, runId: Long?, at: Instant, message: SdkPerformanceMessage) {
        val p = message.process
        var sql = db.sql(
            """
            INSERT INTO sdk_performance_sample (
              websocket_session_id,game_instance_id,qa_run_id,message_id,received_at,
              frame_count,sampled_ms,mean_ms,min_ms,max_ms,p95_ms,p99_ms,one_percent_low_fps,
              point_one_percent_low_fps,hitch_count,hitch_threshold_ms,budget_ms,is_focused,battery_status,
              has_process,process_cpu_percent,process_working_set_bytes,process_private_bytes,
              process_managed_heap_bytes,process_gen0_collections,process_gen1_collections,
              process_gen2_collections,process_sampled_ms)
            VALUES (:sessionId,:instanceId,:runId,:messageId,:at,:frameCount,:sampledMs,:meanMs,:minMs,
              :maxMs,:p95Ms,:p99Ms,:oneLow,:pointOneLow,:hitchCount,:hitchThreshold,:budgetMs,
              :focused,:battery,:hasProcess,:cpu,:workingSet,:privateBytes,:heap,:gen0,:gen1,:gen2,:processMs)
            """
        ).bind("sessionId",sessionId).bind("instanceId",instanceId).bindNullable("runId",runId,java.lang.Long::class.java)
            .bindNullable("messageId",message.id,java.lang.Long::class.java).bind("at",at)
            .bind("frameCount",message.frameTimes.frameCount).bind("sampledMs",message.frameTimes.sampledMs)
            .bind("meanMs",message.frameTimes.meanMs).bind("minMs",message.frameTimes.minMs)
            .bind("maxMs",message.frameTimes.maxMs).bind("p95Ms",message.frameTimes.p95Ms)
            .bind("p99Ms",message.frameTimes.p99Ms).bind("oneLow",message.frameTimes.onePercentLowFps)
            .bind("pointOneLow",message.frameTimes.pointOnePercentLowFps).bind("hitchCount",message.frameTimes.hitchCount)
            .bind("hitchThreshold",message.frameTimes.hitchThresholdMs).bind("budgetMs",message.frameTimes.budgetMs)
            .bind("focused",message.status.isFocused).bindNullable("battery",message.status.batteryStatus,String::class.java)
            .bind("hasProcess",p != null).bindNullable("cpu",p?.cpuPercent,java.lang.Double::class.java)
            .bindNullable("workingSet",p?.workingSetBytes,java.lang.Long::class.java)
            .bindNullable("privateBytes",p?.privateBytes,java.lang.Long::class.java)
            .bindNullable("heap",p?.managedHeapBytes,java.lang.Long::class.java)
            .bindNullable("gen0",p?.gen0Collections,java.lang.Integer::class.java)
            .bindNullable("gen1",p?.gen1Collections,java.lang.Integer::class.java)
            .bindNullable("gen2",p?.gen2Collections,java.lang.Integer::class.java)
            .bindNullable("processMs",p?.sampledMs,java.lang.Double::class.java)
        sql.fetch().awaitRowsUpdated()
    }

    suspend fun aggregateSample(runId: Long, instanceId: Long, at: Instant, message: SdkPerformanceMessage) {
        val f=message.frameTimes; val p=message.process; val processMs=p?.sampledMs ?: 0.0
        // 최빈값 도수를 먼저 올려야 아래 요약 upsert가 이번 표본까지 반영한 최빈값을 읽는다.
        db.sql(BUDGET_TALLY_UPSERT).bind("runId",runId).bind("budget",f.budgetMs).fetch().awaitRowsUpdated()
        db.sql(SUMMARY_UPSERT).bind("runId",runId).bind("instanceId",instanceId).bind("at",at)
            .bind("sampled",f.sampledMs).bind("frames",f.frameCount).bind("p95",f.p95Ms*f.frameCount)
            .bind("p99",f.p99Ms*f.frameCount).bind("oneLow",f.onePercentLowFps*f.frameCount)
            .bind("hitches",f.hitchCount)
            .bind("processCount",if(p==null) 0 else 1).bind("cpuWeighted",(p?.cpuPercent ?: 0.0)*processMs)
            .bind("cpuWeight",processMs).bindNullable("cpu",p?.cpuPercent,java.lang.Double::class.java)
            .bindNullable("workingSet",p?.workingSetBytes,java.lang.Long::class.java)
            .bind("gen0",p?.gen0Collections ?: 0).bind("gen1",p?.gen1Collections ?: 0)
            .bind("gen2",p?.gen2Collections ?: 0).bind("discharging",if(message.status.batteryStatus=="Discharging") 1 else 0)
            .fetch().awaitRowsUpdated()
        db.sql(SERIES_UPSERT).bind("runId",runId).bind("at",at).bind("frames",f.frameCount)
            .bind("sampled",f.sampledMs).bind("p95",f.p95Ms*f.frameCount).bind("max",f.maxMs)
            .bind("hitches",f.hitchCount).bind("processCount",if(p==null) 0 else 1)
            .bind("cpuWeighted",(p?.cpuPercent ?: 0.0)*processMs).bind("cpuWeight",processMs)
            .bindNullable("workingSet",p?.workingSetBytes,java.lang.Long::class.java)
            .bind("focused",if(message.status.isFocused) 1 else 0).fetch().awaitRowsUpdated()
    }

    suspend fun saveDevice(instanceId: Long, sessionId: String, at: Instant, d: SdkDeviceInfo) {
        db.sql(DEVICE_UPSERT).bind("sessionId",sessionId).bind("instanceId",instanceId).bind("at",at)
            .bindDevice(d).fetch().awaitRowsUpdated()
        db.sql("""UPDATE sdk_performance_run_summary s SET is_editor=:isEditor
            FROM qa_run r WHERE s.qa_run_id=r.id AND r.game_instance_id=:instanceId
            AND r.status IN ('STARTING','RUNNING')""")
            .bindNullable("isEditor",d.isEditor,java.lang.Boolean::class.java).bind("instanceId",instanceId)
            .fetch().awaitRowsUpdated()
    }

    suspend fun findRun(runId: Long,userId: Long): RunRow? = db.sql(RUN_SQL).bind("runId",runId)
        .bind("userId",userId).map { r,_ -> r.toRun() }.flow().toList().firstOrNull()

    suspend fun findDevice(instanceId:Long, before:Instant): DeviceRow? = db.sql(DEVICE_SQL)
        .bind("instanceId",instanceId).bind("before",before).map { r,_ -> r.toDevice() }.flow().toList().firstOrNull()

    suspend fun findSeries(runId:Long,bucketSeconds:Long):List<SeriesRow> = db.sql(SERIES_SQL)
        .bind("runId",runId).bind("bucketSeconds",bucketSeconds).map { r,_ -> r.toSeries() }.flow().toList()

    suspend fun findBuildRuns(projectId:Long,buildId:Long,userId:Long):List<RunRow> = db.sql(BUILD_SQL)
        .bind("projectId",projectId).bind("buildId",buildId).bind("userId",userId)
        .map { r,_ -> r.toRun() }.flow().toList()

    suspend fun buildAccessible(projectId:Long,buildId:Long,userId:Long):Boolean = db.sql(
        """SELECT 1 FROM game_build gb JOIN project_member pm ON pm.project_id=gb.project_id
           WHERE gb.id=:buildId AND gb.project_id=:projectId AND pm.app_user_id=:userId""")
        .bind("buildId",buildId).bind("projectId",projectId).bind("userId",userId)
        .map { _,_->true }.flow().toList().firstOrNull() ?: false

    private fun DatabaseClient.GenericExecuteSpec.bindDevice(d:SdkDeviceInfo):DatabaseClient.GenericExecuteSpec = this
        .bindNullable("deviceModel",d.deviceModel,String::class.java).bindNullable("processorType",d.processorType,String::class.java)
        .bindNullable("processorCount",d.processorCount,java.lang.Integer::class.java).bindNullable("systemMemory",d.systemMemoryMb,java.lang.Integer::class.java)
        .bindNullable("graphicsName",d.graphicsDeviceName,String::class.java).bindNullable("graphicsType",d.graphicsDeviceType,String::class.java)
        .bindNullable("graphicsMemory",d.graphicsMemoryMb,java.lang.Integer::class.java).bindNullable("os",d.operatingSystem,String::class.java)
        .bindNullable("quality",d.qualityLevel,java.lang.Integer::class.java).bindNullable("width",d.resolutionWidth,java.lang.Integer::class.java)
        .bindNullable("height",d.resolutionHeight,java.lang.Integer::class.java).bindNullable("refresh",d.refreshRateHz,java.lang.Double::class.java)
        .bindNullable("dpi",d.dpi,java.lang.Double::class.java).bindNullable("fullscreen",d.fullScreenMode,String::class.java)
        .bindNullable("target",d.targetFrameRate,java.lang.Integer::class.java).bindNullable("vsync",d.vSyncCount,java.lang.Integer::class.java)
        .bindNullable("isEditor",d.isEditor,java.lang.Boolean::class.java).bindNullable("isDebug",d.isDebugBuild,java.lang.Boolean::class.java)
        .bindNullable("backend",d.scriptingBackend,String::class.java).bindNullable("sdk",d.sdkVersion,String::class.java)

    private fun Readable.toRun()=RunRow(long("run_id"),long("game_instance_id"),nullableLong("game_build_id"),
        instant("started_at"),nullableInstant("completed_at"),string("status"),nullableBoolean("is_editor"),
        nullableLong("sample_count"),nullableDouble("covered_ms"),nullableLong("frame_count"),nullableDouble("frame_time_sum_ms"),
        nullableDouble("frame_p95_weighted_sum"),nullableDouble("frame_p99_weighted_sum"),nullableDouble("one_low_weighted_sum"),
        nullableLong("hitch_count"),nullableDouble("budget_mode_ms"),nullableLong("process_sample_count"),nullableDouble("cpu_weighted_sum"),
        nullableDouble("cpu_weight_ms"),nullableDouble("cpu_percent_max"),nullableLong("working_set_bytes_max"),nullableLong("gen0_collections"),
        nullableLong("gen1_collections"),nullableLong("gen2_collections"),nullableLong("discharging_sample_count"))
    private fun Readable.toDevice()=DeviceRow(nullableBoolean("is_editor"),string("scripting_backend"),string("sdk_version"),string("device_model"),string("processor_type"),nullableInt("processor_count"),string("graphics_device_name"),string("graphics_device_type"),string("operating_system"),nullableInt("target_frame_rate"),nullableInt("v_sync_count"),nullableDouble("refresh_rate_hz"))
    private fun Readable.toSeries()=SeriesRow(instant("bucket_at"),long("sample_count"),long("frame_count"),double("sampled_ms"),double("frame_time_sum_ms"),double("p95_sum"),double("frame_max_ms"),long("hitch_count"),long("process_count"),double("cpu_sum"),double("cpu_ms"),nullableLong("working_set"),long("focused_count"))
    private fun Readable.long(n:String)=get(n,java.lang.Long::class.java)!!.toLong(); private fun Readable.nullableLong(n:String)=get(n,java.lang.Long::class.java)?.toLong()
    private fun Readable.nullableInt(n:String)=get(n,java.lang.Integer::class.java)?.toInt(); private fun Readable.double(n:String)=get(n,java.lang.Double::class.java)!!.toDouble(); private fun Readable.nullableDouble(n:String)=get(n,java.lang.Double::class.java)?.toDouble()
    private fun Readable.string(n:String)=get(n,String::class.java); private fun Readable.instant(n:String)=get(n,Instant::class.java)!!; private fun Readable.nullableInstant(n:String)=get(n,Instant::class.java); private fun Readable.nullableBoolean(n:String)=get(n,java.lang.Boolean::class.java)?.booleanValue()

    companion object {
        /** 런별 budget_ms 도수. 최빈값을 O(런의 budget 종류 수)로 읽기 위한 것이다. */
        private const val BUDGET_TALLY_UPSERT="""INSERT INTO sdk_performance_run_budget(qa_run_id,budget_ms,sample_count) VALUES(:runId,:budget,1)
          ON CONFLICT(qa_run_id,budget_ms) DO UPDATE SET sample_count=sdk_performance_run_budget.sample_count+1"""
        /** 동률이면 budget_ms가 작은 쪽으로 결정한다. 같은 입력에 같은 답이 나와야 한다. */
        private const val BUDGET_MODE="""(SELECT b.budget_ms FROM sdk_performance_run_budget b WHERE b.qa_run_id=:runId ORDER BY b.sample_count DESC,b.budget_ms LIMIT 1)"""
        private const val SUMMARY_UPSERT="""INSERT INTO sdk_performance_run_summary(qa_run_id,game_instance_id,game_build_id,is_editor,sample_count,covered_ms,frame_count,frame_time_sum_ms,frame_p95_weighted_sum,frame_p99_weighted_sum,one_low_weighted_sum,hitch_count,budget_mode_ms,process_sample_count,cpu_weighted_sum,cpu_weight_ms,cpu_percent_max,working_set_bytes_max,gen0_collections,gen1_collections,gen2_collections,discharging_sample_count,updated_at)
          SELECT :runId,:instanceId,gi.last_game_build_id,dc.is_editor,1,:sampled,:frames,:sampled,:p95,:p99,:oneLow,:hitches,$BUDGET_MODE,
            :processCount,:cpuWeighted,:cpuWeight,:cpu,:workingSet,:gen0,:gen1,:gen2,:discharging,:at
          FROM game_instance gi LEFT JOIN LATERAL (SELECT is_editor FROM sdk_device_context WHERE game_instance_id=gi.id AND received_at<=:at ORDER BY received_at DESC LIMIT 1) dc ON TRUE WHERE gi.id=:instanceId
          ON CONFLICT(qa_run_id) DO UPDATE SET sample_count=sdk_performance_run_summary.sample_count+1,covered_ms=sdk_performance_run_summary.covered_ms+:sampled,frame_count=sdk_performance_run_summary.frame_count+:frames,frame_time_sum_ms=sdk_performance_run_summary.frame_time_sum_ms+:sampled,frame_p95_weighted_sum=sdk_performance_run_summary.frame_p95_weighted_sum+:p95,frame_p99_weighted_sum=sdk_performance_run_summary.frame_p99_weighted_sum+:p99,one_low_weighted_sum=sdk_performance_run_summary.one_low_weighted_sum+:oneLow,hitch_count=sdk_performance_run_summary.hitch_count+:hitches,budget_mode_ms=$BUDGET_MODE,process_sample_count=sdk_performance_run_summary.process_sample_count+:processCount,cpu_weighted_sum=sdk_performance_run_summary.cpu_weighted_sum+:cpuWeighted,cpu_weight_ms=sdk_performance_run_summary.cpu_weight_ms+:cpuWeight,cpu_percent_max=GREATEST(sdk_performance_run_summary.cpu_percent_max,:cpu),working_set_bytes_max=GREATEST(sdk_performance_run_summary.working_set_bytes_max,:workingSet),gen0_collections=sdk_performance_run_summary.gen0_collections+:gen0,gen1_collections=sdk_performance_run_summary.gen1_collections+:gen1,gen2_collections=sdk_performance_run_summary.gen2_collections+:gen2,discharging_sample_count=sdk_performance_run_summary.discharging_sample_count+:discharging,updated_at=:at"""
        // 열 목록을 적는다. 위치 기반 INSERT는 나중에 누가 열 하나를 더하는 순간 값이 조용히
        // 옆 칸으로 밀린다.
        private const val SERIES_UPSERT="""INSERT INTO sdk_performance_run_series(qa_run_id,bucket_at,sample_count,frame_count,sampled_ms,frame_time_sum_ms,frame_p95_weighted_sum,frame_max_ms,hitch_count,process_sample_count,cpu_weighted_sum,cpu_weight_ms,working_set_bytes_max,focused_sample_count)
          VALUES(:runId,date_trunc('second',:at),1,:frames,:sampled,:sampled,:p95,:max,:hitches,:processCount,:cpuWeighted,:cpuWeight,:workingSet,:focused)
          ON CONFLICT(qa_run_id,bucket_at) DO UPDATE SET sample_count=sdk_performance_run_series.sample_count+1,frame_count=sdk_performance_run_series.frame_count+:frames,sampled_ms=sdk_performance_run_series.sampled_ms+:sampled,frame_time_sum_ms=sdk_performance_run_series.frame_time_sum_ms+:sampled,frame_p95_weighted_sum=sdk_performance_run_series.frame_p95_weighted_sum+:p95,frame_max_ms=GREATEST(sdk_performance_run_series.frame_max_ms,:max),hitch_count=sdk_performance_run_series.hitch_count+:hitches,process_sample_count=sdk_performance_run_series.process_sample_count+:processCount,cpu_weighted_sum=sdk_performance_run_series.cpu_weighted_sum+:cpuWeighted,cpu_weight_ms=sdk_performance_run_series.cpu_weight_ms+:cpuWeight,working_set_bytes_max=GREATEST(sdk_performance_run_series.working_set_bytes_max,:workingSet),focused_sample_count=sdk_performance_run_series.focused_sample_count+:focused"""
        private const val DEVICE_UPSERT="""INSERT INTO sdk_device_context(websocket_session_id,game_instance_id,received_at,device_model,processor_type,processor_count,system_memory_mb,graphics_device_name,graphics_device_type,graphics_memory_mb,operating_system,quality_level,resolution_width,resolution_height,refresh_rate_hz,dpi,full_screen_mode,target_frame_rate,v_sync_count,is_editor,is_debug_build,scripting_backend,sdk_version)
          VALUES(:sessionId,:instanceId,:at,:deviceModel,:processorType,:processorCount,:systemMemory,:graphicsName,:graphicsType,:graphicsMemory,:os,:quality,:width,:height,:refresh,:dpi,:fullscreen,:target,:vsync,:isEditor,:isDebug,:backend,:sdk) ON CONFLICT(websocket_session_id) DO UPDATE SET received_at=:at,device_model=:deviceModel,processor_type=:processorType,processor_count=:processorCount,system_memory_mb=:systemMemory,graphics_device_name=:graphicsName,graphics_device_type=:graphicsType,graphics_memory_mb=:graphicsMemory,operating_system=:os,quality_level=:quality,resolution_width=:width,resolution_height=:height,refresh_rate_hz=:refresh,dpi=:dpi,full_screen_mode=:fullscreen,target_frame_rate=:target,v_sync_count=:vsync,is_editor=:isEditor,is_debug_build=:isDebug,scripting_backend=:backend,sdk_version=:sdk"""
        private const val RUN_SQL="""SELECT r.id run_id,r.game_instance_id,r.started_at,r.completed_at,r.status,s.* FROM qa_run r JOIN test_run tr ON tr.id=r.test_run_id JOIN project_member pm ON pm.project_id=tr.project_id AND pm.app_user_id=:userId LEFT JOIN sdk_performance_run_summary s ON s.qa_run_id=r.id WHERE r.id=:runId"""
        // 계약대로 isEditor "인" 런만 뺀다. `= FALSE`면 DEVICE_CONTEXT를 못 받아 is_editor가
        // NULL인 런까지 빠져, 추세에서 런이 이유 없이 사라진다.
        private const val BUILD_SQL="""SELECT r.id run_id,r.game_instance_id,r.started_at,r.completed_at,r.status,s.* FROM sdk_performance_run_summary s JOIN qa_run r ON r.id=s.qa_run_id JOIN test_run tr ON tr.id=r.test_run_id JOIN project_member pm ON pm.project_id=tr.project_id AND pm.app_user_id=:userId WHERE tr.project_id=:projectId AND s.game_build_id=:buildId AND s.is_editor IS DISTINCT FROM TRUE ORDER BY r.started_at"""
        private const val DEVICE_SQL="""SELECT * FROM sdk_device_context WHERE game_instance_id=:instanceId AND received_at<=:before ORDER BY received_at DESC LIMIT 1"""
        private const val SERIES_SQL="""SELECT to_timestamp(floor(extract(epoch from bucket_at)/:bucketSeconds)*:bucketSeconds) bucket_at,SUM(sample_count)::bigint sample_count,SUM(frame_count)::bigint frame_count,SUM(sampled_ms)::double precision sampled_ms,SUM(frame_time_sum_ms)::double precision frame_time_sum_ms,SUM(frame_p95_weighted_sum)::double precision p95_sum,MAX(frame_max_ms)::double precision frame_max_ms,SUM(hitch_count)::bigint hitch_count,SUM(process_sample_count)::bigint process_count,SUM(cpu_weighted_sum)::double precision cpu_sum,SUM(cpu_weight_ms)::double precision cpu_ms,MAX(working_set_bytes_max)::bigint working_set,SUM(focused_sample_count)::bigint focused_count FROM sdk_performance_run_series WHERE qa_run_id=:runId GROUP BY 1 ORDER BY 1"""
    }
}

data class RunRow(val runId:Long,val gameInstanceId:Long,val gameBuildId:Long?,val startedAt:Instant,val completedAt:Instant?,val status:String?,val isEditor:Boolean?,val sampleCount:Long?,val coveredMs:Double?,val frameCount:Long?,val frameTimeSum:Double?,val p95Sum:Double?,val p99Sum:Double?,val oneLowSum:Double?,val hitchCount:Long?,val budgetMs:Double?,val processCount:Long?,val cpuSum:Double?,val cpuMs:Double?,val cpuMax:Double?,val workingSetMax:Long?,val gen0:Long?,val gen1:Long?,val gen2:Long?,val dischargingCount:Long?)
data class DeviceRow(val isEditor:Boolean?,val backend:String?,val sdk:String?,val model:String?,val processor:String?,val processorCount:Int?,val graphics:String?,val graphicsType:String?,val os:String?,val target:Int?,val vsync:Int?,val refresh:Double?)
data class SeriesRow(val at:Instant,val samples:Long,val frames:Long,val sampledMs:Double,val frameTimeSum:Double,val p95Sum:Double,val frameMax:Double,val hitches:Long,val processCount:Long,val cpuSum:Double,val cpuMs:Double,val workingSet:Long?,val focused:Long)
