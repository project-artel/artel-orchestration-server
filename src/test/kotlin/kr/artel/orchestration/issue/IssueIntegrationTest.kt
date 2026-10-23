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
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.issue.folder.IssueFolderCloneRequest
import kr.artel.orchestration.issue.folder.IssueFolderItemsRequest
import kr.artel.orchestration.issue.folder.IssueFolderRequest
import kr.artel.orchestration.issue.folder.IssueFolderService
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.issue.service.IssueService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
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
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var gameBuildRepository: GameBuildRepository
    @Autowired private lateinit var issueFolderService: IssueFolderService
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
        qaRunRepository.deleteAll()
        testRunRepository.deleteAll()
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

    /**
     * `qa_run_id` 는 `IssueEntity` 에 없다 — 남긴 try 를 거쳐야 안다(ARTEL-722). run 에 속한 try 와
     * `qa_run` 이 생기기 전의 단독 실행 try 를 나란히 세워, 배치 조회가 각자에게 맞는 값을 준다.
     */
    @Test
    fun `qaRunId는 이슈를 남긴 try 를 거쳐 문자열로 실리고 단독 실행은 null이다`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seedProject(owner)
        val qaRunId = seedQaRun(seed)
        val runTryId = seedQaTry(seed, status = "COMPLETED", qaRunId = qaRunId)
        val runIssueId = seedIssue(runTryId, "MAJOR", "run 소속 실행이 남긴 이슈")
        val standaloneIssueId = seedIssue(seed.qaTryId, "MAJOR", "단독 실행이 남긴 이슈")

        val page = issueService.listByProject(seed.projectId, seed.ownerId, null, null, null, 50)
        val byId = page.items.associateBy { it.id }

        assertThat(byId.getValue(runIssueId.toString()).qaRunId).isEqualTo(qaRunId.toString())
        assertThat(byId.getValue(standaloneIssueId.toString()).qaRunId).isNull()
    }

    /**
     * 목록 한 줄만 보고도 어느 빌드의 어느 TR · 시나리오에서 나왔는지 알 수 있어야 한다. 빌드는 run 이 고른 것을
     * 먼저 보고 없으면 TR 의 빌드로 본다. 같은 규칙으로 거르고 센다.
     */
    @Test
    fun `이슈는 나온 빌드 · TR · 시나리오를 싣고 그것으로 거르고 센다`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seedProject(owner)
        val now = Instant.now()
        val oldBuild = gameBuildRepository.save(GameBuildEntity(projectId = seed.projectId, version = "1.0", createdAt = now, updatedAt = now)).id!!
        val newBuild = gameBuildRepository.save(GameBuildEntity(projectId = seed.projectId, version = "1.1", label = "hotfix", createdAt = now, updatedAt = now)).id!!
        // TR 은 1.0 에 있지만 run 이 1.1 을 골랐다 — 이슈는 1.1 에서 나온 것이다.
        val trOld = testRunRepository.save(TestRunEntity(projectId = seed.projectId, name = "전투 TR", gameBuildId = oldBuild)).id!!
        val runPicked = seedQaRun(seed, trOld, gameBuildId = newBuild)
        val runInherited = seedQaRun(seed, trOld)
        val fromPicked = seedIssue(seedQaTry(seed, qaRunId = runPicked), "BLOCKER", "1.1 에서 나옴")
        val fromInherited = seedIssue(seedQaTry(seed, qaRunId = runInherited), "MAJOR", "TR 빌드 1.0 에서 나옴")
        val standalone = seedIssue(seed.qaTryId, "MINOR", "run 없이 나옴")

        val byId = issueService.listByProject(seed.projectId, seed.ownerId, null, null, null, 50).items.associateBy { it.id }
        val picked = byId.getValue(fromPicked.toString()).origin!!
        assertThat(picked.buildVersion).isEqualTo("1.1")
        assertThat(picked.buildLabel).isEqualTo("hotfix")
        assertThat(picked.testRunName).isEqualTo("전투 TR")
        assertThat(picked.testScenarioId).isEqualTo(seed.testScenarioId.toString())
        assertThat(byId.getValue(fromInherited.toString()).origin!!.buildVersion).isEqualTo("1.0")
        val alone = byId.getValue(standalone.toString()).origin!!
        assertThat(alone.testRunId).isNull()
        assertThat(alone.gameBuildId).isNull()

        val onNew = issueService.listByProject(seed.projectId, seed.ownerId, null, null, null, 50, gameBuildId = newBuild)
        assertThat(onNew.items.map { it.title }).containsExactly("1.1 에서 나옴")
        val titled = issueService.listByProject(seed.projectId, seed.ownerId, null, null, null, 50, query = "tr 빌드")
        assertThat(titled.items.map { it.title }).containsExactly("TR 빌드 1.0 에서 나옴")
        val noBuild = issueService.listByProject(seed.projectId, seed.ownerId, null, null, null, 50, unversioned = true)
        assertThat(noBuild.items.map { it.title }).containsExactly("run 없이 나옴")
        val onTr = issueService.listByProject(seed.projectId, seed.ownerId, null, null, null, 50, testRunId = trOld)
        assertThat(onTr.items.map { it.title }).containsExactlyInAnyOrder("1.1 에서 나옴", "TR 빌드 1.0 에서 나옴")

        issueService.resolve(fromInherited, seed.ownerId)
        val facets = issueService.facetsByProject(seed.projectId, seed.ownerId, null)
        assertThat(facets.total).isEqualTo(3)
        assertThat(facets.builds.map { Triple(it.version, it.count, it.openCount) })
            .containsExactlyInAnyOrder(Triple("1.1", 1L, 1L), Triple("1.0", 1L, 0L), Triple(null, 1L, 1L))
        assertThat(facets.testRuns.single { it.testRunId == trOld.toString() }.count).isEqualTo(2)
        assertThat(facets.severities).containsEntry("BLOCKER", 1L).containsEntry("MAJOR", 1L).containsEntry("MINOR", 1L)
        assertThat(issueService.facetsByProject(seed.projectId, seed.ownerId, "OPEN").total).isEqualTo(2)
        // 빌드 · TR 을 주면 그 안에서만 센다.
        val onNewFacets = issueService.facetsByProject(seed.projectId, seed.ownerId, null, gameBuildId = newBuild)
        assertThat(onNewFacets.total).isEqualTo(1)
        assertThat(onNewFacets.severities).containsOnlyKeys("BLOCKER")
        val onTrFacets = issueService.facetsByProject(seed.projectId, seed.ownerId, null, testRunId = trOld)
        assertThat(onTrFacets.severities).containsOnlyKeys("BLOCKER", "MAJOR")
    }

    /** 우클릭으로 만든 folder 에 이슈를 담고, 그 folder 로 목록을 거르고, 복제 · 이동 · 삭제한다(ARTEL-1114). */
    @Test
    fun `이슈 folder 에 담고 거르고 복제하고 지운다`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val seed = seedProject(owner)
        val first = seedIssue(seed.qaTryId, "MAJOR", "담을 이슈")
        seedIssue(seed.qaTryId, "MINOR", "안 담을 이슈")
        val combat = issueFolderService.create(seed.projectId, seed.ownerId, IssueFolderRequest(name = "전투"))
        val child = issueFolderService.create(seed.projectId, seed.ownerId, IssueFolderRequest(name = "조합", parentId = combat.id))

        assertThatThrownBy { runBlocking { issueFolderService.create(seed.projectId, seed.ownerId, IssueFolderRequest(name = "전투")) } }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy {
            runBlocking { issueFolderService.update(seed.projectId, seed.ownerId, combat.id.toLong(), IssueFolderRequest(name = "전투", parentId = child.id)) }
        }.isInstanceOf(BadRequestException::class.java)

        val filled = issueFolderService.addItems(seed.projectId, seed.ownerId, child.id.toLong(), IssueFolderItemsRequest(listOf(first.toString())))
        assertThat(filled.issueCount).isEqualTo(1)
        val inFolder = issueService.listByProject(seed.projectId, seed.ownerId, null, null, null, 50, folderId = child.id.toLong())
        assertThat(inFolder.items.map { it.title }).containsExactly("담을 이슈")

        val copy = issueFolderService.clone(seed.projectId, seed.ownerId, combat.id.toLong(), IssueFolderCloneRequest())
        assertThat(copy.name).isEqualTo("전투 사본")
        val all = issueFolderService.list(seed.projectId, seed.ownerId).items
        assertThat(all.map { it.name }).containsExactlyInAnyOrder("전투", "조합", "전투 사본", "조합")
        assertThat(all.filter { it.name == "조합" }.map { it.issueCount }).containsOnly(1)

        issueFolderService.removeItems(seed.projectId, seed.ownerId, child.id.toLong(), IssueFolderItemsRequest(listOf(first.toString())))
        issueFolderService.delete(seed.projectId, seed.ownerId, combat.id.toLong())
        assertThat(issueFolderService.list(seed.projectId, seed.ownerId).items.map { it.name }).containsExactlyInAnyOrder("전투 사본", "조합")
        assertThat(issueRepository.findById(first)).isNotNull()

        val stranger = signIn("43", "stranger")
        assertThatThrownBy { runBlocking { issueFolderService.list(seed.projectId, stranger.userId.toLong()) } }
            .isInstanceOf(NotFoundException::class.java)
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
        )
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = project.id)
        )
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )
        val seed = Seed(
            ownerId = ownerId,
            projectId = project.id,
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
    private suspend fun seedQaTry(seed: Seed, status: String = "COMPLETED", qaRunId: Long? = null): Long {
        val now = Instant.now()
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = seed.testScenarioId,
                gameInstanceId = seed.gameInstanceId,
                qaRunId = qaRunId,
                startedBy = seed.ownerId,
                status = status,
                startedAt = now,
                completedAt = if (status == "RUNNING") null else now
            )
        ).id!!
    }

    /** run 하나(ARTEL-259 최소형). qa_try 를 여기 붙여야 `IssueResponse.qaRunId` 가 문자열을 낸다. */
    private suspend fun seedQaRun(seed: Seed, testRunId: Long? = null, gameBuildId: Long? = null): Long {
        val now = Instant.now()
        val runOf = testRunId ?: testRunRepository.save(TestRunEntity(projectId = seed.projectId, name = "런")).id!!
        return qaRunRepository.save(
            QaRunEntity(
                testRunId = runOf,
                gameBuildId = gameBuildId,
                gameInstanceId = seed.gameInstanceId,
                startedBy = seed.ownerId,
                status = "COMPLETED",
                startedAt = now,
                completedAt = now
            )
        ).id!!
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
        ).id!!

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
        )

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)

        // Agent가 프레임에 찍는 이벤트 시각. PostgreSQL timestamptz(마이크로초)와 정확히 비교되도록
        // 마이크로초 정밀도로 고정한다.
        val REPORTED_AT: Instant = Instant.parse("2026-07-28T12:34:56.123456Z")
    }
}
