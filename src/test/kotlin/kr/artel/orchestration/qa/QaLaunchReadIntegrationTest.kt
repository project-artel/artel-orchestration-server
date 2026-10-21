package kr.artel.orchestration.qa

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaLaunchReadService
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
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
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * QA 실행 화면이 읽는 실행 목록과 "지난 실행 이후"(ARTEL-1083).
 *
 * 실제 PostgreSQL 을 쓴다 — 이 기능은 LATERAL 집계와 시각 비교가 전부라 대역으로는 검증할 것이 남지 않는다.
 *
 * "지난 실행 이후" 의 변경은 기록 시각으로 센다. 그래서 테스트는 마지막 실행을 하루 전에 시작시키고,
 * 그 전에 있던 것과 그 뒤에 생긴 것을 시각으로 갈라 세운다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaLaunchReadIntegrationTest {

    @Autowired private lateinit var service: QaLaunchReadService
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var issueRepository: IssueRepository
    @Autowired private lateinit var gameBuildRepository: GameBuildRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var testRunScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    /** 마지막 실행의 시작. 이것보다 뒤에 기록된 것만 "지난 실행 이후" 다. */
    private val lastStart: Instant = Instant.now().minus(Duration.ofDays(1))
    private val longAgo: Instant = lastStart.minus(Duration.ofDays(7))

    private var projectId: Long = 0
    private var ownerId: Long = 0
    private var strangerId: Long = 0
    private var instanceId: Long = 0
    private var runId: Long = 0
    private var oldScenarioId: Long = 0

    @BeforeEach
    fun seed(): Unit = runBlocking {
        wipe()
        ownerId = signIn("launch-owner", "9301")
        strangerId = signIn("launch-stranger", "9302")
        projectId = projectRepository.save(
            ProjectEntity(name = "launch-project", genre = "RPG", createdAt = longAgo, updatedAt = longAgo)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = ownerId, role = ProjectRole.OWNER.name, createdAt = longAgo)
        )
        instanceId = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId, name = "Dem의 MacBook Pro", platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(), createdAt = longAgo, updatedAt = longAgo,
            )
        ).id!!
        runId = testRunRepository.save(
            TestRunEntity(projectId = projectId, name = "회귀 1차", createdAt = longAgo, updatedAt = longAgo)
        ).id!!
        oldScenarioId = testScenarioRepository.save(TestScenarioEntity(projectId = projectId, title = "맵 배경이 바뀐다")).id!!
        testRunScenarioRepository.save(TestRunScenarioEntity(testRunId = runId, testScenarioId = oldScenarioId, position = 0))
        stamp("test_scenario", oldScenarioId, "updated_at", longAgo)
        stampWhere("test_run_scenario", "test_scenario_id = $oldScenarioId", "created_at", longAgo)
    }

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        issueRepository.deleteAll()
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        testRunScenarioRepository.deleteAll()
        testRunRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        gameBuildRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ 실행 목록

    @Test
    fun `실행 목록은 최신순이고 런 이름 · 모델 · 케이스 합 · 이슈 수를 싣는다`(): Unit = runBlocking {
        val older = newQaRun(longAgo)
        newTry(older, model = "openai/gpt-6-luna", passed = 40, total = 42)
        val newer = newQaRun(lastStart)
        val first = newTry(newer, model = "openai/gpt-6.1-sol", passed = 7, total = 8)
        newTry(newer, model = "openai/gpt-6.1-sol", passed = 3, total = 3)
        newIssue(first, "배경이 안 바뀜")

        val items = service.listRuns(projectId, ownerId, 10).items

        assertThat(items.map { it.id }).containsExactly(newer.toString(), older.toString())
        val top = items.first()
        assertThat(top.testRunName).isEqualTo("회귀 1차")
        assertThat(top.gameInstanceName).isEqualTo("Dem의 MacBook Pro")
        assertThat(top.model).isEqualTo("openai/gpt-6.1-sol")
        assertThat(top.casesPassed).isEqualTo(10)
        assertThat(top.casesTotal).isEqualTo(11)
        assertThat(top.issueCount).isEqualTo(1)
    }

    @Test
    fun `테스트 런을 지목하면 그 런의 실행만 고른다`(): Unit = runBlocking {
        val mine = newQaRun(lastStart)
        val otherRunId = testRunRepository.save(
            TestRunEntity(projectId = projectId, name = "회귀 2차", createdAt = longAgo, updatedAt = longAgo)
        ).id!!
        val other = qaRunRepository.save(
            QaRunEntity(
                testRunId = otherRunId, gameInstanceId = instanceId, startedBy = ownerId, status = "COMPLETED",
                startedAt = lastStart, completedAt = lastStart, createdAt = lastStart, updatedAt = lastStart,
            )
        ).id!!

        assertThat(service.listRuns(projectId, ownerId, 10, runId).items.map { it.id }).containsExactly(mine.toString())
        assertThat(service.listRuns(projectId, ownerId, 10, otherRunId).items.map { it.id }).containsExactly(other.toString())
        assertThat(service.listRuns(projectId, ownerId, 10).items).hasSize(2)
    }

    @Test
    fun `판정이 없는 실행은 케이스 수가 0 이 아니라 없다`(): Unit = runBlocking {
        val run = newQaRun(lastStart, status = "CANCELLED")
        newTry(run, model = "openai/gpt-6.1-sol", passed = null, total = null, status = "CANCELLED")

        val top = service.listRuns(projectId, ownerId, 10).items.single()

        assertThat(top.casesPassed).isNull()
        assertThat(top.casesTotal).isNull()
    }

    @Test
    fun `프로젝트에 참여하지 않았으면 목록도 지난 실행 이후도 404 다`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.listRuns(projectId, strangerId, 10) } }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { runBlocking { service.sinceLastQa(projectId, runId, strangerId) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    // ------------------------------------------------------------------ 지난 실행 이후

    @Test
    fun `실행한 적이 없으면 마지막 실행이 없고 목록은 비어 있다`(): Unit = runBlocking {
        val since = service.sinceLastQa(projectId, runId, ownerId)

        assertThat(since.lastQaRun).isNull()
        assertThat(since.issues).isEmpty()
        assertThat(since.changes).isEmpty()
    }

    @Test
    fun `이슈는 마지막 실행의 것만 싣는다`(): Unit = runBlocking {
        val older = newQaRun(longAgo)
        newIssue(newTry(older, model = "m", passed = 1, total = 2), "예전 이슈")
        val last = newQaRun(lastStart)
        val lastTry = newTry(last, model = "m", passed = 3, total = 4)
        newIssue(lastTry, "스테이지 2 배경이 안 바뀜", step = "7")

        val since = service.sinceLastQa(projectId, runId, ownerId)

        assertThat(since.lastQaRun?.id).isEqualTo(last.toString())
        assertThat(since.issues.map { it.title }).containsExactly("스테이지 2 배경이 안 바뀜")
        assertThat(since.issues.single().step).isEqualTo(7)
        assertThat(since.issues.single().scenarioTitle).isEqualTo("맵 배경이 바뀐다")
    }

    @Test
    fun `마지막 실행 뒤로 기록된 것만 변경으로 모은다`(): Unit = runBlocking {
        val last = newQaRun(lastStart)
        val issueId = newIssue(newTry(last, model = "m", passed = 3, total = 4), "배경이 안 바뀜")
        // 뒤에 생긴 것들.
        issueRepository.findById(issueId)!!.let {
            issueRepository.save(it.copy(status = "RESOLVED", resolvedAt = Instant.now(), resolvedBy = ownerId))
        }
        gameBuildRepository.save(GameBuildEntity(projectId = projectId, version = "1.1-e2e", createdAt = Instant.now(), updatedAt = Instant.now()))
        val added = testScenarioRepository.save(TestScenarioEntity(projectId = projectId, title = "엔딩까지 한 번에")).id!!
        testRunScenarioRepository.save(TestRunScenarioEntity(testRunId = runId, testScenarioId = added, position = 1))
        stamp("test_scenario", oldScenarioId, "updated_at", Instant.now())
        // 앞에 있던 것 — 나오면 안 된다.
        val oldBuild = gameBuildRepository.save(GameBuildEntity(projectId = projectId, version = "1.0", createdAt = longAgo, updatedAt = longAgo)).id!!
        stamp("game_build", oldBuild, "created_at", longAgo)

        val kinds = service.sinceLastQa(projectId, runId, ownerId).changes.map { it.kind to it.subjectName }

        assertThat(kinds).containsExactlyInAnyOrder(
            "ISSUE_RESOLVED" to "배경이 안 바뀜",
            "BUILD_REPORTED" to "1.1-e2e",
            "SCENARIO_ADDED" to "엔딩까지 한 번에",
            "SCENARIO_UPDATED" to "맵 배경이 바뀐다",
        )
    }

    @Test
    fun `다른 프로젝트의 런을 지목하면 404 다`(): Unit = runBlocking {
        val otherProject = projectRepository.save(
            ProjectEntity(name = "other", genre = "RPG", createdAt = longAgo, updatedAt = longAgo)
        ).id!!
        val otherRun = testRunRepository.save(
            TestRunEntity(projectId = otherProject, name = "남의 런", createdAt = longAgo, updatedAt = longAgo)
        ).id!!

        assertThatThrownBy { runBlocking { service.sinceLastQa(projectId, otherRun, ownerId) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    // ------------------------------------------------------------------ seed

    private suspend fun newQaRun(startedAt: Instant, status: String = "COMPLETED"): Long =
        qaRunRepository.save(
            QaRunEntity(
                testRunId = runId,
                gameInstanceId = instanceId,
                startedBy = ownerId,
                status = status,
                startedAt = startedAt,
                completedAt = startedAt.plus(Duration.ofMinutes(10)),
                createdAt = startedAt,
                updatedAt = startedAt,
            )
        ).id!!

    private suspend fun newTry(qaRunId: Long, model: String, passed: Int?, total: Int?, status: String = "COMPLETED"): Long {
        val run = qaRunRepository.findById(qaRunId)!!
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = oldScenarioId,
                gameInstanceId = instanceId,
                qaRunId = qaRunId,
                startedBy = ownerId,
                status = status,
                model = model,
                casesPassed = passed,
                casesTotal = total,
                startedAt = run.startedAt,
                completedAt = run.startedAt.plus(Duration.ofMinutes(5)),
            )
        ).id!!
    }

    private suspend fun newIssue(qaTryId: Long, title: String, step: String? = null): Long =
        issueRepository.save(
            IssueEntity(
                qaTryId = qaTryId,
                severity = "MAJOR",
                title = title,
                detail = Json.of(if (step == null) "{}" else """{"step": $step}"""),
                reportedAt = qaTryRepository.findById(qaTryId)!!.startedAt,
            )
        ).id!!

    private suspend fun stamp(table: String, id: Long, column: String, at: Instant) =
        stampWhere(table, "id = $id", column, at)

    private suspend fun stampWhere(table: String, where: String, column: String, at: Instant) {
        databaseClient.sql("UPDATE $table SET $column = :at WHERE $where")
            .bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun signIn(login: String, providerUserId: String): Long =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = providerUserId, login = login,
                displayName = login, avatarUrl = null, email = "$login@example.com",
            )
        ).userId.toLong()
}
