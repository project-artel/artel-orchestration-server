package kr.artel.orchestration.sdkperf

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.sdkperf.dto.MetricGroupAvailability
import kr.artel.orchestration.sdkperf.metrics.MetricGroupReader
import kr.artel.orchestration.sdkperf.dto.SdkDeviceContextMessage
import kr.artel.orchestration.sdkperf.dto.SdkDeviceInfo
import kr.artel.orchestration.sdkperf.dto.SdkFrameTimes
import kr.artel.orchestration.sdkperf.dto.SdkPerformanceMessage
import kr.artel.orchestration.sdkperf.dto.SdkProcessMetrics
import kr.artel.orchestration.sdkperf.dto.SdkRunStatus
import kr.artel.orchestration.sdkperf.repository.SdkPerformanceRepository
import kr.artel.orchestration.sdkperf.service.SdkPerfIngestService
import kr.artel.orchestration.sdkperf.service.SdkPerfQueryService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterEach
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
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var ingest: SdkPerfIngestService
    @Autowired lateinit var query: SdkPerfQueryService
    @Autowired lateinit var repository: SdkPerformanceRepository

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
    }

    /** 남의 프로젝트 런은 존재 자체가 보이지 않아야 한다. */
    @Test
    fun `a run in another members project is not visible`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("private")
        val run = world.startRun(instance, startedSecondsAgo = 1)
        val stranger = id("INSERT INTO app_user (display_name, nickname, user_tag) VALUES ('stranger', 'stranger-' || gen_random_uuid(), '0000') RETURNING id")

        assertThat(query.runDetail(run, stranger)).isNull()
        assertThat(query.buildTrend(world.project, world.build, stranger)).isNull()
        exec("DELETE FROM app_user WHERE id=$stranger")
    }


    /**
     * 재연결 순서. `DEVICE_CONTEXT`는 표본보다 늦게 올 수 있고, 그때는 요약 행이 이미 있어
     * `saveDevice`의 UPDATE 경로가 `collected_groups`를 옮긴다. 앞선 테스트들은 전부 반대
     * 순서라 이 경로를 지나지 않았다.
     */
    @Test
    fun `collectedGroups reaches the summary when the device context arrives after the first sample`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 3)

        ingest.recordPerformance(instance, "ws", performance(process = null))
        ingest.recordDeviceContext(
            instance, "ws",
            SdkDeviceContextMessage(
                "DEVICE_CONTEXT", 1,
                SdkDeviceInfo(isEditor = false, collectedGroups = listOf("renderCounters"))
            )
        )

        val groups = query.runDetail(run, world.user)!!.summary!!.groups
        assertThat(groups.getValue("renderCounters").availability).isEqualTo(MetricGroupAvailability.UNSUPPORTED)
        assertThat(groups.getValue("gc").availability).isEqualTo(MetricGroupAvailability.NOT_REPORTED)
    }

    /**
     * 봉투만 오고 숫자 잎이 없는 군은 `MEASURED`가 아니다.
     *
     * 계약이 `MEASURED`를 "값이 온 적 있음"으로 정의한다. 봉투만으로 `MEASURED`를 주면
     * `sampleRatio: 1.0`에 `metrics: {}`라는, 재보니 아무것도 없더라는 뜻의 응답이 나간다.
     * `source`는 값이 없어도 알 수 있으므로 잃지 않는다.
     */
    @Test
    fun `a group envelope carrying no numeric leaf is UNSUPPORTED rather than MEASURED`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 2)
        ingest.recordPerformance(instance, "ws", performanceFromJson(
            """{"renderCounters":{"source":"PROFILER_RECORDER"}}"""))

        val group = query.runDetail(run, world.user)!!.summary!!.groups.getValue("renderCounters")
        assertThat(group.availability).isEqualTo(MetricGroupAvailability.UNSUPPORTED)
        assertThat(group.metrics).isNull()
        assertThat(group.source).isEqualTo("PROFILER_RECORDER")
    }

    /**
     * 상한은 모르는 payload를 받아들이는 대가를 막는 장치다. 넘치면 잘라내고 나머지는 정상
     * 저장해야 한다 — 표본을 통째로 버리면 프레임 지표까지 함께 사라진다.
     */
    @Test
    fun `a payload past the group and leaf caps is trimmed instead of failing the sample`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 2)
        val manyGroups = (1..MetricGroupReader.MAX_GROUPS_PER_SAMPLE + 8)
            .joinToString(",") { """"g$it":{"v":$it}""" }
        val fatGroup = """"gc":{${(1..MetricGroupReader.MAX_LEAVES_PER_GROUP + 8).joinToString(",") { """"leaf$it":$it""" }}}"""

        ingest.recordPerformance(instance, "ws", performanceFromJson("{$manyGroups,$fatGroup}"))

        val detail = query.runDetail(run, world.user)!!
        // 프레임 지표는 살아 있다. 이것이 상한의 목적이다.
        assertThat(detail.summary!!.sampleCount).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM sdk_performance_sample_group"))
            .isEqualTo(MetricGroupReader.MAX_GROUPS_PER_SAMPLE.toLong())
        assertThat(count("SELECT COUNT(*) FROM sdk_performance_run_group_metric WHERE group_name='gc'"))
            .isLessThanOrEqualTo(MetricGroupReader.MAX_LEAVES_PER_GROUP.toLong())
    }

    /**
     * 같은 경로로 접히는 두 키(`{"a":{"b":1},"a.b":2}`)가 한 upsert 문장에 두 번 들어가면
     * Postgres가 21000으로 문장 전체를 거부하고, 트랜잭션이 롤백돼 표본이 통째로 사라진다.
     * 모르는 payload를 받는 것이 이 기능의 전제라 방어되어 있어야 한다.
     */
    @Test
    fun `leaf paths that collide after flattening do not abort the sample`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 2)

        ingest.recordPerformance(instance, "ws", performanceFromJson("""{"gc":{"a":{"b":1},"a.b":2}}"""))

        assertThat(query.runDetail(run, world.user)!!.summary!!.sampleCount).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM sdk_performance_run_group_metric WHERE group_name='gc' AND leaf_path='a.b'"))
            .isEqualTo(1)
    }

    /** `source`가 컬럼 폭을 넘겨도 표본이 사라지면 안 된다. 잘라서 담는다. */
    @Test
    fun `an over long source is truncated rather than rolling back the sample`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 2)
        val long = "E".repeat(MetricGroupReader.MAX_SOURCE_LENGTH + 20)

        ingest.recordPerformance(instance, "ws", performanceFromJson("""{"renderCounters":{"source":"$long","drawCalls":10}}"""))

        val group = query.runDetail(run, world.user)!!.summary!!.groups.getValue("renderCounters")
        assertThat(group.availability).isEqualTo(MetricGroupAvailability.MEASURED)
        assertThat(group.source).hasSize(MetricGroupReader.MAX_SOURCE_LENGTH)
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

        suspend fun completeRun(run: Long) =
            exec("UPDATE qa_run SET status='COMPLETED',completed_at=now() WHERE id=$run")

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

    /**
     * 이 클래스가 씨 뿌린 것들. [cleanSeeded] 가 테스트마다 이걸 비운다.
     *
     * 종전에는 각 테스트가 본문 끝에서 `world.cleanUp()` 을 불렀는데, **실패한 테스트는 거기까지
     * 못 간다.** 그러면 행이 남고, 이 스위트의 단정 여럿이 `SELECT COUNT(*) FROM ...` 처럼 범위를
     * 안 거는 전역 집계라 뒤따르는 테스트가 남의 행을 함께 세어 무너진다. 실패 하나가 셋이 되던
     * 이유다(ARTEL-795).
     */
    private val seeded = mutableListOf<World>()

    @AfterEach
    fun cleanSeeded(): Unit = runBlocking {
        seeded.forEach { it.cleanUp() }
        seeded.clear()
    }

    private suspend fun seed(): World {
        val suffix = UUID.randomUUID().toString()
        val user = id("INSERT INTO app_user (display_name, nickname, user_tag) VALUES ('perf', 'perf-' || gen_random_uuid(), '0000') RETURNING id")
        val project = id("INSERT INTO project(name,genre) VALUES('perf-$suffix','OTHER') RETURNING id")
        exec("INSERT INTO project_member(project_id,app_user_id,role) VALUES($project,$user,'OWNER')")
        val build = id("INSERT INTO game_build(project_id,version) VALUES($project,'$suffix') RETURNING id")
        val testRun = id("INSERT INTO test_run(project_id,name) VALUES($project,'perf') RETURNING id")
        return World(user, project, build, testRun).also { seeded += it }
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


    // ---- 지표군 확장 (ARTEL-435) ----

    /**
     * 이 작업의 핵심 성질. **계약에도 서버 코드에도 없는 군을 보내도 저장되고 응답에 나온다.**
     *
     * 이것이 깨지면 SDK가 지표군을 하나 더할 때마다 서버를 먼저 고쳐야 값이 남고, 그 구조가
     * 곧 "군 하나 추가에 마이그레이션 하나"로 돌아간다.
     *
     * 일부러 실제 JSON에서 역직렬화한다. `@JsonAnySetter`가 끊기면 군이 조용히 사라지는데,
     * 코틀린 생성자로 만든 객체로는 그 회귀가 잡히지 않는다.
     */
    @Test
    fun `an undeclared metric group survives ingestion and appears in the response`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 2)

        ingest.recordPerformance(instance, "ws", performanceFromJson("""{"somethingNobodyDeclared":{"weird":12.5}}"""))

        assertThat(count("SELECT COUNT(*) FROM sdk_performance_sample_group WHERE group_name='somethingNobodyDeclared'"))
            .isEqualTo(1)
        val group = query.runDetail(run, world.user)!!.summary!!.groups.getValue("somethingNobodyDeclared")
        assertThat(group.availability).isEqualTo(MetricGroupAvailability.MEASURED)
        assertThat(group.sampleRatio).isEqualTo(1.0)
        // 모르는 잎의 기본 롤업은 평균과 최대다.
        assertThat(group.metrics).containsEntry("weirdMean", 12.5).containsEntry("weirdMax", 12.5)
    }

    /**
     * `null`(값 하나 없음)·`0`(재봤더니 0) 위의 세 번째 상태.
     *
     * "재려 했으나 카운터가 없었다"와 "이 SDK는 이 군을 모른다"를 뭉개면, 값이 사라졌을 때
     * 게임 코드 탓인지 SDK 탓인지 알 수 없어 빌드 간 회귀 판단이 성립하지 않는다.
     */
    @Test
    fun `collectedGroups separates a group that could not be measured from one the SDK never collects`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 2)
        ingest.recordDeviceContext(
            instance, "ws",
            SdkDeviceContextMessage(
                "DEVICE_CONTEXT", 1,
                SdkDeviceInfo(isEditor = false, collectedGroups = listOf("gc", "renderCounters"))
            )
        )

        // gc만 실제로 값이 온다. renderCounters는 선언됐지만 이 플랫폼에서 카운터가 없었다.
        ingest.recordPerformance(instance, "ws", performanceFromJson("""{"gc":{"gcUsedBytes":1024}}"""))

        val groups = query.runDetail(run, world.user)!!.summary!!.groups
        assertThat(groups.getValue("gc").availability).isEqualTo(MetricGroupAvailability.MEASURED)
        assertThat(groups.getValue("renderCounters").availability).isEqualTo(MetricGroupAvailability.UNSUPPORTED)
        assertThat(groups.getValue("renderCounters").metrics).isNull()
        // 선언조차 되지 않은 군.
        assertThat(groups.getValue("sdkOverhead").availability).isEqualTo(MetricGroupAvailability.NOT_REPORTED)
    }

    /** `collectedGroups` 이전 SDK. 새 군을 안 보내는 것이지 못 재는 것이 아니다. */
    @Test
    fun `an SDK that predates collectedGroups reports every new group as NOT_REPORTED`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 2)
        ingest.recordDeviceContext(
            instance, "ws",
            SdkDeviceContextMessage("DEVICE_CONTEXT", 1, SdkDeviceInfo(isEditor = false, sdkVersion = "0.1.0"))
        )
        ingest.recordPerformance(instance, "ws", performance(process = null))

        val groups = query.runDetail(run, world.user)!!.summary!!.groups
        assertThat(groups.getValue("gc").availability).isEqualTo(MetricGroupAvailability.NOT_REPORTED)
        assertThat(groups.getValue("renderCounters").availability).isEqualTo(MetricGroupAvailability.NOT_REPORTED)
    }

    /**
     * 델타 카운터는 합, 그 밖은 평균·최대.
     *
     * GC가 런 전체에서 몇 번 돌았는지가 필요하지, 창당 평균 몇 번인지가 필요한 것이 아니다.
     */
    @Test
    fun `counter leaves sum across samples while gauge leaves average`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 3)

        ingest.recordPerformance(instance, "ws", performanceFromJson(
            """{"gc":{"collections":{"gen0":3,"gen1":1,"gen2":0},"allocatedInFrameBytes":1000}}"""))
        ingest.recordPerformance(instance, "ws", performanceFromJson(
            """{"gc":{"collections":{"gen0":5,"gen1":0,"gen2":0},"allocatedInFrameBytes":3000}}"""))

        val metrics = query.runDetail(run, world.user)!!.summary!!.groups.getValue("gc").metrics!!
        @Suppress("UNCHECKED_CAST")
        val collections = metrics["collections"] as Map<String, Any?>
        assertThat(collections).containsEntry("gen0", 8L).containsEntry("gen1", 1L)
        // gen2는 두 표본 모두 0이다. 없음이 아니라 "재봤더니 0"이므로 자리를 지킨다.
        assertThat(collections).containsEntry("gen2", 0L)
        assertThat(metrics).containsEntry("allocatedInFrameBytesMean", 2000.0)
        assertThat(metrics).containsEntry("allocatedInFrameBytesMax", 3000.0)
    }

    /**
     * 출처가 다른 같은 이름의 값을 한 필드에 담지 않는다.
     *
     * Editor `UnityStats`의 draw call과 Standalone `ProfilerRecorder`의 draw call은 이름이 같아도
     * 다른 값이다. 화면이 두 런을 같은 선에 잇지 않으려면 응답에 출처가 있어야 한다.
     */
    @Test
    fun `renderCounters carries its source through to the build trend`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 2)
        ingest.recordDeviceContext(
            instance, "ws", SdkDeviceContextMessage("DEVICE_CONTEXT", 1, SdkDeviceInfo(isEditor = false))
        )
        ingest.recordPerformance(instance, "ws", performanceFromJson(
            """{"renderCounters":{"source":"PROFILER_RECORDER","drawCalls":812}}"""))
        world.completeRun(run)

        val trend = query.buildTrend(world.project, world.build, world.user)!!
        val group = trend.runs.single().groups.getValue("renderCounters")
        assertThat(group.source).isEqualTo("PROFILER_RECORDER")
        assertThat(group.metrics).containsEntry("drawCallsMean", 812.0)
    }

    /** 표본이 없던 버킷에는 군 키 자체가 없다. 빈 군을 채우면 응답이 버킷 수 × 군 수로 부푼다. */
    @Test
    fun `series points carry group metrics only for buckets that had samples`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 4)
        ingest.recordPerformance(instance, "ws", performanceFromJson("""{"gc":{"gcUsedBytes":2048}}"""))

        val points = query.runDetail(run, world.user)!!.series.points
        assertThat(points.count { it.groups.containsKey("gc") }).isEqualTo(1)
        assertThat(points.first { it.groups.containsKey("gc") }.groups.getValue("gc").metrics)
            .containsEntry("gcUsedBytesMax", 2048.0)
        // 나머지 점은 미측정 구간이라 군이 비어 있다.
        assertThat(points.any { it.groups.isEmpty() }).isTrue()
    }

    /**
     * 보존 정책은 원본만 지운다 (ARTEL-434).
     *
     * 요약과 시계열이 함께 사라지면 오래된 런의 상세 화면이 통째로 비어, 보존 정책이 조회 계약을
     * 바꿔 버린다. 지워지는 것은 표본 단위 드릴다운뿐이어야 한다.
     */
    @Test
    fun `retention removes raw samples while the run detail response stays whole`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 2)
        ingest.recordPerformance(instance, "ws", performanceFromJson("""{"gc":{"gcUsedBytes":4096}}"""))
        exec("UPDATE sdk_performance_sample SET received_at=now()-interval '90 days' WHERE qa_run_id=$run")

        val deleted = repository.deleteSamplesOlderThan(java.time.Instant.now().minus(java.time.Duration.ofDays(30)), 1000)

        assertThat(deleted).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM sdk_performance_sample WHERE qa_run_id=$run")).isEqualTo(0)
        // 군 payload는 원본을 따라 CASCADE로 사라진다.
        assertThat(count("SELECT COUNT(*) FROM sdk_performance_sample_group")).isEqualTo(0)

        val detail = query.runDetail(run, world.user)!!
        assertThat(detail.summary!!.sampleCount).isEqualTo(1)
        assertThat(detail.summary!!.groups.getValue("gc").availability).isEqualTo(MetricGroupAvailability.MEASURED)
        assertThat(detail.series.points.any { it.groups.containsKey("gc") }).isTrue()
    }

    /** 최상위 스칼라는 군이 아니다. `type`/`id`가 군으로 잡히면 롤업 테이블이 오염된다. */
    @Test
    fun `top level scalars are not mistaken for metric groups`(): Unit = runBlocking {
        val world = seed()
        val instance = world.instance("main")
        val run = world.startRun(instance, startedSecondsAgo = 2)
        ingest.recordPerformance(instance, "ws", performanceFromJson("""{"gc":{"gcUsedBytes":1}}"""))

        val measured = query.runDetail(run, world.user)!!.summary!!.groups
            .filterValues { it.availability == MetricGroupAvailability.MEASURED }
        assertThat(measured.keys).containsExactly("gc")
    }

    /**
     * 지표군은 `@JsonAnySetter`로만 들어온다. 코틀린 생성자로 만든 객체는 그 경로를 지나지 않아
     * 역직렬화가 끊겨도 테스트가 통과해 버린다. 실제 와이어 JSON에서 만든다.
     *
     * @param groupsJson `{"gc":{...}}` 형태. 최상위 봉투에 그대로 합쳐진다.
     */
    private fun performanceFromJson(groupsJson: String): SdkPerformanceMessage {
        val envelope = """{"type":"PERFORMANCE","id":1,""" +
            """"frameTimes":{"frameCount":60,"sampledMs":1000.0,"meanMs":16.67,"minMs":15.0,"maxMs":40.0,""" +
            """"p95Ms":18.0,"p99Ms":30.0,"onePercentLowFps":25.0,"pointOnePercentLowFps":25.0,""" +
            """"hitchCount":2,"hitchThresholdMs":33.33,"budgetMs":16.67},""" +
            """"status":{"isFocused":true,"batteryStatus":"Charging"}"""
        val groups = groupsJson.trim().removePrefix("{").removeSuffix("}")
        return objectMapper.readValue("$envelope,$groups}", SdkPerformanceMessage::class.java)
    }

    private suspend fun id(sql: String): Long =
        db.sql(sql).map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }.flow().toList().single()

    private suspend fun count(sql: String): Long =
        db.sql(sql).map { row, _ -> row.get(0, java.lang.Long::class.java)!!.toLong() }.flow().toList().single()

    private suspend fun exec(sql: String) {
        db.sql(sql).fetch().awaitRowsUpdated()
    }
}
