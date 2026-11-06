package kr.artel.orchestration.tracker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.issue.service.IssueService
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.tracker.client.IssueTrackerClientRegistry
import kr.artel.orchestration.tracker.entity.ProjectTrackerLinkEntity
import kr.artel.orchestration.tracker.entity.TrackerProvider
import kr.artel.orchestration.tracker.entity.TrackerSyncState
import kr.artel.orchestration.tracker.repository.IssueTrackerLinkRepository
import kr.artel.orchestration.tracker.repository.ProjectTrackerLinkRepository
import kr.artel.orchestration.tracker.service.IssueTrackerSyncService
import kr.artel.orchestration.tracker.service.TrackerNotConnectedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * 내보내기 규칙(ARTEL-671).
 *
 * GitHub 호출은 전부 [FakeIssueTrackerClient] 로 바꾼다 — 실제 GitHub 에 닿는 검증은 사람이 하는
 * 수동 항목이다. 여기서 지키는 것은 넷이다: severity 기준, 멱등 `claim`, 실패 기록, 상태 반영.
 */
@ActiveProfiles("test")
@SpringBootTest
class IssueTrackerSyncTest {

    /**
     * registry 자체를 갈아 끼운다. `@Primary` 를 client 에 붙이는 것으로는 진짜 GitHub 구현체가
     * `List<IssueTrackerClient>` 에 남는다.
     */
    @TestConfiguration
    class FakeTrackerConfig {
        @Bean fun fakeIssueTrackerClient() = FakeIssueTrackerClient()

        @Bean
        @Primary
        fun fakeRegistry(fake: FakeIssueTrackerClient): IssueTrackerClientRegistry = registryOf(fake)
    }

    @Autowired private lateinit var fake: FakeIssueTrackerClient
    @Autowired private lateinit var syncService: IssueTrackerSyncService
    @Autowired private lateinit var issueService: IssueService
    @Autowired private lateinit var issueRepository: IssueRepository
    @Autowired private lateinit var issueLinkRepository: IssueTrackerLinkRepository
    @Autowired private lateinit var projectLinkRepository: ProjectTrackerLinkRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var scope: CoroutineScope

    private lateinit var seeder: TrackerSeeder

    /** 리액티브 트랜잭션은 롤백되지 않고 DB를 공유하므로 FK 순서대로 직접 비운다. */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        fake.reset()
        issueLinkRepository.deleteAll()
        issueRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        scenarioRepository.deleteAll()
        projectLinkRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
        seeder = TrackerSeeder(
            projectRepository, memberRepository, scenarioRepository,
            gameInstanceRepository, qaTryRepository, issueRepository
        )
    }

    @Test
    fun `exports a blocker and leaves a minor alone`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner)
        connect(seed.projectId, owner)

        val blocker = seeder.issue(seed.qaTryId, "BLOCKER", "레벨 진입에서 튕긴다")
        val minor = seeder.issue(seed.qaTryId, "MINOR", "제목이 잘린다")

        syncService.syncAutomatically(blocker)
        syncService.syncAutomatically(minor)

        assertThat(fake.created).hasSize(1)
        assertThat(fake.created.single().draft.title).isEqualTo("[BLOCKER] 레벨 진입에서 튕긴다")
        assertThat(link(blocker)!!.syncState).isEqualTo(TrackerSyncState.SYNCED.name)
        assertThat(link(minor)).isNull()
    }

    @Test
    fun `puts severity expectation actual steps and a link back to the run into the body`(): Unit =
        runBlocking {
            val owner = signIn("1", "octocat")
            val seed = seeder.seed(owner)
            connect(seed.projectId, owner)
            val issueId = seeder.issue(seed.qaTryId, "CRITICAL")

            syncService.syncAutomatically(issueId)

            val body = fake.created.single().draft.body
            assertThat(body).contains("CRITICAL")
            assertThat(body).contains("상점이 열린다")
            assertThat(body).contains("검은 화면")
            assertThat(body).contains("1. 상점 진입")
            // 링크가 틀려도 어느 실행인지 잃지 않도록 id 를 글자로도 싣는다.
            assertThat(body).contains("/projects/${seed.projectId}/qa-tries/${seed.qaTryId}")
            assertThat(body).contains("`qaTry=${seed.qaTryId}`")
        }

    @Test
    fun `two concurrent exports of one defect create exactly one external issue`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner)
        connect(seed.projectId, owner)
        val issueId = seeder.issue(seed.qaTryId, "BLOCKER")

        // 승자를 게이트 안에 붙잡아 둔 채 두 번째 요청을 태워야, 그것이 방금 만들어진 PENDING 을
        // 만나는 분기를 실제로 지난다.
        fake.gated = true
        val winner = async { syncService.syncAutomatically(issueId) }
        withTimeout(10_000) { fake.entered.await() }
        assertThat(link(issueId)!!.syncState).isEqualTo(TrackerSyncState.PENDING.name)

        // 두 번째 요청은 게이트에 들어오지 못하고 claim 실패로 곧장 끝난다.
        syncService.syncAutomatically(issueId)
        assertThat(fake.created).isEmpty()

        fake.release.complete(Unit)
        winner.await()

        assertThat(fake.created).hasSize(1)
        assertThat(link(issueId)!!.syncState).isEqualTo(TrackerSyncState.SYNCED.name)
    }

    @Test
    fun `a resent agent frame does not export twice`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner)
        connect(seed.projectId, owner)
        val messageId = UUID.randomUUID().toString()

        val first = record(seed.qaTryId, messageId)
        val second = record(seed.qaTryId, messageId)

        assertThat(second).isEqualTo(first)
        assertThat(fake.created).hasSize(1)
    }

    @Test
    fun `records the failure and lets a manual retry claim it again`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner)
        connect(seed.projectId, owner)
        val issueId = seeder.issue(seed.qaTryId, "BLOCKER")

        fake.failNextCreate = true
        syncService.syncAutomatically(issueId)

        val failed = link(issueId)!!
        assertThat(failed.syncState).isEqualTo(TrackerSyncState.FAILED.name)
        assertThat(failed.syncError).isNotBlank()

        val retried = syncService.syncManually(issueId, owner)
        assertThat(retried.syncState).isEqualTo(TrackerSyncState.SYNCED.name)
        assertThat(retried.externalKey).isNotNull()
        assertThat(fake.created).hasSize(1)
    }

    @Test
    fun `a manual retry reclaims a pending row that has gone stale`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner)
        connect(seed.projectId, owner)
        val issueId = seeder.issue(seed.qaTryId, "BLOCKER")

        // 내보내는 도중 프로세스가 죽어 굳은 행. 유예가 없으면 아무도 이것을 되살릴 수 없다.
        issueLinkRepository.claim(
            issueId = issueId,
            provider = TrackerProvider.GITHUB.name,
            now = Instant.now().minusSeconds(3_600),
            staleBefore = Instant.now().minusSeconds(3_600)
        )

        val result = syncService.syncManually(issueId, owner)

        assertThat(result.syncState).isEqualTo(TrackerSyncState.SYNCED.name)
        assertThat(fake.created).hasSize(1)
    }

    @Test
    fun `an already synced defect answers with the state it has and creates nothing new`(): Unit =
        runBlocking {
            val owner = signIn("1", "octocat")
            val seed = seeder.seed(owner)
            connect(seed.projectId, owner)
            val issueId = seeder.issue(seed.qaTryId, "BLOCKER")
            syncService.syncAutomatically(issueId)
            val firstKey = link(issueId)!!.externalKey

            val again = syncService.syncManually(issueId, owner)

            assertThat(again.externalKey).isEqualTo(firstKey)
            assertThat(fake.created).hasSize(1)
        }

    @Test
    fun `a manual export without a connection says so instead of answering quietly`(): Unit =
        runBlocking {
            val owner = signIn("1", "octocat")
            val seed = seeder.seed(owner)
            val issueId = seeder.issue(seed.qaTryId, "BLOCKER")

            assertThatThrownBy { runBlocking { syncService.syncManually(issueId, owner) } }
                .isInstanceOf(TrackerNotConnectedException::class.java)
            assertThat(fake.created).isEmpty()
        }

    @Test
    fun `resolve closes the external issue and reopen opens it again`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner)
        connect(seed.projectId, owner)
        val issueId = seeder.issue(seed.qaTryId, "BLOCKER")
        syncService.syncAutomatically(issueId)
        val externalKey = link(issueId)!!.externalKey

        issueService.resolve(issueId, owner)
        drainAutoSync()
        assertThat(fake.closed).containsExactly(externalKey)

        issueService.reopen(issueId, owner)
        drainAutoSync()
        assertThat(fake.reopened).containsExactly(externalKey)

        // 이미 그 상태인 이슈에 다시 요청해도 저쪽을 건드리지 않는다.
        issueService.reopen(issueId, owner)
        drainAutoSync()
        assertThat(fake.reopened).hasSize(1)
    }

    @Test
    fun `resolving a defect with no external issue touches nothing`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner)
        val issueId = seeder.issue(seed.qaTryId, "BLOCKER")

        issueService.resolve(issueId, owner)
        drainAutoSync()

        assertThat(fake.closed).isEmpty()
        assertThat(link(issueId)).isNull()
    }

    @Test
    fun `resolving while the export is still pending closes nothing`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner)
        connect(seed.projectId, owner)
        val issueId = seeder.issue(seed.qaTryId, "BLOCKER")
        // 아직 내보내는 중이라 external_key 가 없다. 계획이 열어 둔 창이며, 그 창에서 아무것도
        // 하지 않는다는 것이 여기서 고정하는 동작이다.
        issueLinkRepository.claim(
            issueId = issueId,
            provider = TrackerProvider.GITHUB.name,
            now = Instant.now(),
            staleBefore = Instant.now().minusSeconds(3_600)
        )

        issueService.resolve(issueId, owner)
        drainAutoSync()

        assertThat(fake.closed).isEmpty()
        assertThat(link(issueId)!!.syncState).isEqualTo(TrackerSyncState.PENDING.name)
    }

    @Test
    fun `a stranger cannot export someone else's defect`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val stranger = signIn("2", "hubot")
        val seed = seeder.seed(owner)
        connect(seed.projectId, owner)
        val issueId = seeder.issue(seed.qaTryId, "BLOCKER")

        assertThatThrownBy { runBlocking { syncService.syncManually(issueId, stranger) } }
            .isInstanceOf(NotFoundException::class.java)
        assertThat(fake.created).isEmpty()
        assertThat(link(issueId)).isNull()
    }

    @Test
    fun `a defect is still stored and answered when the tracker is down`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner)
        connect(seed.projectId, owner)

        fake.failNextCreate = true
        val issueId = record(seed.qaTryId, UUID.randomUUID().toString())

        // 내보내기가 실패해도 결함은 저장되고 agent 는 id 를 받는다. QA 런은 계속된다.
        assertThat(issueRepository.findById(issueId)).isNotNull()
        assertThat(link(issueId)!!.syncState).isEqualTo(TrackerSyncState.FAILED.name)
    }

    @Test
    fun `an empty severity ladder turns automatic export off`(): Unit = runBlocking {
        val owner = signIn("1", "octocat")
        val seed = seeder.seed(owner)
        connect(seed.projectId, owner, severities = "")
        val issueId = seeder.issue(seed.qaTryId, "BLOCKER")

        syncService.syncAutomatically(issueId)

        assertThat(fake.created).isEmpty()
    }

    // --- helpers ---

    /**
     * `recordAgentIssue` 는 자동 sync 를 별도 scope 에 던지고 기다리지 않는다. 테스트는 그 scope 의
     * 자식들을 `join` 해 결과를 확정한다 — `delay` 로 재우지 않는다(`testing.md`: sleep 금지).
     */
    private suspend fun record(qaTryId: Long, messageId: String): Long {
        val id = issueService.recordAgentIssue(
            qaTryId = qaTryId,
            messageId = messageId,
            correlationId = null,
            severity = "BLOCKER",
            title = "레벨 진입에서 튕긴다",
            reportedAt = Instant.parse("2026-08-28T01:02:03Z"),
            payload = objectMapper.readTree("""{"expected":"연다","actual":"안 연다"}""")
        )
        drainAutoSync()
        return id
    }

    /** 주입받은 scope 에 떠 있는 자동 sync 를 모두 끝낸다. */
    private suspend fun drainAutoSync() {
        withTimeout(10_000) {
            scope.coroutineContext.job.children.toList().forEach { it.join() }
        }
    }

    private suspend fun link(issueId: Long) =
        issueLinkRepository.findByIssueIdAndProvider(issueId, TrackerProvider.GITHUB.name)

    private suspend fun connect(
        projectId: Long,
        userId: Long,
        severities: String = ProjectTrackerLinkEntity.DEFAULT_AUTO_SYNC_SEVERITIES
    ) {
        projectLinkRepository.save(
            ProjectTrackerLinkEntity(
                projectId = projectId,
                provider = TrackerProvider.GITHUB.name,
                externalWorkspace = "artel",
                externalRepository = "game",
                installationRef = "4242",
                autoSyncSeverities = severities,
                connectedBy = userId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )
    }

    private suspend fun signIn(providerUserId: String, login: String): Long =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        ).userId.toLong()
}
