package kr.artel.orchestration.qa

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
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.dto.QaRunConfigStatsCell
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaStatsService
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
import java.util.UUID

/**
 * 실행 설정 축 집계(ARTEL-239 후속).
 *
 * 실제 PostgreSQL을 쓴다 — 이 기능은 전부 SQL 안에 있다. GROUPING SETS, `FILTER`, 사전 집계
 * LEFT JOIN 중 어느 하나라도 인메모리 대역으로 바꾸면 검증할 것이 남지 않는다.
 *
 * 가장 중요한 케이스는 [llm_usage 다건이 런 수를 부풀리지 않는다]이다. `llm_usage`를 `qa_try`에
 * 그냥 조인하면 런 하나가 호출 수만큼 복제돼 완주율과 런 수가 동시에 틀리는데, 숫자가 그럴듯해
 * 보여 화면만 봐서는 잡히지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaStatsIntegrationTest {

    @Autowired private lateinit var statsService: QaStatsService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var llmUsageRepository: LlmUsageRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    private val now: Instant = Instant.parse("2026-08-05T00:00:00Z")
    private val windowStart: Instant = now.minus(Duration.ofDays(7))
    private val windowEnd: Instant = now.plus(Duration.ofDays(1))

    private var projectId: Long = 0
    private var scenarioId: Long = 0
    private var ownerId: Long = 0
    private var instanceSeq: Int = 0

    @BeforeEach
    fun seed(): Unit = runBlocking {
        wipe()
        val owner = signIn("stats-owner", "2391")
        ownerId = owner.userId.toLong()
        val project = projectRepository.save(
            ProjectEntity(name = "stats-project", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectId = project.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = projectId,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        scenarioId = testScenarioRepository.save(
            TestScenarioEntity(projectId = projectId)
        )!!.id!!
    }

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        llmUsageRepository.deleteAll()
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `runs fold into one cell per settings combination`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", arch = "v2-tool-loop", status = "COMPLETED")
        seedRun(model = "sonnet-5", arch = "v2-tool-loop", status = "COMPLETED")
        seedRun(model = "sonnet-5", arch = "v2-tool-loop", status = "FAILED")
        seedRun(model = "gpt-4o", arch = "v2-tool-loop", status = "COMPLETED")

        val stats = stats()

        assertThat(stats.cells).hasSize(2)
        val sonnet = stats.cellFor(model = "sonnet-5")
        assertThat(sonnet.runs).isEqualTo(3)
        assertThat(sonnet.completed).isEqualTo(2)
        assertThat(sonnet.failed).isEqualTo(1)
        assertThat(stats.cellFor(model = "gpt-4o").runs).isEqualTo(1)
        assertThat(stats.total.runs).isEqualTo(4)
        assertThat(stats.total.completed).isEqualTo(3)
    }

    @Test
    fun `cancelled and still-running are counted apart from failed`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED")
        seedRun(model = "sonnet-5", status = "FAILED")
        seedRun(model = "sonnet-5", status = "CANCELLED")
        seedRun(model = "sonnet-5", status = "RUNNING")

        val cell = stats().cellFor(model = "sonnet-5")

        // 운영자가 멈춘 런을 실패로 세면 완주율이 조작 가능한 숫자가 된다.
        assertThat(cell.failed).isEqualTo(1)
        assertThat(cell.cancelled).isEqualTo(1)
        assertThat(cell.active).isEqualTo(1)
        assertThat(cell.completed).isEqualTo(1)
        assertThat(cell.runs).isEqualTo(4)
    }

    @Test
    fun `runs with unknown settings stay in the totals as a null cell`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED")
        // ARTEL-239 이전에 끝난 런, 또는 run_config를 안 돌려주는 구버전 Agent.
        seedRun(model = null, arch = null, promptVersion = null, effort = null, status = "COMPLETED")

        val stats = stats()

        val unknown = stats.cells.single { it.model == null }
        assertThat(unknown.runs).isEqualTo(1)
        // 버리면 셀 합과 총계가 어긋나고, 그 차이를 화면에서 설명할 방법이 없다.
        assertThat(stats.cells.sumOf { it.runs }).isEqualTo(stats.total.runs)
    }

    @Test
    fun `many llm calls on one run do not inflate the run count`(): Unit = runBlocking {
        val qaTryId = seedRun(model = "sonnet-5", status = "COMPLETED")
        seedUsage(qaTryId, inputTokens = 100, outputTokens = 10, costUsd = "0.001000")
        seedUsage(qaTryId, inputTokens = 200, outputTokens = 20, costUsd = "0.002000")
        seedUsage(qaTryId, inputTokens = 300, outputTokens = 30, costUsd = "0.003000")

        val cell = stats().cellFor(model = "sonnet-5")

        assertThat(cell.runs).isEqualTo(1)
        assertThat(cell.completed).isEqualTo(1)
        assertThat(cell.llmCalls).isEqualTo(3)
        assertThat(cell.inputTokens).isEqualTo(600)
        assertThat(cell.outputTokens).isEqualTo(60)
        assertThat(cell.costUsd).isEqualByComparingTo(BigDecimal("0.006000"))
    }

    @Test
    fun `usage from another service does not leak into qa cost`(): Unit = runBlocking {
        val qaTryId = seedRun(model = "sonnet-5", status = "COMPLETED")
        seedUsage(qaTryId, inputTokens = 100, outputTokens = 10, costUsd = "0.001000")
        // reference_id는 다형 참조라 같은 값이 다른 테이블의 id를 가리킨다. service가 없으면
        // 임베딩 지출이 QA 런 비용으로 들어온다.
        seedUsage(qaTryId, inputTokens = 999, outputTokens = 0, costUsd = "9.000000", service = "EMBEDDING")

        val cell = stats().cellFor(model = "sonnet-5")

        assertThat(cell.inputTokens).isEqualTo(100)
        assertThat(cell.costUsd).isEqualByComparingTo(BigDecimal("0.001000"))
    }

    @Test
    fun `a run with no priced call reports unknown cost, not zero`(): Unit = runBlocking {
        val qaTryId = seedRun(model = "sonnet-5", status = "COMPLETED")
        seedUsage(qaTryId, inputTokens = 100, outputTokens = 10, costUsd = null)

        val cell = stats().cellFor(model = "sonnet-5")

        assertThat(cell.inputTokens).isEqualTo(100)
        // 0으로 뭉개면 "공짜"와 "단가 미상"이 같아진다.
        assertThat(cell.costUsd).isNull()
    }

    @Test
    fun `only completed runs feed the average duration`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED", durationMs = 60_000)
        seedRun(model = "sonnet-5", status = "COMPLETED", durationMs = 20_000)
        // 즉시 실패가 평균에 들어가면 "빨라졌다"로 읽힌다.
        seedRun(model = "sonnet-5", status = "FAILED", durationMs = 500)

        val cell = stats().cellFor(model = "sonnet-5")

        assertThat(cell.avgCompletedDurationMs).isNotNull()
        assertThat(cell.avgCompletedDurationMs!!).isCloseTo(40_000.0, org.assertj.core.data.Offset.offset(1.0))
    }

    @Test
    fun `the window includes from and excludes to`(): Unit = runBlocking {
        seedRun(model = "before", status = "COMPLETED", startedAt = windowStart.minusSeconds(1))
        seedRun(model = "at-from", status = "COMPLETED", startedAt = windowStart)
        seedRun(model = "at-to", status = "COMPLETED", startedAt = windowEnd)

        val stats = stats()

        assertThat(stats.cells.map { it.model }).containsExactly("at-from")
        assertThat(stats.total.runs).isEqualTo(1)
    }

    @Test
    fun `a non-member sees nothing`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED")
        val outsider = signIn("outsider", "2392").userId.toLong()

        val stats = statsService.stats(projectId, outsider, windowStart, windowEnd, 200)

        // 403이 아니라 빈 집계다. 여기만 거절하면 프로젝트의 존재 여부가 샌다.
        assertThat(stats.cells).isEmpty()
        assertThat(stats.total.runs).isEqualTo(0)
        assertThat(stats.total.costUsd).isNull()
    }

    @Test
    fun `truncation is reported and the totals stay whole`(): Unit = runBlocking {
        repeat(4) { seedRun(model = "model-$it", status = "COMPLETED") }

        val stats = statsService.stats(projectId, ownerId, windowStart, windowEnd, 2)

        assertThat(stats.truncated).isTrue()
        assertThat(stats.cells).hasSize(2)
        // 총계는 자르기 전 전체에서 나온다. 잘린 셀의 합이 아니다.
        assertThat(stats.total.runs).isEqualTo(4)
    }

    @Test
    fun `an inverted window is refused`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { statsService.stats(projectId, ownerId, windowEnd, windowStart, 200) }
        }.isInstanceOf(BadRequestException::class.java)
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun stats() = statsService.stats(projectId, ownerId, windowStart, windowEnd, 200)

    private fun kr.artel.orchestration.qa.dto.QaStatsResponse.cellFor(model: String): QaRunConfigStatsCell =
        cells.single { it.model == model }

    /**
     * 런 하나. 인스턴스를 매번 새로 만드는 이유는 `uk_qa_try_active_instance`가 인스턴스당
     * 활성 런을 하나로 묶기 때문이다 — 같은 인스턴스에 RUNNING을 둘 만들 수 없다.
     */
    private suspend fun seedRun(
        model: String? = "sonnet-5",
        effort: String? = "high",
        promptVersion: String? = "v3",
        arch: String? = "v2-tool-loop",
        status: String,
        startedAt: Instant = now.minus(Duration.ofDays(1)),
        durationMs: Long = 30_000
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
        val terminal = status in setOf("COMPLETED", "FAILED", "CANCELLED")
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenarioId,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = status,
                model = model,
                reasoningEffort = effort,
                promptVersion = promptVersion,
                agentArch = arch,
                startedAt = startedAt,
                completedAt = if (terminal) startedAt.plusMillis(durationMs) else null
            )
        )!!.id!!
    }

    private suspend fun seedUsage(
        qaTryId: Long,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: String?,
        service: String = "QA_RUN"
    ) {
        llmUsageRepository.save(
            LlmUsageEntity(
                service = service,
                referenceId = qaTryId,
                provider = "anthropic",
                model = "sonnet-5",
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = costUsd?.let(::BigDecimal),
                latencyMs = 1_200,
                calledAt = now.minus(Duration.ofDays(1)),
                createdAt = now
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
