package kr.artel.orchestration.qa

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.dto.QaVerdictTrendBucket
import kr.artel.orchestration.qa.service.QaRunGates
import kr.artel.orchestration.qa.service.QaTryPersistenceService
import kr.artel.orchestration.qa.service.QaVerdictTrendService
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

/**
 * 대시보드 QA 판정 추이(ARTEL-1386). 판정은 실제 실행이 남기는 것과 같은 `CASE_VERDICT` 행을 시각을 정해 넣는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaVerdictTrendIntegrationTest {

    @Autowired private lateinit var service: QaVerdictTrendService
    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var database: DatabaseClient
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    private val from = Instant.parse("2026-10-01T00:00:00Z")
    private val to = Instant.parse("2026-10-08T00:00:00Z")

    @BeforeEach
    fun before(): Unit = runBlocking { wipe() }

    @AfterEach
    fun after(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        for (table in listOf(
            "qa_case_attempt", "qa_log", "qa_try", "qa_run", "test_case_identifier", "test_case", "game_build",
            "game_instance", "test_run", "test_scenario", "project_member", "project", "oauth_identity", "app_user"
        )) {
            database.sql("DELETE FROM $table").fetch().rowsUpdated().awaitFirstOrNull()
        }
    }

    @Test
    fun `날마다 통과 · 실패 · 회귀를 가르고 가장 많이 실패한 Test Case 를 싣는다`(): Unit = runBlocking {
        val seed = seed()
        // 10/1 KST: TC-1 통과, TC-2 실패 · 10/2 KST: TC-1 실패(앞이 통과 → 회귀), TC-2 실패 두 번, TC-3 통과
        verdict(seed.tryIds[0], seed.caseIds[0], "VERIFIED", "2026-10-01T01:00:00Z")
        verdict(seed.tryIds[0], seed.caseIds[1], "FAILED", "2026-10-01T01:05:00Z")
        verdict(seed.tryIds[0], seed.caseIds[0], "FAILED", "2026-10-02T01:00:00Z")
        verdict(seed.tryIds[0], seed.caseIds[1], "FAILED", "2026-10-02T01:05:00Z")
        verdict(seed.tryIds[0], seed.caseIds[1], "FAILED", "2026-10-02T01:10:00Z")
        verdict(seed.tryIds[0], seed.caseIds[2], "VERIFIED", "2026-10-02T01:15:00Z")

        val trend = service.trend(seed.projectId, seed.userId, from, to, QaVerdictTrendBucket.DAY, "Asia/Seoul", null)

        assertThat(trend.buckets.map { it.key }).containsExactly("2026-10-01", "2026-10-02")
        val first = trend.buckets[0]
        assertThat(listOf(first.passed, first.failed, first.regression)).containsExactly(1, 1, 0)
        assertThat(first.runs).isEqualTo(1)
        val second = trend.buckets[1]
        assertThat(listOf(second.passed, second.failed, second.regression)).containsExactly(1, 2, 1)
        assertThat(second.topFailing!!.testCaseId).isEqualTo(seed.caseIds[1].toString())
        assertThat(second.topFailing!!.failures).isEqualTo(2)
        val number = database.sql("SELECT case_number FROM test_case_identifier WHERE test_case_id = :id").bind("id", seed.caseIds[1])
            .map { row -> (row.get("case_number") as Number).toLong() }.one().awaitFirstOrNull()
        assertThat(second.topFailing!!.caseNumber).isEqualTo(number)
        assertThat(second.topFailing!!.title).isEqualTo("step 2")
    }

    @Test
    fun `회귀는 기간 밖의 앞 판정까지 보고 정한다`(): Unit = runBlocking {
        val seed = seed()
        verdict(seed.tryIds[0], seed.caseIds[0], "VERIFIED", "2026-09-20T01:00:00Z")
        verdict(seed.tryIds[0], seed.caseIds[0], "FAILED", "2026-10-03T01:00:00Z")

        // 10/1 칸은 실행 시작일이라 실행 수만 있다. 판정은 10/3 칸에 있다.
        val bucket = service.trend(seed.projectId, seed.userId, from, to, QaVerdictTrendBucket.DAY, "Asia/Seoul", null)
            .buckets.single { it.key == "2026-10-03" }

        assertThat(bucket.regression).isEqualTo(1)
        assertThat(bucket.failed).isZero()
        assertThat(bucket.passed).isZero()
    }

    @Test
    fun `미판정은 결론 못 낸 시도와 미판정 시나리오에 담긴 Test Case 를 센다`(): Unit = runBlocking {
        val seed = seed()
        database.sql(
            "INSERT INTO qa_case_attempt (id, qa_try_id, test_case_id, project_id, case_snapshot, started_at, finished_at, verdict) " +
                "VALUES (:id, :tryId, :caseId, :projectId, '{}'::jsonb, :at, :at, 'INCONCLUSIVE')"
        ).bind("id", UUID.randomUUID()).bind("tryId", seed.tryIds[0]).bind("caseId", seed.caseIds[0])
            .bind("projectId", seed.projectId).bind("at", Instant.parse("2026-10-04T01:00:00Z"))
            .fetch().rowsUpdated().awaitFirstOrNull()
        // 두 번째 시나리오(Test Case 3개)가 모델 API 오류로 미판정
        database.sql(
            "UPDATE qa_try SET status = 'UNDECIDED', stop_code = 'llm_credit_exhausted', completed_at = :at WHERE id = :id"
        ).bind("at", Instant.parse("2026-10-04T02:00:00Z")).bind("id", seed.tryIds[1]).fetch().rowsUpdated().awaitFirstOrNull()

        val bucket = service.trend(seed.projectId, seed.userId, from, to, QaVerdictTrendBucket.DAY, "Asia/Seoul", null)
            .buckets.single { it.key == "2026-10-04" }

        assertThat(bucket.undecided).isEqualTo(4)
    }

    @Test
    fun `실행마다 묶으면 실행 하나가 칸 하나고 버린 런은 빠진다`(): Unit = runBlocking {
        val seed = seed()
        verdict(seed.tryIds[0], seed.caseIds[0], "VERIFIED", "2026-10-01T01:00:00Z")

        val byRun = service.trend(seed.projectId, seed.userId, from, to, QaVerdictTrendBucket.RUN, "Asia/Seoul", null)
        assertThat(byRun.buckets.map { it.key }).containsExactly(seed.runId.toString())
        assertThat(byRun.buckets.single().startedAt).isNotNull()

        database.sql("UPDATE qa_run SET discarded_at = now() WHERE id = :id").bind("id", seed.runId).fetch().rowsUpdated().awaitFirstOrNull()
        assertThat(service.trend(seed.projectId, seed.userId, from, to, QaVerdictTrendBucket.RUN, "Asia/Seoul", null).buckets).isEmpty()
    }

    @Test
    fun `주로 묶으면 월요일이 열쇠고 기간 안의 빌드를 싣는다`(): Unit = runBlocking {
        val seed = seed()
        verdict(seed.tryIds[0], seed.caseIds[0], "VERIFIED", "2026-10-01T01:00:00Z") // 목
        verdict(seed.tryIds[0], seed.caseIds[1], "VERIFIED", "2026-10-06T01:00:00Z") // 다음 주 화
        database.sql("INSERT INTO game_build (project_id, version, is_editor, created_at) VALUES (:p, '1.4.1', true, :at)")
            .bind("p", seed.projectId).bind("at", Instant.parse("2026-10-05T00:00:00Z")).fetch().rowsUpdated().awaitFirstOrNull()

        val trend = service.trend(seed.projectId, seed.userId, from, to, QaVerdictTrendBucket.WEEK, "Asia/Seoul", null)

        assertThat(trend.buckets.map { it.key }).containsExactly("2026-09-28", "2026-10-05")
        assertThat(trend.builds.map { it.version }).containsExactly("1.4.1")
        assertThat(trend.builds.single().isEditor).isTrue()
    }

    @Test
    fun `참여자가 아니면 404 이고 기간이 거꾸로면 400 이다`(): Unit = runBlocking {
        val seed = seed()
        val stranger = oauthUserService.upsert(
            OAuthIdentity("github", "13862", "stranger", "stranger", null, "stranger1386@example.com")
        ).userId.toLong()

        assertThatThrownBy { runBlocking { service.trend(seed.projectId, stranger, from, to, QaVerdictTrendBucket.DAY, "Asia/Seoul", null) } }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { runBlocking { service.trend(seed.projectId, seed.userId, to, from, QaVerdictTrendBucket.DAY, "Asia/Seoul", null) } }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { runBlocking { service.trend(seed.projectId, seed.userId, from, to, QaVerdictTrendBucket.DAY, "Mars/Base", null) } }
            .isInstanceOf(BadRequestException::class.java)
    }

    // ----------------------------------------------------------------- helpers

    private data class Seed(val projectId: Long, val userId: Long, val runId: Long, val tryIds: List<Long>, val caseIds: List<Long>)

    private suspend fun seed(): Seed {
        val user = oauthUserService.upsert(OAuthIdentity("github", "1386", "trend", "trend", null, "trend1386@example.com"))
        val userId = user.userId.toLong()
        val now = Instant.parse("2026-10-01T00:30:00Z")
        val project = projectRepository.save(ProjectEntity(name = "trend-p", genre = "ACTION", createdAt = now, updatedAt = now))
        projectMemberRepository.save(ProjectMemberEntity(projectId = project.id!!, appUserId = userId, role = ProjectRole.OWNER.name, createdAt = now))
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(projectId = project.id, name = "inst", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)
        )
        val caseIds = (1..3).map { n ->
            val id = database.sql(
                "INSERT INTO test_case (project_id, scene, step, expected_value) VALUES (:p, 'S', :step, 'ok') RETURNING id"
            ).bind("p", project.id).bind("step", "step $n").map { row -> (row.get("id") as Number).toLong() }.one().awaitFirstOrNull()!!
            id  // 번호(test_case_identifier)는 트리거가 붙인다
        }
        val testRun = testRunRepository.save(TestRunEntity(projectId = project.id, name = "런"))
        val first = testScenarioRepository.save(TestScenarioEntity(projectId = project.id))
        val second = testScenarioRepository.save(TestScenarioEntity(projectId = project.id))
        val steps = caseIds.joinToString(",", "[", "]") { """{"action":"a","case_id":$it}""" }
        database.sql("UPDATE test_scenario SET steps = CAST(:steps AS jsonb) WHERE id = :id")
            .bind("steps", steps).bind("id", second.id!!).fetch().rowsUpdated().awaitFirstOrNull()
        val started = persistence.createRunStarting(testRun.id!!, instance.id!!, userId, listOf(first.id!!, second.id!!), QaRunGates())
        database.sql("UPDATE qa_run SET started_at = :at WHERE id = :id")
            .bind("at", now).bind("id", started.qaRun.id!!).fetch().rowsUpdated().awaitFirstOrNull()
        return Seed(project.id, userId, started.qaRun.id!!, started.tries.map { it.id!! }, caseIds)
    }

    private suspend fun verdict(qaTryId: Long, caseId: Long, status: String, at: String) {
        database.sql(
            "INSERT INTO qa_log (qa_try_id, direction, type, message, payload, created_at) " +
                "VALUES (:tryId, 'ORCHE_INTERNAL', 'CASE_VERDICT', :message, CAST(:payload AS jsonb), :at)"
        )
            .bind("tryId", qaTryId)
            .bind("message", "TC → $status")
            .bind("payload", """{"case_id":$caseId,"verification_status":"$status"}""")
            .bind("at", Instant.parse(at))
            .fetch().rowsUpdated().awaitFirstOrNull()
    }
}
