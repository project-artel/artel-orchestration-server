package kr.artel.orchestration.sdkperf

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.sdkperf.dto.SdkDeviceContextMessage
import kr.artel.orchestration.sdkperf.dto.SdkDeviceInfo
import kr.artel.orchestration.sdkperf.dto.SdkFrameTimes
import kr.artel.orchestration.sdkperf.dto.SdkPerformanceMessage
import kr.artel.orchestration.sdkperf.dto.SdkProcessMetrics
import kr.artel.orchestration.sdkperf.dto.SdkRunStatus
import kr.artel.orchestration.sdkperf.service.SdkPerfIngestService
import kr.artel.orchestration.sdkperf.service.SdkPerfQueryService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * 저장(ARTEL-372)과 집계·조회(ARTEL-378)를 실제 PostgreSQL 위에서 확인한다.
 *
 * 여기서 보는 것은 확정된 Notion 계약이 요구하는 성질들이다 — 원본 보존, 없음과 0의 구분,
 * 에디터 런 제외, 런 길이에 독립적인 hitch 비교, 미측정 구간의 null 점.
 */
@ActiveProfiles("test")
@SpringBootTest
class SdkPerformanceIntegrationTest {

    @Autowired lateinit var db: DatabaseClient
    @Autowired lateinit var ingest: SdkPerfIngestService
    @Autowired lateinit var query: SdkPerfQueryService

    @Test
    fun `raw samples survive outside runs and the active run gets an incremental summary`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val message = performance(process = null)

        ingest.recordPerformance(instance, "ws-outside", message)
        assertThat(count("SELECT COUNT(*) FROM sdk_performance_sample WHERE game_instance_id=$instance AND qa_run_id IS NULL"))
            .isEqualTo(1)

        val run = world.startRun(instance, startedSecondsAgo = 2)
        ingest.recordDeviceContext(
            instance, "ws-active",
            SdkDeviceContextMessage("DEVICE_CONTEXT", 1, SdkDeviceInfo(isEditor = false, sdkVersion = "0.1.0"))
        )
        ingest.recordPerformance(instance, "ws-active", message)
        ingest.recordPerformance(instance, "ws-active", message)

        val detail = query.runDetail(run, world.user)!!
        assertThat(detail.runId).isEqualTo(run)
        assertThat(detail.summary!!.sampleCount).isEqualTo(2)
        assertThat(detail.summary!!.coveredMs).isEqualTo(2000.0)
        assertThat(detail.device!!.sdkVersion).isEqualTo("0.1.0")
        // 런 밖 표본은 요약에 들어가지 않지만 원본으로는 남아 있다.
        assertThat(count("SELECT COUNT(*) FROM sdk_performance_sample WHERE game_instance_id=$instance"))
            .isEqualTo(3)

        world.cleanUp()
    }

    /**
     * ARTEL-372/378의 핵심 불변식. `process`가 빠진 표본과 값이 0인 표본이 저장·집계·응답
     * 어디에서도 뒤섞이면 안 된다. 섞이면 미측정 플랫폼이 "CPU 0%"로 보인다.
     */
    @Test
    fun `absent process and zero-valued process stay distinguishable end to end`(): Unit = runBlocking {
        val world = seed()

        val quiet = world.instance("quiet")
        val quietRun = world.startRun(quiet, startedSecondsAgo = 1)
        ingest.recordPerformance(
            quiet, "ws-quiet",
            performance(
                process = SdkProcessMetrics(
                    cpuPercent = 0.0, workingSetBytes = 0, privateBytes = 0, managedHeapBytes = 0,
                    gen0Collections = 0, gen1Collections = 0, gen2Collections = 0, sampledMs = 1000.0
                )
            )
        )

        val blind = world.instance("blind")
        val blindRun = world.startRun(blind, startedSecondsAgo = 1)
        ingest.recordPerformance(blind, "ws-blind", performance(process = null))

        // 저장: has_process가 둘을 가른다.
        assertThat(count("SELECT COUNT(*) FROM sdk_performance_sample WHERE game_instance_id=$quiet AND has_process AND process_cpu_percent=0"))
            .isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM sdk_performance_sample WHERE game_instance_id=$blind AND NOT has_process AND process_cpu_percent IS NULL"))
            .isEqualTo(1)

        // 응답: 0은 값이고 없음은 null이다.
        val measured = query.runDetail(quietRun, world.user)!!.summary!!
        assertThat(measured.cpuPercentMean).isEqualTo(0.0)
        assertThat(measured.processSampleRatio).isEqualTo(1.0)
        assertThat(measured.gcCollections.gen0).isEqualTo(0)
        assertThat(measured.workingSetBytesMax).isEqualTo(0)

        val unmeasured = query.runDetail(blindRun, world.user)!!.summary!!
        assertThat(unmeasured.cpuPercentMean).isNull()
        assertThat(unmeasured.cpuPercentMax).isNull()
        assertThat(unmeasured.processSampleRatio).isEqualTo(0.0)
        assertThat(unmeasured.gcCollections.gen0).isNull()
        assertThat(unmeasured.workingSetBytesMax).isNull()

        world.cleanUp()
    }

    /** 평균이 0으로 끌려가지 않아야 한다 — `process`가 중간부터 빠진 런. */
    @Test
    fun `cpu mean ignores samples that carry no process block`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("partial")
        val run = world.startRun(instance, startedSecondsAgo = 1)

        ingest.recordPerformance(
            instance, "ws-partial",
            performance(process = SdkProcessMetrics(cpuPercent = 40.0, sampledMs = 1000.0))
        )
        ingest.recordPerformance(instance, "ws-partial", performance(process = null))

        val summary = query.runDetail(run, world.user)!!.summary!!
        // 40%가 한 번, 미측정이 한 번. 미측정을 0으로 세면 20%가 된다.
        assertThat(summary.cpuPercentMean).isEqualTo(40.0)
        assertThat(summary.processSampleRatio).isEqualTo(0.5)

        world.cleanUp()
    }

    /**
     * 계약은 `isEditor`"인" 런만 빼라고 한다. DEVICE_CONTEXT를 못 받아 `isEditor`를 모르는
     * 런까지 빼면 추세에서 런이 이유 없이 사라진다.
     */
    @Test
    fun `build trend drops editor runs but keeps runs with unknown device context`(): Unit = runBlocking {
        val world = seed()

        val standalone = world.instance("standalone")
        ingest.recordDeviceContext(
            standalone, "ws-standalone",
            SdkDeviceContextMessage("DEVICE_CONTEXT", 1, SdkDeviceInfo(isEditor = false))
        )
        val standaloneRun = world.startRun(standalone, startedSecondsAgo = 1)
        ingest.recordPerformance(standalone, "ws-standalone", performance(process = null))

        val editor = world.instance("editor")
        ingest.recordDeviceContext(
            editor, "ws-editor",
            SdkDeviceContextMessage("DEVICE_CONTEXT", 1, SdkDeviceInfo(isEditor = true))
        )
        val editorRun = world.startRun(editor, startedSecondsAgo = 1)
        ingest.recordPerformance(editor, "ws-editor", performance(process = null))

        val unknown = world.instance("unknown")
        val unknownRun = world.startRun(unknown, startedSecondsAgo = 1)
        ingest.recordPerformance(unknown, "ws-unknown", performance(process = null))

        val trend = query.buildTrend(world.project, world.build, world.user)!!
        assertThat(trend.gameBuildId).isEqualTo(world.build)
        assertThat(trend.runs.map { it.runId })
            .containsExactlyInAnyOrder(standaloneRun, unknownRun)
            .doesNotContain(editorRun)

        world.cleanUp()
    }

    /** 런 길이가 달라도 분당 hitch로는 비교가 성립해야 한다. */
    @Test
    fun `hitches per minute is comparable across runs of different length`(): Unit = runBlocking {
        val world = seed()

        val shortInstance = world.instance("short")
        val shortRun = world.startRun(shortInstance, startedSecondsAgo = 2)
        ingest.recordPerformance(shortInstance, "ws-short", performance(process = null))

        val longInstance = world.instance("long")
        val longRun = world.startRun(longInstance, startedSecondsAgo = 600)
        ingest.recordPerformance(longInstance, "ws-long", performance(process = null))

        val shortSummary = query.runDetail(shortRun, world.user)!!.summary!!
        val longSummary = query.runDetail(longRun, world.user)!!.summary!!

        // 같은 표본이면 런 길이와 무관하게 같은 값이다. 분모가 런 길이가 아니라 덮은 시간이라서다.
        assertThat(shortSummary.hitchesPerMinute).isEqualTo(longSummary.hitchesPerMinute)
        assertThat(shortSummary.hitchesPerMinute).isEqualTo(120.0)
        // 반대로 커버리지는 런 길이에 따라 갈린다 — 긴 런은 거의 재지 못한 런이다.
        assertThat(longSummary.coverageRatio).isLessThan(shortSummary.coverageRatio)
        assertThat(longSummary.coverageRatio).isLessThan(0.8)

        world.cleanUp()
    }

    /** 측정되지 않은 구간은 점이 사라지는 것이 아니라 값이 null인 점으로 남는다. */
    @Test
    fun `unmeasured stretches become null points rather than missing points`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("gappy")
        val run = world.startRun(instance, startedSecondsAgo = 0)
        ingest.recordPerformance(instance, "ws-gappy", performance(process = null))
        // 런 시작을 표본 시각 기준으로 10초 앞당긴다. 앱과 DB의 벽시계 편차에 기대지 않고
        // "앞의 10초는 아무도 재지 않았다"를 만들기 위한 것이다.
        exec(
            "UPDATE qa_run SET started_at=" +
                "(SELECT MIN(received_at) FROM sdk_performance_sample WHERE qa_run_id=$run)" +
                "-interval '10 seconds' WHERE id=$run"
        )

        val series = query.runDetail(run, world.user)!!.series
        assertThat(series.bucketMs).isEqualTo(1000)
        // 시작부터 지금까지가 1초 간격 점으로 빠짐없이 채워져 있어야 한다.
        assertThat(series.points.size).isGreaterThanOrEqualTo(11)
        assertThat(series.points.map { it.atMs }).isSorted()
        assertThat(series.points.first().atMs).isEqualTo(0)

        val measured = series.points.filter { it.frameMeanMs != null }
        val unmeasured = series.points.filter { it.frameMeanMs == null }
        assertThat(measured).hasSize(1)
        assertThat(unmeasured).isNotEmpty
        // 빈 구간은 0이 아니라 전부 null이고, 왜 비었는지를 isFocused가 알려준다.
        assertThat(unmeasured).allSatisfy {
            assertThat(it.frameP95Ms).isNull()
            assertThat(it.frameMaxMs).isNull()
            assertThat(it.hitchCount).isNull()
            assertThat(it.cpuPercent).isNull()
            assertThat(it.workingSetBytes).isNull()
            assertThat(it.isFocused).isFalse()
        }
        assertThat(measured.single().frameMeanMs).isEqualTo(1000.0 / 60, within(0.001))
        assertThat(measured.single().hitchCount).isEqualTo(2)
        assertThat(measured.single().isFocused).isTrue()

        world.cleanUp()
    }

    /** 표본이 하나도 없는 런은 404가 아니라 summary null + 빈 시계열이다. */
    @Test
    fun `a run without samples answers with a null summary instead of an error`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("silent")
        val run = world.startRun(instance, startedSecondsAgo = 5)

        val detail = query.runDetail(run, world.user)!!
        assertThat(detail.summary).isNull()
        assertThat(detail.series.points).isEmpty()

        world.cleanUp()
    }

    /** 남의 프로젝트 런은 존재 자체가 보이지 않아야 한다. */
    @Test
    fun `a run in another members project is not visible`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("private")
        val run = world.startRun(instance, startedSecondsAgo = 1)
        val stranger = id("INSERT INTO app_user(display_name) VALUES('stranger') RETURNING id")

        assertThat(query.runDetail(run, stranger)).isNull()
        assertThat(query.buildTrend(world.project, world.build, stranger)).isNull()

        world.cleanUp()
        exec("DELETE FROM app_user WHERE id=$stranger")
    }

    // ---- 시드 ----

    private inner class World(val user: Long, val project: Long, val build: Long, val testRun: Long) {
        suspend fun instance(name: String): Long = id(
            "INSERT INTO game_instance(project_id,name,platform,last_game_build_id) " +
                "VALUES($project,'$name','UNITY',$build) RETURNING id"
        )

        suspend fun startRun(instance: Long, startedSecondsAgo: Long): Long = id(
            "INSERT INTO qa_run(test_run_id,game_instance_id,started_by,status,started_at) " +
                "VALUES($testRun,$instance,$user,'RUNNING',now()-interval '$startedSecondsAgo seconds') RETURNING id"
        )

        /**
         * 삭제 순서는 FK가 정한다. qa_run을 지우면 요약·시계열·budget 도수는 CASCADE로 따라
         * 사라지지만, 원본 표본은 qa_run을 참조하므로 먼저 지워야 한다.
         */
        suspend fun cleanUp() {
            val instances = "SELECT id FROM game_instance WHERE project_id=$project"
            exec("DELETE FROM sdk_performance_sample WHERE game_instance_id IN ($instances)")
            exec("DELETE FROM sdk_device_context WHERE game_instance_id IN ($instances)")
            exec("DELETE FROM qa_run WHERE game_instance_id IN ($instances)")
            exec("DELETE FROM test_run WHERE project_id=$project")
            exec("DELETE FROM game_instance WHERE project_id=$project")
            exec("DELETE FROM game_build WHERE project_id=$project")
            exec("DELETE FROM project_member WHERE project_id=$project")
            exec("DELETE FROM project WHERE id=$project")
            exec("DELETE FROM app_user WHERE id=$user")
        }
    }

    private suspend fun seed(): World {
        val suffix = UUID.randomUUID().toString()
        val user = id("INSERT INTO app_user(display_name) VALUES('perf') RETURNING id")
        val project = id("INSERT INTO project(name,genre) VALUES('perf-$suffix','OTHER') RETURNING id")
        exec("INSERT INTO project_member(project_id,app_user_id,role) VALUES($project,$user,'OWNER')")
        val build = id("INSERT INTO game_build(project_id,version) VALUES($project,'$suffix') RETURNING id")
        val testRun = id("INSERT INTO test_run(project_id,name) VALUES($project,'perf') RETURNING id")
        return World(user, project, build, testRun)
    }

    /** 프레임 60개를 정확히 1초에 담은 표본. hitch 2회는 분당 120회로 환산된다. */
    private fun performance(process: SdkProcessMetrics?) = SdkPerformanceMessage(
        type = "PERFORMANCE",
        id = 1,
        frameTimes = SdkFrameTimes(
            frameCount = 60, sampledMs = 1000.0, meanMs = 16.67, minMs = 15.0, maxMs = 40.0,
            p95Ms = 18.0, p99Ms = 30.0, onePercentLowFps = 25.0, pointOnePercentLowFps = 25.0,
            hitchCount = 2, hitchThresholdMs = 33.33, budgetMs = 16.67
        ),
        status = SdkRunStatus(isFocused = true, batteryStatus = "Charging"),
        process = process
    )

    private suspend fun id(sql: String): Long =
        db.sql(sql).map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }.flow().toList().single()

    private suspend fun count(sql: String): Long =
        db.sql(sql).map { row, _ -> row.get(0, java.lang.Long::class.java)!!.toLong() }.flow().toList().single()

    private suspend fun exec(sql: String) {
        db.sql(sql).fetch().awaitRowsUpdated()
    }
}
