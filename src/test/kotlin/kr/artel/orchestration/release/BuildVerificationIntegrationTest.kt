package kr.artel.orchestration.release

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
import kr.artel.orchestration.project.service.ProjectAccessDeniedException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.qa.service.QaRunGates
import kr.artel.orchestration.qa.service.QaTryPersistenceService
import kr.artel.orchestration.release.dto.ReleaseCriteria
import kr.artel.orchestration.release.repository.BuildVerificationRepository
import kr.artel.orchestration.release.service.BuildVerificationService
import kr.artel.orchestration.release.service.ReleaseCriteriaService
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 이번 빌드 검증 진행과 출시 기준(ARTEL-1386). 판정은 실제 실행이 남기는 것과 같은 `qa_case_attempt` 행을 시각을 정해 넣는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BuildVerificationIntegrationTest {

    @Autowired private lateinit var repository: BuildVerificationRepository
    @Autowired private lateinit var access: ProjectAccessService
    @Autowired private lateinit var criteria: ReleaseCriteriaService
    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var database: DatabaseClient
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    /** 10/12 12:00 KST. */
    private val service by lazy { BuildVerificationService(repository, access, Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneOffset.UTC)) }

    @BeforeEach
    fun before(): Unit = runBlocking { wipe() }

    @AfterEach
    fun after(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        for (table in listOf(
            "project_release_criteria", "issue", "qa_verdict_review", "qa_case_attempt", "qa_log", "qa_try", "qa_run",
            "test_case_version", "test_case_identifier", "test_case", "game_build", "game_instance", "test_run",
            "test_scenario", "project_member", "project", "oauth_identity", "app_user"
        )) {
            database.sql("DELETE FROM $table").fetch().rowsUpdated().awaitFirstOrNull()
        }
    }

    @Test
    fun `TC 마다 마지막 판정으로 세고 날마다 누적한다`(): Unit = runBlocking {
        val seed = seed()
        // TC1: 앞 버전 통과 → 이번 실패(회귀). TC2: 통과 뒤 판정 못함(통과 그대로). TC3: 판정 못함만. TC4: 앞 버전 통과를 이어받음.
        attempt(seed, seed.caseIds[0], "FAILED", "2026-10-10T01:00:00Z")
        attempt(seed, seed.caseIds[1], "PASSED", "2026-10-10T01:05:00Z")
        attempt(seed, seed.caseIds[1], "INCONCLUSIVE", "2026-10-11T01:00:00Z")
        attempt(seed, seed.caseIds[2], "INCONCLUSIVE", "2026-10-11T01:05:00Z")

        val progress = service.progress(seed.projectId, seed.userId, seed.buildId, "Asia/Seoul")

        assertThat(progress.previousBuild!!.version).isEqualTo("1.0.0")
        assertThat(progress.days.map { it.date }).containsExactly("2026-10-10", "2026-10-11", "2026-10-12")
        val first = progress.days[0]
        assertThat(listOf(first.passed, first.failed, first.regression, first.inconclusive, first.runs)).containsExactly(2, 0, 1, 0, 1)
        val second = progress.days[1]
        assertThat(listOf(second.passed, second.regression, second.inconclusive)).containsExactly(2, 1, 1)
        val summary = progress.summary
        assertThat(listOf(summary.total, summary.passed, summary.failed, summary.regression, summary.inconclusive, summary.pending, summary.carried))
            .containsExactly(4, 2, 0, 1, 1, 0, 1)
        assertThat(summary.lastJudgedAt).isEqualTo(Instant.parse("2026-10-11T01:05:00Z"))
    }

    @Test
    fun `사람이 바꾼 판정을 쓰고 멈춘 시도와 버린 런은 뺀다`(): Unit = runBlocking {
        val seed = seed()
        val logId = attempt(seed, seed.caseIds[0], "FAILED", "2026-10-10T01:00:00Z")
        database.sql("INSERT INTO qa_verdict_review (qa_log_id, qa_try_id, test_case_id, flagged, decision, decided_at) VALUES (:log, :try, :case, false, 'PASSED', now())")
            .bind("log", logId).bind("try", seed.tryId).bind("case", seed.caseIds[0]).fetch().rowsUpdated().awaitFirstOrNull()
        attempt(seed, seed.caseIds[1], "INTERRUPTED", "2026-10-10T01:05:00Z")
        val other = run(seed, discarded = true)
        attempt(seed.copy(tryId = other), seed.caseIds[2], "PASSED", "2026-10-10T02:00:00Z")

        val summary = service.progress(seed.projectId, seed.userId, seed.buildId, "Asia/Seoul").summary

        assertThat(listOf(summary.passed, summary.regression, summary.inconclusive, summary.pending)).containsExactly(2, 0, 0, 2)
    }

    @Test
    fun `안 닫힌 BLOCKER · CRITICAL 이슈만 센다`(): Unit = runBlocking {
        val seed = seed()
        issue(seed, "BLOCKER", resolved = false)
        issue(seed, "CRITICAL", resolved = false)
        issue(seed, "CRITICAL", resolved = true)
        issue(seed, "MAJOR", resolved = false)

        assertThat(service.progress(seed.projectId, seed.userId, seed.buildId, "Asia/Seoul").summary.openBlockers).isEqualTo(2)
    }

    @Test
    fun `빌드 요약은 최근에 올라온 것부터고 남의 프로젝트는 404 다`(): Unit = runBlocking {
        val seed = seed()

        val items = service.summaries(seed.projectId, seed.userId, 5).items
        assertThat(items.map { it.build.version }).containsExactly("1.1.0", "1.0.0")
        // 1.0.0 은 시도 없이 버전 행에만 판정이 있다(직접 고침) — 그것도 판정으로 센다.
        assertThat(items[1].passed).isEqualTo(2)

        val stranger = user("stranger")
        assertThatThrownBy { runBlocking { service.summaries(seed.projectId, stranger, 5) } }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { runBlocking { service.progress(seed.projectId, seed.userId, seed.buildId + 999, "Asia/Seoul") } }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { runBlocking { service.summaries(seed.projectId, seed.userId, 0) } }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `출시 기준은 기본값에서 시작하고 OWNER 만 바꾸고 되돌린다`(): Unit = runBlocking {
        val seed = seed()
        val member = user("member")
        projectMemberRepository.save(ProjectMemberEntity(projectId = seed.projectId, appUserId = member, role = ProjectRole.MEMBER.name, createdAt = Instant.now()))
        val looser = ReleaseCriteria(maxPending = 3, maxRegression = 1, minPassRate = 90, maxOpenBlockers = 0, maxInconclusiveRate = 10)

        assertThat(criteria.get(seed.projectId, member).isDefault).isTrue()
        val saved = criteria.update(seed.projectId, seed.userId, looser)
        assertThat(saved.criteria).isEqualTo(looser)
        assertThat(saved.isDefault).isFalse()
        assertThat(criteria.get(seed.projectId, member).criteria).isEqualTo(looser)

        assertThatThrownBy { runBlocking { criteria.update(seed.projectId, member, looser) } }.isInstanceOf(ProjectAccessDeniedException::class.java)
        assertThatThrownBy { runBlocking { criteria.update(seed.projectId, seed.userId, looser.copy(minPassRate = 101)) } }
            .isInstanceOf(BadRequestException::class.java)

        val reset = criteria.reset(seed.projectId, seed.userId)
        assertThat(reset.isDefault).isTrue()
        assertThat(reset.criteria).isEqualTo(ReleaseCriteria.DEFAULT)
    }

    // ----------------------------------------------------------------- helpers

    private data class Seed(
        val projectId: Long, val userId: Long, val instanceId: Long, val testRunId: Long, val scenarioId: Long,
        val buildId: Long, val tryId: Long, val caseIds: List<Long>
    )

    private suspend fun user(name: String): Long =
        oauthUserService.upsert(OAuthIdentity("github", "1386-$name", name, name, null, "$name-1386@example.com")).userId.toLong()

    /** 1.0.0(10/1)에서 TC1 · TC4 통과, 1.1.0(10/10 09:00 KST)에 TC 넷 — TC4 는 1.0.0 판정을 이어받았다. */
    private suspend fun seed(): Seed {
        val userId = user("owner")
        val now = Instant.parse("2026-10-01T00:00:00Z")
        val project = projectRepository.save(ProjectEntity(name = "release-p", genre = "ACTION", createdAt = now, updatedAt = now))
        projectMemberRepository.save(ProjectMemberEntity(projectId = project.id!!, appUserId = userId, role = ProjectRole.OWNER.name, createdAt = now))
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(projectId = project.id, name = "inst", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)
        )
        val older = insertId("INSERT INTO game_build (project_id, version, created_at) VALUES (${project.id}, '1.0.0', '2026-10-01T00:00:00Z') RETURNING id")
        val build = insertId("INSERT INTO game_build (project_id, version, created_at) VALUES (${project.id}, '1.1.0', '2026-10-10T00:00:00Z') RETURNING id")
        val caseIds = (1..4).map { n ->
            insertId("INSERT INTO test_case (project_id, scene, step, expected_value) VALUES (${project.id}, 'S', 'step $n', 'ok') RETURNING id")
        }
        version(caseIds[0], older, "VERIFIED", null)
        version(caseIds[3], older, "VERIFIED", null)
        caseIds.forEachIndexed { index, id -> version(id, build, if (index == 3) "VERIFIED" else "DRAFT", if (index == 3) older else null) }
        val testRun = testRunRepository.save(TestRunEntity(projectId = project.id, name = "런"))
        val scenario = testScenarioRepository.save(TestScenarioEntity(projectId = project.id))
        val seed = Seed(project.id, userId, instance.id!!, testRun.id!!, scenario.id!!, build, 0, caseIds)
        return seed.copy(tryId = run(seed, discarded = false))
    }

    /** 이 빌드에서 QA 실행 하나를 연다. 시도는 끝낸 것으로 둔다 — 같은 인스턴스로 다음 실행을 열 수 있게. */
    private suspend fun run(seed: Seed, discarded: Boolean): Long {
        val started = persistence.createRunStarting(seed.testRunId, seed.instanceId, seed.userId, listOf(seed.scenarioId), QaRunGates())
        val runId = started.qaRun.id!!
        database.sql("UPDATE qa_run SET started_at = '2026-10-10T00:30:00Z', status = 'COMPLETED', completed_at = '2026-10-10T00:45:00Z', game_build_id = :build, discarded_at = ${if (discarded) "now()" else "NULL"} WHERE id = :id")
            .bind("build", seed.buildId).bind("id", runId).fetch().rowsUpdated().awaitFirstOrNull()
        val tryId = started.tries.single().id!!
        database.sql("UPDATE qa_try SET status = 'COMPLETED', completed_at = '2026-10-10T00:40:00Z' WHERE id = :id")
            .bind("id", tryId).fetch().rowsUpdated().awaitFirstOrNull()
        return tryId
    }

    private suspend fun version(caseId: Long, buildId: Long, status: String, carriedFrom: Long?) {
        database.sql(
            "INSERT INTO test_case_version (test_case_id, game_build_id, scene, step, expected_value, verification_status, verdict_carried_from_build_id) " +
                "VALUES (:case, :build, 'S', 'step', 'ok', :status, ${carriedFrom ?: "NULL"})"
        ).bind("case", caseId).bind("build", buildId).bind("status", status).fetch().rowsUpdated().awaitFirstOrNull()
    }

    /** 시도 한 번. 검토가 붙을 수 있게 `qa_log` 행도 만들고 그 id 를 돌려준다. */
    private suspend fun attempt(seed: Seed, caseId: Long, verdict: String, at: String): Long {
        val logId = insertId(
            "INSERT INTO qa_log (qa_try_id, direction, type, message, payload, created_at) " +
                "VALUES (${seed.tryId}, 'ORCHE_INTERNAL', 'CASE_VERDICT', 'TC', '{}'::jsonb, '$at') RETURNING id"
        )
        database.sql(
            "INSERT INTO qa_case_attempt (id, qa_try_id, test_case_id, project_id, case_snapshot, started_at, finished_at, qa_log_id, verdict) " +
                "VALUES (:id, :try, :case, :project, '{}'::jsonb, :at, :at, :log, :verdict)"
        ).bind("id", UUID.randomUUID()).bind("try", seed.tryId).bind("case", caseId).bind("project", seed.projectId)
            .bind("at", Instant.parse(at)).bind("log", logId).bind("verdict", verdict)
            .fetch().rowsUpdated().awaitFirstOrNull()
        return logId
    }

    private suspend fun issue(seed: Seed, severity: String, resolved: Boolean) {
        database.sql(
            "INSERT INTO issue (qa_try_id, severity, title, reported_at, project_id, status, resolved_at) " +
                "VALUES (:try, :severity, 'bug', now(), :project, :status, ${if (resolved) "now()" else "NULL"})"
        ).bind("try", seed.tryId).bind("severity", severity).bind("project", seed.projectId)
            .bind("status", if (resolved) "RESOLVED" else "OPEN").fetch().rowsUpdated().awaitFirstOrNull()
    }

    private suspend fun insertId(sql: String): Long =
        database.sql(sql).map { row -> (row.get("id") as Number).toLong() }.one().awaitFirstOrNull()!!
}
