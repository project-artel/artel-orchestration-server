package kr.artel.orchestration.issue

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.issue.service.IssueService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * issue 도메인 통합 테스트. payload(JSONB) 때문에 실제 PostgreSQL을 쓴다.
 *
 * 두 방향을 함께 본다:
 * - 저장 — QA 인바운드 라우터([QaAgentInboundRouter])가 Agent 프레임을 받아 남기는 경로.
 * - 조회·해결 — 사람이 화면에서 부르는 [IssueService] 경로(ARTEL-245). 컨트롤러는 서비스에
 *   위임만 하므로 서비스 수준에서 검증한다.
 *
 * 검증: ISSUE 프레임 저장(severity/title/detail), 재전송 멱등, 잘못된 severity는 저장 안 함,
 * 실행·프로젝트 단위 목록과 필터·커서, 해결/되돌리기의 멱등, 비참여자 차단.
 */
@ActiveProfiles("test")
@SpringBootTest
class IssueIntegrationTest {

    @Autowired private lateinit var inboundRouter: QaAgentInboundRouter
    @Autowired private lateinit var issueRepository: IssueRepository
    @Autowired private lateinit var issueService: IssueService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    /**
     * 리액티브 트랜잭션은 테스트 롤백이 안 되고 실 DB를 공유하므로 FK 순서대로 직접 비운다.
     * 테스트 후에도 반드시 비운다 — qa_try는 game_instance/test_scenario를 하드 FK로 참조하므로,
     * 남겨두면 이 도메인을 모르는 다른 테스트의 game_instance/project 삭제가 FK로 깨진다.
     */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        issueRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `persists an agent-reported issue with severity, title and full payload as detail`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val qaTryId = seedRunningQaTry(owner)

        deliver(
            qaTryId,
            messageId = UUID.randomUUID().toString(),
            severity = "MAJOR",
            title = "Save button does nothing on the pause menu",
            extra = """"detail":"Clicking Save shows no feedback and no file is written","step":3"""
        )

        val issues = issueRepository.findAll().toList()
        assertThat(issues).hasSize(1)
        val issue = issues.single()
        assertThat(issue.severity).isEqualTo("MAJOR")
        assertThat(issue.title).isEqualTo("Save button does nothing on the pause menu")
        val detail = objectMapper.readTree(issue.detail.asString())
        assertThat(detail["step"].asInt()).isEqualTo(3)
        assertThat(detail["detail"].asText()).contains("no file is written")
        // reported_at은 서버 수신 시각이 아니라 Agent가 프레임에 찍은 이벤트 시각을 그대로 보존한다.
        assertThat(issue.reportedAt).isEqualTo(REPORTED_AT)
    }

    @Test
    fun `is idempotent on message id so a re-delivered frame does not duplicate`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val qaTryId = seedRunningQaTry(owner)
        val messageId = UUID.randomUUID().toString()

        deliver(qaTryId, messageId, "CRITICAL", "Crash on level load")
        deliver(qaTryId, messageId, "CRITICAL", "Crash on level load")

        assertThat(issueRepository.findAll().toList()).hasSize(1)
    }

    @Test
    fun `drops a frame whose severity is not on the ladder without persisting`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val qaTryId = seedRunningQaTry(owner)

        deliver(qaTryId, UUID.randomUUID().toString(), severity = "SEVERE", title = "Unknown severity")

        assertThat(issueRepository.findAll().toList()).isEmpty()
    }

    // --- 조회·해결(ARTEL-245) ---

    @Test
    fun `pages a run's issues newest first`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seedProject(owner)
        seedIssue(seed.qaTryId, "MINOR", "oldest")
        val second = seedIssue(seed.qaTryId, "MAJOR", "middle")
        seedIssue(seed.qaTryId, "BLOCKER", "newest")

        val page = issueService.listByQaTry(seed.qaTryId, seed.ownerId, null, 2)

        assertThat(page.items.map { it.title }).containsExactly("newest", "middle")
        assertThat(page.hasMore).isTrue()
        assertThat(page.nextBeforeId).isEqualTo(second.toString())

        val next = issueService.listByQaTry(seed.qaTryId, seed.ownerId, second, 2)
        assertThat(next.items.map { it.title }).containsExactly("oldest")
        assertThat(next.hasMore).isFalse()
        assertThat(next.nextBeforeId).isNull()
    }

    @Test
    fun `gathers a project's issues across runs and narrows them by severity and status`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seedProject(owner)
        val otherRun = seedQaTry(seed)
        seedIssue(seed.qaTryId, "MAJOR", "from the first run")
        val blocker = seedIssue(otherRun, "BLOCKER", "from the second run")

        val all = issueService.listByProject(seed.projectId, seed.ownerId, null, null, null, 50)
        assertThat(all.items.map { it.title })
            .containsExactlyInAnyOrder("from the first run", "from the second run")

        val bySeverity = issueService.listByProject(seed.projectId, seed.ownerId, null, "BLOCKER", null, 50)
        assertThat(bySeverity.items.map { it.title }).containsExactly("from the second run")

        issueService.resolve(blocker, seed.ownerId)
        val open = issueService.listByProject(seed.projectId, seed.ownerId, "OPEN", null, null, 50)
        assertThat(open.items.map { it.title }).containsExactly("from the first run")
    }

    @Test
    fun `resolving records who and when, and asking twice does not move the timestamp`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seedProject(owner)
        val issueId = seedIssue(seed.qaTryId, "CRITICAL", "Crash on level load")

        issueService.resolve(issueId, seed.ownerId)
        val resolved = issueRepository.findById(issueId)!!
        assertThat(resolved.status).isEqualTo("RESOLVED")
        assertThat(resolved.resolvedBy).isEqualTo(seed.ownerId)
        assertThat(resolved.resolvedAt).isNotNull()

        issueService.resolve(issueId, seed.ownerId)
        assertThat(issueRepository.findById(issueId)!!.resolvedAt).isEqualTo(resolved.resolvedAt)
    }

    @Test
    fun `reopening clears the resolution and is idempotent too`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seedProject(owner)
        val issueId = seedIssue(seed.qaTryId, "MINOR", "Typo on the title screen")

        issueService.resolve(issueId, seed.ownerId)
        issueService.reopen(issueId, seed.ownerId)
        issueService.reopen(issueId, seed.ownerId)

        val reopened = issueRepository.findById(issueId)!!
        assertThat(reopened.status).isEqualTo("OPEN")
        assertThat(reopened.resolvedAt).isNull()
        assertThat(reopened.resolvedBy).isNull()
    }

    @Test
    fun `hides every issue path from someone outside the project`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val stranger = signIn("43", "hubot")
        val seed = seedProject(owner)
        val issueId = seedIssue(seed.qaTryId, "MAJOR", "Not yours")
        val strangerId = stranger.userId.toLong()

        assertThatThrownBy {
            runBlocking { issueService.listByProject(seed.projectId, strangerId, null, null, null, 50) }
        }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy {
            runBlocking { issueService.listByQaTry(seed.qaTryId, strangerId, null, 50) }
        }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy {
            runBlocking { issueService.resolve(issueId, strangerId) }
        }.isInstanceOf(NotFoundException::class.java)

        // 거부는 흔적을 남기지 않는다: 남의 이슈 상태가 그대로여야 한다.
        assertThat(issueRepository.findById(issueId)!!.status).isEqualTo("OPEN")
    }

    @Test
    fun `rejects a filter value that is not on the ladder`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seedProject(owner)

        assertThatThrownBy {
            runBlocking { issueService.listByProject(seed.projectId, seed.ownerId, "DONE", null, null, 50) }
        }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy {
            runBlocking { issueService.listByProject(seed.projectId, seed.ownerId, null, "SEVERE", null, 50) }
        }.isInstanceOf(BadRequestException::class.java)
    }

    // --- helpers ---

    private suspend fun deliver(
        qaTryId: Long,
        messageId: String,
        severity: String,
        title: String,
        extra: String? = null
    ) {
        val payload = buildString {
            append("""{"severity":"$severity","title":${objectMapper.writeValueAsString(title)}""")
            if (extra != null) append(",$extra")
            append("}")
        }
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = messageId,
                type = "ISSUE",
                qaTryId = qaTryId.toString(),
                correlationId = UUID.randomUUID().toString(),
                timestamp = REPORTED_AT,
                payload = objectMapper.readTree(payload)
            )
        )
    }

    private suspend fun seedRunningQaTry(owner: AuthenticatedUser): Long = seedProject(owner).qaTryId

    /** 프로젝트 + 시나리오 + 인스턴스 + 실행 하나. 조회 테스트는 프로젝트 id까지 필요하다. */
    private suspend fun seedProject(owner: AuthenticatedUser): Seed {
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "issue-project", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = project.id!!, payload = Json.of("{}"))
        )!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        val seed = Seed(
            ownerId = ownerId,
            projectId = project.id!!,
            testScenarioId = scenario.id!!,
            gameInstanceId = instance.id!!,
            qaTryId = 0
        )
        return seed.copy(qaTryId = seedQaTry(seed, status = "RUNNING"))
    }

    /**
     * 같은 프로젝트에 실행을 하나 더. 프로젝트 단위 목록이 여러 실행을 모으는지 보려면 필요하다.
     *
     * 기본이 COMPLETED인 것은 `uk_qa_try_active_instance` 때문이다 — 한 인스턴스에 진행 중인
     * 실행은 하나뿐이다. 프로젝트에 쌓인 이슈는 대부분 끝난 실행의 것이므로 실제와도 맞는다.
     */
    private suspend fun seedQaTry(seed: Seed, status: String = "COMPLETED"): Long {
        val now = Instant.now()
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = seed.testScenarioId,
                gameInstanceId = seed.gameInstanceId,
                startedBy = seed.ownerId,
                status = status,
                startedAt = now,
                completedAt = if (status == "RUNNING") null else now
            )
        )!!.id!!
    }

    /**
     * 이슈를 직접 넣는다. 라우터를 거치면 severity·순서를 이 테스트가 원하는 대로 못 잡는다.
     * 저장 경로 자체는 위쪽 세 테스트가 이미 덮는다.
     */
    private suspend fun seedIssue(qaTryId: Long, severity: String, title: String): Long =
        issueRepository.save(
            IssueEntity(
                qaTryId = qaTryId,
                messageId = UUID.randomUUID().toString(),
                severity = severity,
                title = title,
                detail = Json.of("{}"),
                reportedAt = REPORTED_AT,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )!!.id!!

    private data class Seed(
        val ownerId: Long,
        val projectId: Long,
        val testScenarioId: Long,
        val gameInstanceId: Long,
        val qaTryId: Long
    )

    private suspend fun signIn(providerUserId: String, login: String): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        )!!

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)

        // Agent가 프레임에 찍는 이벤트 시각. PostgreSQL timestamptz(마이크로초)와 정확히 비교되도록
        // 마이크로초 정밀도로 고정한다.
        val REPORTED_AT: Instant = Instant.parse("2026-07-28T12:34:56.123456Z")
    }
}
