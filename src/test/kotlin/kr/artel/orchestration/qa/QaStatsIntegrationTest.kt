package kr.artel.orchestration.qa

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.entity.PlatformRole
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
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaStatsService
import kr.artel.orchestration.qa.entity.QaRunEntity
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
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var scoreRepository: kr.artel.orchestration.qa.repository.QaTryScoreRepository
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
        scoreRepository.deleteAll()
        llmUsageRepository.deleteAll()
        qaLogRepository.deleteAll()
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
    fun `verdict sums come with the run count they rest on`(): Unit = runBlocking {
        seedRun(
            model = "sonnet-5", status = "COMPLETED",
            stepsTotal = 5, stepsPassed = 5, casesTotal = 2, casesPassed = 2
        )
        seedRun(
            model = "sonnet-5", status = "FAILED",
            stepsTotal = 5, stepsPassed = 1, casesTotal = 2, casesPassed = 0
        )
        // 소켓이 죽어 요약 없이 끝난 런. 판정을 **모르는** 것이지 0점이 아니다.
        seedRun(model = "sonnet-5", status = "FAILED")

        val cell = stats().cellFor(model = "sonnet-5")

        assertThat(cell.runs).isEqualTo(3)
        // 셋 중 둘만 판정을 안다. 이 차이가 보이지 않으면 6/10을 세 런의 성적으로 읽게 되고,
        // 그 비율은 깔끔하게 끝난 런에만 조건부다.
        assertThat(cell.verdictKnown).isEqualTo(2)
        assertThat(cell.stepsTotal).isEqualTo(10)
        assertThat(cell.stepsPassed).isEqualTo(6)
        assertThat(cell.casesTotal).isEqualTo(4)
        assertThat(cell.casesPassed).isEqualTo(2)
    }

    @Test
    fun `a scope with no promoted verdict reports zero known, not a null sum`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED")

        val cell = stats().cellFor(model = "sonnet-5")

        // SUM은 전부 NULL이면 NULL을 낸다. 그대로 내보내면 화면이 "0점"과 구분할 수 없으므로
        // 합계는 0으로 접고, 그것이 미측정이라는 사실은 verdictKnown이 진다.
        assertThat(cell.verdictKnown).isEqualTo(0)
        assertThat(cell.stepsTotal).isEqualTo(0)
        assertThat(cell.stepsPassed).isEqualTo(0)
        assertThat(cell.casesTotal).isEqualTo(0)
        assertThat(cell.casesPassed).isEqualTo(0)
    }

    @Test
    fun `채점 결과가 축별로 접히고 재채점이 런 수를 부풀리지 않는다`(): Unit = runBlocking {
        val a = seedRun(model = "sonnet-5", status = "COMPLETED", stepsTotal = 4, stepsPassed = 3)
        val b = seedRun(model = "sonnet-5", status = "FAILED", stepsTotal = 4, stepsPassed = 1)
        // 라벨이 하나도 없어 채점 대상이 아닌 런. 판정은 멀쩡히 받았다.
        seedRun(model = "sonnet-5", status = "COMPLETED", stepsTotal = 4, stepsPassed = 4)

        seedScore(a, correctPass = 2, falseAlarm = 1, miss = 0, correctFail = 1, unreported = 0)
        seedScore(b, correctPass = 0, falseAlarm = 0, miss = 2, correctFail = 0, unreported = 2)
        // 같은 런을 새 기준으로 재채점했다. 접지 않고 조인하면 이 런이 두 번 세어진다.
        seedScore(a, correctPass = 3, falseAlarm = 0, miss = 0, correctFail = 1, unreported = 0, version = "2")

        val cell = stats().cellFor(model = "sonnet-5")

        assertThat(cell.runs).isEqualTo(3)
        assertThat(cell.verdictKnown).isEqualTo(3)
        // 셋 중 둘만 채점됐다 — verdictKnown과 다른 수다.
        assertThat(cell.scoredRuns).isEqualTo(2)
        // 재채점한 런은 **최신 판정만** 센다.
        assertThat(cell.correctPass).isEqualTo(3)
        assertThat(cell.falseAlarm).isEqualTo(0)
        assertThat(cell.miss).isEqualTo(2)
        assertThat(cell.correctFail).isEqualTo(1)
        assertThat(cell.unreported).isEqualTo(2)
    }

    @Test
    fun `채점된 런이 없으면 scoredRuns가 0이고 합계도 0이다`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED", stepsTotal = 4, stepsPassed = 4)

        val cell = stats().cellFor(model = "sonnet-5")

        // "채점할 것이 없었다"이지 "0점"이 아니다 — 그 구분은 scoredRuns가 진다.
        assertThat(cell.scoredRuns).isEqualTo(0)
        assertThat(cell.miss).isEqualTo(0)
        assertThat(cell.unreported).isEqualTo(0)
    }

    @Test
    fun `오탐과 미탐이 축별로 갈려 나온다`(): Unit = runBlocking {
        // 관대한 모델: 실패해야 할 것을 통과라 했다.
        val lenient = seedRun(model = "lenient", status = "COMPLETED", stepsTotal = 5, stepsPassed = 5)
        seedScore(lenient, correctPass = 3, falseAlarm = 0, miss = 2, correctFail = 0, unreported = 0)
        // 까다로운 모델: 멀쩡한 것을 실패라 했다. **틀린 개수는 같다.**
        val strict = seedRun(model = "strict", status = "COMPLETED", stepsTotal = 5, stepsPassed = 3)
        seedScore(strict, correctPass = 1, falseAlarm = 2, miss = 0, correctFail = 2, unreported = 0)

        val stats = stats()

        // 스칼라 하나로 접었다면 둘이 같은 점수로 보인다. QA 에이전트에게 미탐이 훨씬 나쁘고
        // (못 찾은 버그는 출시된다), 그 방향이 여기서 사라지면 화면이 되살릴 수 없다.
        assertThat(stats.cellFor(model = "lenient").miss).isEqualTo(2)
        assertThat(stats.cellFor(model = "lenient").falseAlarm).isEqualTo(0)
        assertThat(stats.cellFor(model = "strict").miss).isEqualTo(0)
        assertThat(stats.cellFor(model = "strict").falseAlarm).isEqualTo(2)
        assertThat(stats.total.miss).isEqualTo(2)
        assertThat(stats.total.falseAlarm).isEqualTo(2)
    }

    @Test
    fun `detail 모양이 다른 채점 행이 집계를 죽이지 않고 분모에서 빠진다`(): Unit = runBlocking {
        val scored = seedRun(model = "sonnet-5", status = "COMPLETED", stepsTotal = 4, stepsPassed = 3)
        seedScore(scored, correctPass = 1, falseAlarm = 0, miss = 0, correctFail = 1, unreported = 0)
        val broken = seedRun(model = "sonnet-5", status = "COMPLETED", stepsTotal = 4, stepsPassed = 4)
        // detail은 JSONB라 스키마가 강제되지 않는다. 이 행 하나로 `::int` 캐스트가 던지면
        // 이 프로젝트의 대시보드가 통째로 500이 된다.
        scoreRepository.insertIfAbsent(
            qaTryId = broken,
            grader = "expected-steps",
            graderVersion = "9",
            detail = """{"matrix":{"correct_pass":"두 개"},"unreported":null}"""
        )

        val cell = stats().cellFor(model = "sonnet-5")

        assertThat(cell.runs).isEqualTo(2)
        // 걸러진 행은 분모에도 안 들어간다 — "0점"이 아니라 "채점을 모른다"로 남는다.
        assertThat(cell.scoredRuns).isEqualTo(1)
        assertThat(cell.correctPass).isEqualTo(1)
        assertThat(cell.correctFail).isEqualTo(1)
    }

    @Test
    fun `모양이 깨진 재채점이 멀쩡한 옛 판정을 가리지 않는다`(): Unit = runBlocking {
        val qaTryId = seedRun(model = "sonnet-5", status = "COMPLETED", stepsTotal = 4, stepsPassed = 3)
        seedScore(qaTryId, correctPass = 2, falseAlarm = 1, miss = 0, correctFail = 1, unreported = 0)
        // 나중에 들어온 행이라 DISTINCT ON이 이것을 고를 차례다. 가드가 없으면 캐스트가 던지고,
        // 가드가 행 단위가 아니라 런 단위로 걸리면 이 런의 멀쩡한 판정까지 통째로 사라진다.
        scoreRepository.insertIfAbsent(
            qaTryId = qaTryId,
            grader = "expected-steps",
            graderVersion = "2",
            detail = """{"matrix":{"miss":null},"unreported":"셋"}"""
        )

        val cell = stats().cellFor(model = "sonnet-5")

        assertThat(cell.scoredRuns).isEqualTo(1)
        assertThat(cell.correctPass).isEqualTo(2)
        assertThat(cell.falseAlarm).isEqualTo(1)
    }

    @Test
    fun `축이 전부 NULL인 런의 점수도 총계에 남는다`(): Unit = runBlocking {
        // ARTEL-239 이전 런이라 축이 전부 NULL이지만 채점은 붙어 있다.
        val legacy = seedRun(
            model = null, arch = null, promptVersion = null, effort = null,
            status = "COMPLETED", stepsTotal = 4, stepsPassed = 1
        )
        seedScore(legacy, correctPass = 1, falseAlarm = 0, miss = 3, correctFail = 0, unreported = 0)

        val stats = stats()

        val unknown = stats.cells.single { it.model == null }
        assertThat(unknown.scoredRuns).isEqualTo(1)
        assertThat(unknown.miss).isEqualTo(3)
        // 버리면 미탐이 총계에만 남고 어느 축의 것인지 설명할 자리가 사라진다.
        assertThat(stats.total.miss).isEqualTo(3)
        assertThat(stats.cells.sumOf { it.scoredRuns }).isEqualTo(stats.total.scoredRuns)
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

    /**
     * `DEVELOPER` 등급은 참여하지 않아도 집계를 받는다(ARTEL-742).
     *
     * 바로 위의 `a non-member sees nothing`과 짝이다. 이 질의는 멤버십 판정을 `JOIN project_member`
     * 대신 `EXISTS`와 `:seesAllProjects`로 옮겼는데, 그 둘 중 하나만 확인하면 조건을 통째로 지워도
     * 테스트가 녹색이거나(윗쪽만 볼 때) 아무도 못 보게 만들어도 녹색이다(이쪽만 볼 때).
     */
    @Test
    fun `a developer sees another project's stats`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED")
        val developerId = signIn("stats-developer", "2393").userId.toLong()
        promote(developerId)

        val stats = statsService.stats(projectId, developerId, windowStart, windowEnd, 200)

        assertThat(stats.cellFor(model = "sonnet-5").runs).isEqualTo(1)
        assertThat(stats.total.runs).isEqualTo(1)
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


    // ------------------------------------------------ 프로젝트를 생략한 합산 (ARTEL-750)

    /**
     * 프로젝트를 안 주면 볼 수 있는 것을 다 더한다.
     *
     * 오너는 자기 프로젝트 하나의 멤버라 남의 런은 섞이지 않고, 개발자는 어느 쪽의 멤버도 아니지만
     * 둘을 다 받는다. 두 사람을 한 테스트에 두는 것은 "합산이 된다" 와 "합산이 경계를 지킨다" 가
     * 같은 질의의 앞뒤라서다 — 나누면 조건을 통째로 지워도 한쪽은 녹색이다.
     */
    @Test
    fun `omitting the project folds every project the caller can see`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED")
        seedForeignRun(model = "gpt-4o")
        val developerId = signIn("stats-fold-developer", "2394").userId.toLong()
        promote(developerId)

        val mine = statsService.stats(null, ownerId, windowStart, windowEnd, 200)
        val all = statsService.stats(null, developerId, windowStart, windowEnd, 200)

        assertThat(mine.total.runs).isEqualTo(1)
        assertThat(mine.cells.map { it.model }).containsExactly("sonnet-5")
        assertThat(all.total.runs).isEqualTo(2)
        assertThat(all.cells.map { it.model }).containsExactlyInAnyOrder("sonnet-5", "gpt-4o")
    }

    /** 아무 프로젝트에도 참여하지 않은 `USER` 는 생략해도 빈 집계다. */
    @Test
    fun `omitting the project gives a non-member nothing`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED")
        val outsider = signIn("stats-fold-outsider", "2395").userId.toLong()

        val stats = statsService.stats(null, outsider, windowStart, windowEnd, 200)

        assertThat(stats.cells).isEmpty()
        assertThat(stats.total.runs).isZero()
    }

    /**
     * 생략한 응답의 `projectId` 는 null 이다.
     *
     * 화면이 이 값으로 "무엇의 집계인가" 를 적으므로, 아무 프로젝트 id 나 채워 넣으면 전체 합계가
     * 그 프로젝트 하나의 것으로 읽힌다.
     */
    @Test
    fun `the folded response carries no project id`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED")

        assertThat(statsService.stats(null, ownerId, windowStart, windowEnd, 200).projectId).isNull()
        assertThat(statsService.stats(projectId, ownerId, windowStart, windowEnd, 200).projectId)
            .isEqualTo(projectId.toString())
    }

    /** 삭제된 프로젝트는 합산에서 빠진다. 목록에도 없는 것이 총계에만 남으면 설명할 수 없다. */
    @Test
    fun `a deleted project is left out of the fold`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED")
        val developerId = signIn("stats-deleted-developer", "2396").userId.toLong()
        promote(developerId)
        seedForeignRun(model = "gpt-4o", deletedAt = now)

        val all = statsService.stats(null, developerId, windowStart, windowEnd, 200)

        assertThat(all.cells.map { it.model }).containsExactly("sonnet-5")
        assertThat(all.total.runs).isEqualTo(1)
    }

    // --------------------------------------------------------------- test run

    /**
     * **`testRunId` 를 안 주면 단독 실행 런이 그대로 나온다.**
     *
     * `qa_try.qa_run_id` 는 nullable 이다 — 단독 실행이 null 이라고 엔티티 주석이 적어 뒀다.
     * `qa_run` 을 무조건 INNER JOIN 하면 필터를 안 걸어도 그 런들이 통째로 사라지는데, 그 회귀는
     * 숫자가 그냥 작아지는 모양이라 화면만 봐서는 잡히지 않는다.
     */
    @Test
    fun `testRunId 를 안 주면 단독 실행 런도 그대로 나온다`(): Unit = runBlocking {
        val (_, qaRunId) = seedQaRun("L1 상세")
        seedRun(model = "sonnet-5", status = "COMPLETED", qaRunId = qaRunId)
        seedRun(model = "gpt-4o", status = "COMPLETED")

        val stats = stats()

        assertThat(stats.total.runs).isEqualTo(2)
        assertThat(stats.cells.map { it.model }).containsExactlyInAnyOrder("sonnet-5", "gpt-4o")
    }

    /**
     * 벤치마크 런은 난이도별로 test run 이 쪼개져 있다. 층별로 비교하려고 쪼갠 것이므로 집계도
     * 그 축으로 갈려야 한다 — 갈리지 않으면 넷이 한 덩어리로 접힌다.
     */
    @Test
    fun `testRunId 로 층을 갈라 센다`(): Unit = runBlocking {
        val (probeRunId, probeQaRunId) = seedQaRun("프로브")
        val (detailRunId, detailQaRunId) = seedQaRun("L1 상세")
        seedRun(model = "sonnet-5", status = "COMPLETED", qaRunId = probeQaRunId)
        seedRun(model = "gpt-4o", status = "COMPLETED", qaRunId = detailQaRunId)
        seedRun(model = "haiku", status = "COMPLETED", qaRunId = detailQaRunId)
        // 단독 실행 런은 어느 층에도 속하지 않으므로 층을 물으면 빠진다.
        seedRun(model = "standalone", status = "COMPLETED")

        val probe = statsService.stats(projectId, ownerId, windowStart, windowEnd, 200, probeRunId)
        val detail = statsService.stats(projectId, ownerId, windowStart, windowEnd, 200, detailRunId)

        assertThat(probe.total.runs).isEqualTo(1)
        assertThat(probe.cells.map { it.model }).containsExactly("sonnet-5")
        assertThat(detail.total.runs).isEqualTo(2)
        assertThat(detail.cells.map { it.model }).containsExactlyInAnyOrder("gpt-4o", "haiku")
    }

    /**
     * **프로젝트 권한 술어를 우회하지 않는다.** `testRunId` 는 그 술어에 더해지는 것이지 대신하는
     * 것이 아니다 — 여기가 빠지면 아무 로그인 사용자가 남의 test run 집계를 본다.
     *
     * 없는 것과 못 보는 것을 예외로 갈라 답하지도 않는다. 갈라 답하면 그 test run 의 존재 여부가
     * 새어 나간다(기존 멤버십 조건과 같은 태도).
     */
    @Test
    fun `남의 프로젝트 test run id 는 빈 집계다`(): Unit = runBlocking {
        val (testRunId, qaRunId) = seedQaRun("L1 상세")
        seedRun(model = "sonnet-5", status = "COMPLETED", qaRunId = qaRunId)
        val outsider = signIn("stats-testrun-outsider", "2397").userId.toLong()

        val stats = statsService.stats(projectId, outsider, windowStart, windowEnd, 200, testRunId)

        assertThat(stats.cells).isEmpty()
        assertThat(stats.total.runs).isZero()
    }

    /** 없는 test run id 도 예외가 아니라 빈 집계다. 존재 여부를 응답 모양으로 알려주지 않는다. */
    @Test
    fun `없는 test run id 는 예외가 아니라 빈 집계다`(): Unit = runBlocking {
        seedRun(model = "sonnet-5", status = "COMPLETED")

        val stats = statsService.stats(projectId, ownerId, windowStart, windowEnd, 200, 987_654_321L)

        assertThat(stats.cells).isEmpty()
        assertThat(stats.total.runs).isZero()
    }

    /**
     * 응답이 물어본 test run 을 되돌려 준다. `projectId` 와 같은 이유다 — 여러 층을 나란히 놓고
     * 비교할 때 어느 응답이 어느 층의 것인지가 응답 자체에 없으면 짝을 손으로 들고 있어야 한다.
     */
    @Test
    fun `응답이 물어본 test run 을 되돌려 준다`(): Unit = runBlocking {
        val (testRunId, qaRunId) = seedQaRun("L2 중간")
        seedRun(model = "sonnet-5", status = "COMPLETED", qaRunId = qaRunId)

        assertThat(statsService.stats(projectId, ownerId, windowStart, windowEnd, 200, testRunId).testRunId)
            .isEqualTo(testRunId.toString())
        assertThat(stats().testRunId).isNull()
    }

    // ------------------------------------------------------------------ label

    /**
     * **`label` 을 안 주면 실험에 안 묶인 런이 그대로 나온다.**
     *
     * `qa_run.label` 이 nullable 이고 `qa_try.qa_run_id` 도 nullable 이라, 이 필터를 조인으로
     * 표현하면 필터를 안 걸어도 두 종류의 런이 통째로 사라진다. `testRunId` 와 같은 회귀이고,
     * 숫자가 그냥 작아지는 모양이라 화면만 봐서는 잡히지 않는 것도 같다.
     */
    @Test
    fun `label 을 안 주면 실험에 안 묶인 런도 그대로 나온다`(): Unit = runBlocking {
        val (_, labelled) = seedQaRun("L1 상세", label = "content-map-2x2-파일럿")
        val (_, unlabelled) = seedQaRun("L2 중간")
        seedRun(model = "sonnet-5", status = "COMPLETED", qaRunId = labelled)
        seedRun(model = "gpt-4o", status = "COMPLETED", qaRunId = unlabelled)
        seedRun(model = "haiku", status = "COMPLETED")

        val stats = stats()

        assertThat(stats.total.runs).isEqualTo(3)
        assertThat(stats.cells.map { it.model }).containsExactlyInAnyOrder("sonnet-5", "gpt-4o", "haiku")
    }

    /**
     * 같은 설정으로 다음 달에 다시 돌리면 `run_config` 는 같은데 다른 실험이다. `label` 이 그 둘을
     * 가르는 유일한 값이므로, 이 필터가 없으면 두 실험이 한 덩어리로 접힌다.
     */
    @Test
    fun `label 로 실험 묶음을 갈라 센다`(): Unit = runBlocking {
        val (_, first) = seedQaRun("L2 중간", label = "content-map-2x2-파일럿")
        val (_, second) = seedQaRun("L2 중간 재실행", label = "content-map-2x2-반복")
        seedRun(model = "sonnet-5", status = "COMPLETED", qaRunId = first)
        seedRun(model = "gpt-4o", status = "COMPLETED", qaRunId = second)
        seedRun(model = "haiku", status = "COMPLETED", qaRunId = second)
        // 실험에 안 묶인 런은 어느 묶음에도 없으므로 묶음을 물으면 빠진다.
        seedRun(model = "standalone", status = "COMPLETED")

        val pilot = statsWithLabel("content-map-2x2-파일럿")
        val repeat = statsWithLabel("content-map-2x2-반복")

        assertThat(pilot.total.runs).isEqualTo(1)
        assertThat(pilot.cells.map { it.model }).containsExactly("sonnet-5")
        assertThat(repeat.total.runs).isEqualTo(2)
        assertThat(repeat.cells.map { it.model }).containsExactlyInAnyOrder("gpt-4o", "haiku")
    }

    /**
     * **`label` 과 `testRunId` 는 독립이고 함께 걸린다.** "1차 실험의 9013 런" 이 실제 질문이라,
     * 둘 중 하나가 다른 하나를 덮으면 그 질문을 물을 수 없다. 한쪽만 확인하면 다른 쪽이 무시돼도
     * 녹색이라 셋을 한 테스트에서 본다.
     */
    @Test
    fun `label 과 testRunId 를 함께 걸면 둘 다 좁힌다`(): Unit = runBlocking {
        val (middle, pilotMiddle) = seedQaRun("L2 중간", label = "content-map-2x2-파일럿")
        val (detail, pilotDetail) = seedQaRun("L1 상세", label = "content-map-2x2-파일럿")
        // 같은 test run 을 다른 실험에서 한 번 더 돌린 경우. 이 행이 있어야 label 이 실제로
        // 좁히는지 알 수 있다.
        val repeatMiddle = qaRunRepository.save(
            QaRunEntity(
                testRunId = middle,
                gameInstanceId = gameInstanceRepository.save(
                    GameInstanceEntity(
                        projectId = projectId,
                        name = "run-instance-${instanceSeq++}",
                        platform = "UNITY",
                        sdkUuid = UUID.randomUUID().toString(),
                        createdAt = now,
                        updatedAt = now
                    )
                )!!.id!!,
                startedBy = ownerId,
                status = "COMPLETED",
                label = "content-map-2x2-반복",
                startedAt = now.minus(Duration.ofDays(1)),
                completedAt = now
            )
        )!!.id!!
        seedRun(model = "sonnet-5", status = "COMPLETED", qaRunId = pilotMiddle)
        seedRun(model = "gpt-4o", status = "COMPLETED", qaRunId = pilotDetail)
        seedRun(model = "haiku", status = "COMPLETED", qaRunId = repeatMiddle)

        val both = statsService.stats(
            projectId, ownerId, windowStart, windowEnd, 200,
            testRunId = middle, label = "content-map-2x2-파일럿"
        )

        assertThat(both.total.runs).isEqualTo(1)
        assertThat(both.cells.map { it.model }).containsExactly("sonnet-5")
        // 축 하나씩 걸면 각각 둘이다. 교집합이 하나인 것이 두 술어가 AND 로 걸렸다는 증거다.
        assertThat(statsWithLabel("content-map-2x2-파일럿").total.runs).isEqualTo(2)
        assertThat(
            statsService.stats(projectId, ownerId, windowStart, windowEnd, 200, testRunId = middle).total.runs
        ).isEqualTo(2)
        assertThat(both.testRunId).isEqualTo(middle.toString())
        assertThat(both.label).isEqualTo("content-map-2x2-파일럿")
    }

    /** 없는 이름도 예외가 아니라 빈 집계다. 존재 여부를 응답 모양으로 알려주지 않는다. */
    @Test
    fun `없는 label 은 예외가 아니라 빈 집계다`(): Unit = runBlocking {
        val (_, qaRunId) = seedQaRun("L2 중간", label = "content-map-2x2-파일럿")
        seedRun(model = "sonnet-5", status = "COMPLETED", qaRunId = qaRunId)

        val stats = statsWithLabel("없는 실험")

        assertThat(stats.cells).isEmpty()
        assertThat(stats.total.runs).isZero()
    }

    /** 응답이 물어본 실험 묶음을 되돌려 준다. `projectId` · `testRunId` 와 같은 이유다. */
    @Test
    fun `응답이 물어본 label 을 되돌려 준다`(): Unit = runBlocking {
        val (_, qaRunId) = seedQaRun("L2 중간", label = "content-map-2x2-파일럿")
        seedRun(model = "sonnet-5", status = "COMPLETED", qaRunId = qaRunId)

        assertThat(statsWithLabel("content-map-2x2-파일럿").label).isEqualTo("content-map-2x2-파일럿")
        assertThat(stats().label).isNull()
    }

    /**
     * **목록은 이미 쓰인 이름만, 최근에 쓴 것부터 준다.** 화면이 자유 입력이 아니라 고르는 자리여야
     * `content map 1차` 와 `content map 1차 실험` 이 두 칸으로 갈리지 않는다.
     */
    @Test
    fun `label 목록은 이미 쓰인 것만 최근 순으로 준다`(): Unit = runBlocking {
        val (_, older) = seedQaRun(
            "L1 상세", label = "content-map-2x2-파일럿", startedAt = now.minus(Duration.ofDays(3))
        )
        val (_, newer) = seedQaRun(
            "L2 중간", label = "content-map-2x2-반복", startedAt = now.minus(Duration.ofDays(1))
        )
        val (_, unlabelled) = seedQaRun("L3 추상")
        seedRun(model = "sonnet-5", status = "COMPLETED", qaRunId = older)
        seedRun(model = "gpt-4o", status = "COMPLETED", qaRunId = newer)
        seedRun(model = "haiku", status = "COMPLETED", qaRunId = unlabelled)
        seedRun(model = "standalone", status = "COMPLETED")

        val labels = statsService.labels(projectId, ownerId)

        assertThat(labels.projectId).isEqualTo(projectId.toString())
        assertThat(labels.labels).containsExactly("content-map-2x2-반복", "content-map-2x2-파일럿")
    }

    /**
     * **남의 프로젝트 `label` 이 목록에 새면 안 된다.** 실험 이름은 그 자체로 무엇을 재고 있는지를
     * 말하므로, 집계 숫자를 못 보게 하고 이름만 흘리는 것은 가림이 아니다.
     *
     * 프로젝트를 안 주고 부를 때도 같다 — 그때 목록은 참여 중인 프로젝트의 것뿐이다.
     */
    @Test
    fun `남의 프로젝트 label 은 목록에 안 나온다`(): Unit = runBlocking {
        val (_, mine) = seedQaRun("L2 중간", label = "content-map-2x2-파일럿")
        seedRun(model = "sonnet-5", status = "COMPLETED", qaRunId = mine)
        seedForeignLabelledRun("남의-실험")

        assertThat(statsService.labels(projectId, ownerId).labels)
            .containsExactly("content-map-2x2-파일럿")
        assertThat(statsService.labels(null, ownerId).labels)
            .containsExactly("content-map-2x2-파일럿")
    }

    /**
     * 남의 프로젝트 실험 이름을 그대로 집어넣어도 빈 집계다. `label` 술어는 멤버십 판정을 **대신하지
     * 않고 더해진다** — 여기가 빠지면 이름을 아는 사람이 남의 실험 성적을 그대로 읽는다.
     */
    @Test
    fun `남의 프로젝트 label 을 넣어도 빈 집계다`(): Unit = runBlocking {
        seedForeignLabelledRun("남의-실험")

        val stats = statsService.stats(null, ownerId, windowStart, windowEnd, 200, label = "남의-실험")

        assertThat(stats.cells).isEmpty()
        assertThat(stats.total.runs).isZero()
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun stats() = statsService.stats(projectId, ownerId, windowStart, windowEnd, 200)

    private suspend fun statsWithLabel(label: String) =
        statsService.stats(projectId, ownerId, windowStart, windowEnd, 200, label = label)

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
        durationMs: Long = 30_000,
        // 판정 승격(ARTEL-299). null이면 요약 없이 끝난 런이라 컬럼이 전부 NULL이다.
        stepsTotal: Int? = null,
        stepsPassed: Int? = null,
        casesTotal: Int? = null,
        casesPassed: Int? = null,
        // null 이면 단독 실행 런이다(하위호환). `qa_run_id` 가 nullable 인 것이 그 뜻이고,
        // testRunId 필터가 없을 때 이 런들이 사라지지 않는지가 아래 테스트의 핵심이다.
        qaRunId: Long? = null
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
                qaRunId = qaRunId,
                startedBy = ownerId,
                status = status,
                model = model,
                reasoningEffort = effort,
                promptVersion = promptVersion,
                agentArch = arch,
                stepsTotal = stepsTotal,
                stepsPassed = stepsPassed,
                casesTotal = casesTotal,
                casesPassed = casesPassed,
                startedAt = startedAt,
                completedAt = if (terminal) startedAt.plusMillis(durationMs) else null
            )
        )!!.id!!
    }

    /** `grader='expected-steps'` 채점 한 줄. detail 모양은 ExpectedStepsGrader가 쓰는 것과 같다. */
    private suspend fun seedScore(
        qaTryId: Long,
        correctPass: Int,
        falseAlarm: Int,
        miss: Int,
        correctFail: Int,
        unreported: Int,
        version: String = "1"
    ) {
        scoreRepository.insertIfAbsent(
            qaTryId = qaTryId,
            grader = "expected-steps",
            graderVersion = version,
            detail = """
                {"unreported":$unreported,
                 "matrix":{"correct_pass":$correctPass,"false_alarm":$falseAlarm,
                           "miss":$miss,"correct_fail":$correctFail}}
            """.trimIndent()
        )
    }

    /**
     * test run 하나와 그 밑의 qa_run 하나. 돌려주는 것은 `qa_try.qa_run_id` 에 넣을 값이다.
     *
     * @param label 이 run 이 속한 실험 묶음. null 이면 어느 실험에도 안 묶인 run 이다.
     * @param startedAt `label` 목록의 정렬 기준이라 테스트가 직접 정할 수 있어야 한다.
     */
    private suspend fun seedQaRun(
        testRunName: String,
        projectId: Long = this.projectId,
        label: String? = null,
        startedAt: Instant = now.minus(Duration.ofDays(1))
    ): Pair<Long, Long> {
        val testRunId = testRunRepository.save(
            TestRunEntity(projectId = projectId, name = testRunName)
        )!!.id!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId,
                name = "run-instance-${instanceSeq++}",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        val qaRunId = qaRunRepository.save(
            QaRunEntity(
                testRunId = testRunId,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                // `ck_qa_run_completed_at` 이 종단 상태에 completed_at 을 요구한다.
                status = "COMPLETED",
                label = label,
                startedAt = startedAt,
                completedAt = now
            )
        )!!.id!!
        return testRunId to qaRunId
    }

    /**
     * 아무도 참여하지 않은 다른 프로젝트의 실험 하나 — `test_run` · `qa_run`(`label` 있음) ·
     * `qa_try` 가 전부 그 프로젝트에 있다.
     *
     * `label` 가시성은 집계와 같은 길로 판정된다(`qa_try` → `test_scenario` → `project_id`). 그
     * 길을 실제로 태우려면 남의 `qa_run` 밑에 남의 시나리오를 가리키는 `qa_try` 가 있어야 한다.
     */
    private suspend fun seedForeignLabelledRun(label: String) {
        val project = projectRepository.save(
            ProjectEntity(
                name = "foreign-label-project-${instanceSeq}",
                genre = "ACTION",
                createdAt = now,
                updatedAt = now
            )
        )!!
        val scenario = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "foreign-label-instance-${instanceSeq++}",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        val testRunId = testRunRepository.save(
            TestRunEntity(projectId = project.id!!, name = "남의 런")
        )!!.id!!
        val startedAt = now.minus(Duration.ofDays(1))
        val qaRunId = qaRunRepository.save(
            QaRunEntity(
                testRunId = testRunId,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = "COMPLETED",
                label = label,
                startedAt = startedAt,
                completedAt = now
            )
        )!!.id!!
        qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                qaRunId = qaRunId,
                startedBy = ownerId,
                status = "COMPLETED",
                model = "sonnet-5",
                startedAt = startedAt,
                completedAt = startedAt.plusMillis(30_000)
            )
        )
    }

    /**
     * 아무도 참여하지 않은 다른 프로젝트의 런 하나. 합산이 프로젝트 경계를 어떻게 넘는지 보려면
     * 남의 것이 하나 있어야 한다. `startedBy` 가 오너인 것은 그 컬럼이 NOT NULL 이라서일 뿐,
     * 가시성은 `project_member` 만 정한다.
     */
    private suspend fun seedForeignRun(model: String, deletedAt: Instant? = null): Long {
        val project = projectRepository.save(
            ProjectEntity(
                name = "foreign-project-${instanceSeq}",
                genre = "ACTION",
                createdAt = now,
                updatedAt = now,
                deletedAt = deletedAt
            )
        )!!
        val scenario = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "foreign-instance-${instanceSeq++}",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        val startedAt = now.minus(Duration.ofDays(1))
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
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

    /** `app_user.platform_role`을 올린다. 주는 화면도 API도 없어 테스트도 행을 직접 고친다. */
    private suspend fun promote(userId: Long) {
        val user = appUserRepository.findById(userId)!!
        appUserRepository.save(
            user.copy(platformRole = PlatformRole.DEVELOPER.name, updatedAt = Instant.now())
        )
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
