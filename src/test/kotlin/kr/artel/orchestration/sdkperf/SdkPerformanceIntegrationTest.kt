package kr.artel.orchestration.sdkperf

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.sdkperf.dto.*
import kr.artel.orchestration.sdkperf.service.SdkPerfIngestService
import kr.artel.orchestration.sdkperf.service.SdkPerfQueryService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@ActiveProfiles("test")
@SpringBootTest
class SdkPerformanceIntegrationTest {
    @Autowired lateinit var db:DatabaseClient
    @Autowired lateinit var ingest:SdkPerfIngestService
    @Autowired lateinit var query:SdkPerfQueryService

    @Test
    fun `raw samples survive outside runs and active run gets incremental summary`():Unit=runBlocking {
        val suffix=UUID.randomUUID().toString()
        val user=id("INSERT INTO app_user(display_name) VALUES('perf') RETURNING id")
        val project=id("INSERT INTO project(name,genre) VALUES('perf-$suffix','OTHER') RETURNING id")
        exec("INSERT INTO project_member(project_id,app_user_id,role) VALUES($project,$user,'OWNER')")
        val build=id("INSERT INTO game_build(project_id,version) VALUES($project,'$suffix') RETURNING id")
        val instance=id("INSERT INTO game_instance(project_id,name,platform,last_game_build_id) VALUES($project,'perf','UNITY',$build) RETURNING id")
        val testRun=id("INSERT INTO test_run(project_id,name) VALUES($project,'perf') RETURNING id")
        val message=performance(process=null)

        ingest.recordPerformance(instance,"ws-outside",message)
        assertThat(count("SELECT COUNT(*) FROM sdk_performance_sample WHERE game_instance_id=$instance AND qa_run_id IS NULL")).isEqualTo(1)

        val run=id("INSERT INTO qa_run(test_run_id,game_instance_id,started_by,status,started_at) VALUES($testRun,$instance,$user,'RUNNING',now()-interval '2 seconds') RETURNING id")
        ingest.recordDeviceContext(instance,"ws-active",SdkDeviceContextMessage("DEVICE_CONTEXT",1,SdkDeviceInfo(isEditor=false,processorType="CPU",graphicsDeviceName="GPU",sdkVersion="0.1.0")))
        ingest.recordPerformance(instance,"ws-active",message)

        val detail=query.runDetail(run,user)!!
        assertThat(detail.runId).isEqualTo(run)
        assertThat(detail.summary!!.sampleCount).isEqualTo(1)
        assertThat(detail.summary!!.cpuPercentMean).isNull()
        assertThat(detail.summary!!.processSampleRatio).isEqualTo(0.0)
        assertThat(detail.device!!.sdkVersion).isEqualTo("0.1.0")
        assertThat(detail.series.points).isNotEmpty
        assertThat(query.buildTrend(project,build,user)!!.runs.map { it.runId }).containsExactly(run)

        exec("DELETE FROM sdk_performance_sample WHERE game_instance_id=$instance")
        exec("DELETE FROM sdk_device_context WHERE game_instance_id=$instance")
        exec("DELETE FROM qa_run WHERE id=$run")
        exec("DELETE FROM test_run WHERE id=$testRun")
        exec("DELETE FROM game_instance WHERE id=$instance")
        exec("DELETE FROM game_build WHERE id=$build")
        exec("DELETE FROM project_member WHERE project_id=$project AND app_user_id=$user")
        exec("DELETE FROM project WHERE id=$project")
        exec("DELETE FROM app_user WHERE id=$user")
    }

    private fun performance(process:SdkProcessMetrics?)=SdkPerformanceMessage("PERFORMANCE",1,
        SdkFrameTimes(60,1000.0,16.67,15.0,40.0,18.0,30.0,25.0,25.0,2,33.33,16.67),
        SdkRunStatus(true,"Charging"),process)
    private suspend fun id(sql:String):Long=db.sql(sql).map { r,_->r.get("id",java.lang.Long::class.java)!!.toLong() }.flow().toList().single()
    private suspend fun count(sql:String):Long=db.sql(sql).map { r,_->r.get(0,java.lang.Long::class.java)!!.toLong() }.flow().toList().single()
    private suspend fun exec(sql:String){db.sql(sql).fetch().rowsUpdated().block()}
}
