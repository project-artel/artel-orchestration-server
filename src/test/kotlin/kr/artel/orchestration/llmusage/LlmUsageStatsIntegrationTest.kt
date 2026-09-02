package kr.artel.orchestration.llmusage

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.llmusage.entity.LlmUsageEntity
import kr.artel.orchestration.llmusage.repository.LlmUsageRepository
import kr.artel.orchestration.llmusage.service.LlmUsageStatsService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
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
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * LLM 지출 조회(ARTEL-233 후속).
 *
 * 실제 PostgreSQL을 쓴다 — 기능이 전부 SQL 안에 있다. GROUPING SETS 네 집합, `service`마다 다른
 * 테이블로 가는 다형 참조 조인, `AT TIME ZONE` 일별 버킷 중 어느 하나라도 인메모리 대역으로
 * 바꾸면 검증할 것이 남지 않는다.
 *
 * 가장 중요한 케이스 둘:
 *
 * - [네 축의 합계가 서로 같다] — 축은 같은 호출 집합을 다르게 접은 것이라 합이 어긋나면 어느
 *   축이 틀렸는지 화면에서 알 수 없다.
 * - [남의 프로젝트 지출이 새지 않는다] — 관리자 role이 없어 멤버십 조인이 유일한 경계다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LlmUsageStatsIntegrationTest {

    @Autowired private lateinit var statsService: LlmUsageStatsService
    @Autowired private lateinit var llmUsageRepository: LlmUsageRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    private val now: Instant = Instant.parse("2026-08-05T00:00:00Z")
    private val windowStart: Instant = now.minus(Duration.ofDays(7))
    private val windowEnd: Instant = now.plus(Duration.ofDays(1))

    private var projectId: Long = 0
    private var otherProjectId: Long = 0
    private var scenarioId: Long = 0
    private var ownerId: Long = 0
    private var strangerId: Long = 0
    private var instanceSeq: Int = 0

    @BeforeEach
    fun seed(): Unit = runBlocking {
        wipe()
        ownerId = signIn("usage-owner", "5501").userId.toLong()
        strangerId = signIn("usage-stranger", "5502").userId.toLong()

        projectId = newProject("usage-project", ownerId)
        otherProjectId = newProject("stranger-project", strangerId)
        scenarioId = testScenarioRepository.save(TestScenarioEntity(projectId = projectId))!!.id!!
    }

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        llmUsageRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testRunRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `every axis folds the same calls, so the four sums agree`(): Unit = runBlocking {
        val qaTryId = seedRun()
        seedUsage("QA_RUN", qaTryId, input = 100, output = 10, cost = "0.001000")
        seedUsage("QA_RUN", qaTryId, input = 200, output = 20, cost = "0.002000")
        seedUsage("EMBEDDING", projectId, input = 500, output = 0, cost = "0.000500", model = "openai/text-embedding-3-large")
        seedUsage("KNOWLEDGE_QUERY", projectId, input = 50, output = 5, cost = "0.000100")

        val stats = stats()

        assertThat(stats.total.calls).isEqualTo(4)
        assertThat(stats.total.inputTokens).isEqualTo(850)
        assertThat(stats.total.outputTokens).isEqualTo(35)
        assertThat(stats.total.costUsd).isEqualByComparingTo(BigDecimal("0.003600"))

        // 축은 분할이 아니라 같은 집합을 네 번 접은 것이다. 하나라도 합이 어긋나면 그 축의
        // 조인이나 GROUPING 집합이 틀린 것이고, 화면만 봐서는 어느 쪽인지 알 수 없다.
        assertThat(stats.byService.sumOf { it.totals.calls }).isEqualTo(stats.total.calls)
        assertThat(stats.byModel.sumOf { it.totals.calls }).isEqualTo(stats.total.calls)
        assertThat(stats.byProject.sumOf { it.totals.calls }).isEqualTo(stats.total.calls)
        assertThat(stats.daily.sumOf { it.totals.calls }).isEqualTo(stats.total.calls)
        assertThat(stats.byService.sumOf { it.totals.inputTokens }).isEqualTo(850)
        assertThat(stats.daily.sumOf { it.totals.inputTokens }).isEqualTo(850)
    }

    @Test
    fun `service splits spend the way the polymorphic reference intends`(): Unit = runBlocking {
        val qaTryId = seedRun()
        seedUsage("QA_RUN", qaTryId, input = 300, output = 30, cost = "0.003000")
        seedUsage("EMBEDDING", projectId, input = 500, output = 0, cost = "0.000500")

        val byService = stats().byService.associateBy { it.service }

        assertThat(byService.keys).containsExactlyInAnyOrder("QA_RUN", "EMBEDDING")
        assertThat(byService.getValue("QA_RUN").totals.inputTokens).isEqualTo(300)
        // 임베딩은 텍스트를 벡터로 바꿀 뿐 토큰을 만들지 않는다. 출력 0은 이상 데이터가 아니다.
        assertThat(byService.getValue("EMBEDDING").totals.outputTokens).isEqualTo(0)
        assertThat(byService.getValue("EMBEDDING").totals.inputTokens).isEqualTo(500)
    }

    @Test
    fun `a scenario call resolves its project through test_run`(): Unit = runBlocking {
        val testRunId = testRunRepository.save(
            TestRunEntity(projectId = projectId, name = "run-set")
        )!!.id!!
        seedUsage("SCENARIO", testRunId, input = 700, output = 70, cost = "0.007000")

        val byProject = stats().byProject

        // reference_id가 test_run.id라, 이 조인이 없으면 시나리오 작성 지출이 통째로 사라진다.
        assertThat(byProject).hasSize(1)
        assertThat(byProject.single().projectId).isEqualTo(projectId.toString())
        assertThat(byProject.single().totals.inputTokens).isEqualTo(700)
    }

    @Test
    fun `another project's spend never reaches a non-member`(): Unit = runBlocking {
        seedUsage("KNOWLEDGE_QUERY", projectId, input = 100, output = 10, cost = "0.001000")
        seedUsage("KNOWLEDGE_QUERY", otherProjectId, input = 999, output = 99, cost = "9.000000")

        val mine = stats()

        // 관리자 role이 없어 멤버십 조인이 유일한 경계다. 여기가 새면 아무 로그인 사용자나 배포
        // 전체의 지출을 본다.
        assertThat(mine.total.inputTokens).isEqualTo(100)
        assertThat(mine.byProject.map { it.projectId }).containsExactly(projectId.toString())
        assertThat(mine.total.costUsd).isEqualByComparingTo(BigDecimal("0.001000"))
    }

    @Test
    fun `a call whose target is gone is counted apart, never as free`(): Unit = runBlocking {
        seedUsage("KNOWLEDGE_QUERY", projectId, input = 100, output = 10, cost = "0.001000")
        // 가리키던 행이 지워졌거나, agent가 무엇의 호출인지 모른 경로.
        seedUsage("QA_RUN", referenceId = null, input = 400, output = 40, cost = "0.004000")
        seedUsage("QA_RUN", referenceId = 999_999, input = 400, output = 40, cost = "0.004000")

        val stats = stats()

        // 합계에서는 빠지되 사라지지는 않는다. 알리지 않으면 화면의 "전체"가 조용히 작아진다.
        assertThat(stats.total.calls).isEqualTo(1)
        assertThat(stats.unattributedCalls).isEqualTo(2)
    }

    @Test
    fun `unknown unit price stays unknown instead of collapsing to zero`(): Unit = runBlocking {
        seedUsage("KNOWLEDGE_QUERY", projectId, input = 100, output = 10, cost = null)

        val stats = stats()

        // null은 "공짜"가 아니라 "provider가 단가를 안 알려줬다"이다. 0으로 뭉개면 비용 비교가
        // 조용히 틀린다.
        assertThat(stats.total.costUsd).isNull()
        assertThat(stats.total.calls).isEqualTo(1)
        assertThat(stats.total.pricedCalls).isEqualTo(0)
    }

    @Test
    fun `a partly priced total reports how many calls it stands on`(): Unit = runBlocking {
        seedUsage("KNOWLEDGE_QUERY", projectId, input = 100, output = 10, cost = "0.001000")
        seedUsage("KNOWLEDGE_QUERY", projectId, input = 100, output = 10, cost = null)

        val stats = stats()

        assertThat(stats.total.costUsd).isEqualByComparingTo(BigDecimal("0.001000"))
        // 이 둘이 다르면 금액은 실제 지출의 하한이다. 같은 줄에 실어야 화면이 그 사실을 말할 수 있다.
        assertThat(stats.total.calls).isEqualTo(2)
        assertThat(stats.total.pricedCalls).isEqualTo(1)
    }

    @Test
    fun `the day bucket follows the requested zone, not UTC`(): Unit = runBlocking {
        // KST로는 8월 4일 오전 8시, UTC로는 8월 3일 오후 11시다.
        seedUsage(
            "KNOWLEDGE_QUERY",
            projectId,
            input = 100,
            output = 10,
            cost = "0.001000",
            calledAt = Instant.parse("2026-08-03T23:00:00Z")
        )

        assertThat(stats(zone = "UTC").daily.single().date).isEqualTo(LocalDate.parse("2026-08-03"))
        // 월 경계에서는 이 차이가 그대로 "이번 달 얼마 썼나"의 답을 바꾼다.
        assertThat(stats(zone = "Asia/Seoul").daily.single().date)
            .isEqualTo(LocalDate.parse("2026-08-04"))
    }

    @Test
    fun `an unknown zone is rejected here, not by the database`(): Unit = runBlocking {
        // Postgres에 그대로 넘기면 문맥 없는 500이 되고, 어느 파라미터가 문제였는지 안 남는다.
        assertThatThrownBy {
            runBlocking { stats(zone = "Mars/Olympus") }
        }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `the window is cut by called_at, not by when the batch arrived`(): Unit = runBlocking {
        seedUsage(
            "KNOWLEDGE_QUERY",
            projectId,
            input = 100,
            output = 10,
            cost = "0.001000",
            // 창 밖에 호출됐고, 배치는 창 안에 도착했다. agent는 모아 보내므로 이 어긋남은 정상이다.
            calledAt = windowStart.minus(Duration.ofDays(1)),
            createdAt = now
        )

        // 돈이 나간 시점은 호출 시점이지 우리가 그 사실을 알게 된 시점이 아니다(V24 주석).
        assertThat(stats().total.calls).isEqualTo(0)
    }

    // ------------------------------------------------------------- QA 런 목록

    @Test
    fun `each qa run reports its own tokens and cost`(): Unit = runBlocking {
        val cheap = seedRun(model = "haiku-4-5")
        val pricey = seedRun(model = "sonnet-5")
        seedUsage("QA_RUN", cheap, input = 100, output = 10, cost = "0.001000")
        seedUsage("QA_RUN", pricey, input = 900, output = 90, cost = "0.009000")
        seedUsage("QA_RUN", pricey, input = 100, output = 10, cost = "0.001000")

        val runs = statsService
            .qaRuns(ownerId, projectId, windowStart, windowEnd, 50)
            .associateBy { it.qaTryId }

        assertThat(runs.getValue(cheap.toString()).totals.inputTokens).isEqualTo(100)
        assertThat(runs.getValue(cheap.toString()).totals.calls).isEqualTo(1)
        assertThat(runs.getValue(pricey.toString()).totals.inputTokens).isEqualTo(1000)
        assertThat(runs.getValue(pricey.toString()).totals.calls).isEqualTo(2)
        assertThat(runs.getValue(pricey.toString()).totals.costUsd)
            .isEqualByComparingTo(BigDecimal("0.010000"))
    }

    @Test
    fun `a run with no reported call is listed with zero, not dropped`(): Unit = runBlocking {
        val silent = seedRun()

        val run = statsService.qaRuns(ownerId, projectId, windowStart, windowEnd, 50).single()

        // 빼면 "아직 배치가 안 왔다"와 "그런 런이 없다"가 같은 화면이 된다.
        assertThat(run.qaTryId).isEqualTo(silent.toString())
        assertThat(run.totals.calls).isEqualTo(0)
        assertThat(run.totals.inputTokens).isEqualTo(0)
        assertThat(run.totals.costUsd).isNull()
    }

    @Test
    fun `other services never leak into one run's cost`(): Unit = runBlocking {
        val qaTryId = seedRun()
        seedUsage("QA_RUN", qaTryId, input = 100, output = 10, cost = "0.001000")
        // reference_id는 다형 참조라 같은 값이 다른 테이블의 id를 가리킨다. service를 안 걸면
        // 임베딩 지출이 이 런의 비용으로 들어온다.
        seedUsage("EMBEDDING", qaTryId, input = 999, output = 0, cost = "9.000000")

        val run = statsService.qaRun(ownerId, qaTryId)!!

        assertThat(run.totals.inputTokens).isEqualTo(100)
        assertThat(run.totals.costUsd).isEqualByComparingTo(BigDecimal("0.001000"))
    }

    @Test
    fun `a run in another project is not readable`(): Unit = runBlocking {
        val qaTryId = seedRun()
        seedUsage("QA_RUN", qaTryId, input = 100, output = 10, cost = "0.001000")

        assertThat(statsService.qaRun(strangerId, qaTryId)).isNull()
    }

    @Test
    fun `a single run is found outside the default window`(): Unit = runBlocking {
        // 기본 창(30일)을 훨씬 넘긴 런. 창을 걸면 0으로 보이고 그것이 "안 썼다"로 읽힌다.
        val old = seedRun(startedAt = now.minus(Duration.ofDays(400)))
        seedUsage("QA_RUN", old, input = 100, output = 10, cost = "0.001000")

        assertThat(statsService.qaRun(ownerId, old)!!.totals.inputTokens).isEqualTo(100)
    }

    // ---------------------------------------------------------------- 파라미터

    @Test
    fun `a window longer than a year is refused instead of silently truncated`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking {
                statsService.stats(ownerId, null, now.minus(Duration.ofDays(400)), now, "UTC")
            }
        }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `from must be earlier than to`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { statsService.stats(ownerId, null, windowEnd, windowStart, "UTC") }
        }.isInstanceOf(BadRequestException::class.java)
    }

    // ----------------------------------------------------------------- 픽스처

    private suspend fun stats(zone: String = "UTC") =
        statsService.stats(ownerId, null, windowStart, windowEnd, zone)

    private suspend fun newProject(name: String, memberId: Long): Long {
        val project = projectRepository.save(
            ProjectEntity(name = name, genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = memberId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        return project.id!!
    }

    /**
     * 런 하나. 인스턴스를 매번 새로 만드는 이유는 `uk_qa_try_active_instance`가 인스턴스당 활성
     * 런을 하나로 묶기 때문이다.
     */
    private suspend fun seedRun(
        model: String? = "sonnet-5",
        startedAt: Instant = now.minus(Duration.ofDays(1))
    ): Long {
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId,
                name = "instance-${instanceSeq++}",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenarioId,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = "COMPLETED",
                model = model,
                reasoningEffort = "high",
                promptVersion = "v3",
                agentArch = "v2-tool-loop",
                startedAt = startedAt,
                completedAt = startedAt.plusMillis(30_000)
            )
        )!!.id!!
    }

    private suspend fun seedUsage(
        service: String,
        referenceId: Long?,
        input: Int,
        output: Int,
        cost: String?,
        model: String = "anthropic/sonnet-5",
        calledAt: Instant = now.minus(Duration.ofDays(1)),
        createdAt: Instant = now
    ) {
        llmUsageRepository.save(
            LlmUsageEntity(
                service = service,
                referenceId = referenceId,
                provider = model.substringBefore('/'),
                model = model,
                inputTokens = input,
                outputTokens = output,
                costUsd = cost?.let(::BigDecimal),
                latencyMs = 1_200,
                calledAt = calledAt,
                createdAt = createdAt
            )
        )
    }

    private suspend fun signIn(login: String, providerUserId: String): AuthenticatedUser =
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
}
