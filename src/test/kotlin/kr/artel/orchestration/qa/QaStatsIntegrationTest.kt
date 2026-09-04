package kr.artel.orchestration.qa

import io.r2dbc.postgresql.codec.Json
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
import kr.artel.orchestration.qa.entity.QaLogEntity
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

    // ----------------------------------------------------------------- helpers

    // --- 에이전트가 무엇을 했나 (ARTEL-681) ---------------------------------------

    @Test
    fun `한 번도 안 부른 도구가 0으로 나온다`(): Unit = runBlocking {
        // 이 집계 전체가 이 한 줄을 위해 있다. `qa_log` 만 GROUP BY 하면 0 인 도구는 행이
        // 아예 안 생겨 목록에서 사라지고, 읽는 쪽은 "그런 도구가 없다" 와 "있는데 안 썼다" 를
        // 가릴 수 없다. 실제로 `record_knowledge` 가 모든 런에서 0 이었고, 그것을 사람이
        // `docker logs | grep` 으로 세서 알아냈다.
        val run = seedRun(status = "COMPLETED", tools = listOf("report_step", "record_knowledge"))
        seedToolCall(run, "report_step")

        val tools = toolStats().tools

        assertThat(tools.map { it.tool }).containsExactlyInAnyOrder("report_step", "record_knowledge")
        assertThat(tools.first { it.tool == "record_knowledge" }.calls).isZero()
        assertThat(tools.first { it.tool == "report_step" }.calls).isEqualTo(1)
    }

    @Test
    fun `안 쓰인 도구가 목록 맨 위에 온다`(): Unit = runBlocking {
        // 찾는 것이 안 쓰인 도구이므로 눈이 먼저 닿는 자리에 둔다.
        val run = seedRun(status = "COMPLETED", tools = listOf("click_button", "record_knowledge"))
        repeat(3) { seedToolCall(run, "click_button") }

        assertThat(toolStats().tools.first().tool).isEqualTo("record_knowledge")
    }

    @Test
    fun `쥔 런과 부른 런을 따로 센다`(): Unit = runBlocking {
        // 도구가 새로 생기거나 빠지면 런마다 쥔 목록이 다르다. 둘의 차이가 "줬는데 안 쓴" 런이다.
        val used = seedRun(status = "COMPLETED", tools = listOf("record_knowledge"))
        seedRun(status = "COMPLETED", tools = listOf("record_knowledge"))
        seedToolCall(used, "record_knowledge")

        val row = toolStats().tools.first { it.tool == "record_knowledge" }

        assertThat(row.runsHeld).isEqualTo(2)
        assertThat(row.runsCalled).isEqualTo(1)
    }

    @Test
    fun `쥐지 않은 도구는 세지 않는다`(): Unit = runBlocking {
        // 창 밖의 런이나 다른 프로젝트의 호출이 새어 들어오면 안 된다. 시작점이 `run_config`
        // 이므로 쥐지 않은 것은 호출이 있어도 목록에 없다.
        val run = seedRun(status = "COMPLETED", tools = listOf("report_step"))
        seedToolCall(run, "record_knowledge")

        assertThat(toolStats().tools.map { it.tool }).containsExactly("report_step")
    }

    @Test
    fun `스텝 판정이 근거를 댔는지 센다`(): Unit = runBlocking {
        // 부르기는 매번 부르고 그 칸만 비어 있었다 — 호출 수로는 안 보인다.
        val run = seedRun(status = "COMPLETED", tools = listOf("report_step"))
        seedToolCall(run, "report_step", args = """{"used_knowledge_ids":[41]}""")
        seedToolCall(run, "report_step", args = """{"used_knowledge_ids":[]}""")
        seedToolCall(run, "report_step")

        val citations = toolStats().citations

        assertThat(citations.verdicts).isEqualTo(3)
        assertThat(citations.withCitation).isEqualTo(1)
    }

    @Test
    fun `창에 런이 없으면 빈 집계다`(): Unit = runBlocking {
        val empty = toolStats()

        assertThat(empty.tools).isEmpty()
        assertThat(empty.citations.verdicts).isZero()
        assertThat(empty.issues).isEmpty()
    }

    @Test
    fun `참여자가 아니면 빈 집계다`(): Unit = runBlocking {
        // 403 을 주면 프로젝트의 존재 여부가 새어 나간다. 실행 설정 집계와 같은 동작이다.
        val stranger = signIn("stranger", "77")
        val run = seedRun(status = "COMPLETED", tools = listOf("report_step"))
        seedToolCall(run, "report_step")

        val seen = statsService.toolStats(projectId, stranger.userId.toLong(), windowStart, windowEnd)

        assertThat(seen.tools).isEmpty()
    }

    private suspend fun toolStats() =
        statsService.toolStats(projectId, ownerId, windowStart, windowEnd)

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
        durationMs: Long = 30_000,
        // 판정 승격(ARTEL-299). null이면 요약 없이 끝난 런이라 컬럼이 전부 NULL이다.
        stepsTotal: Int? = null,
        stepsPassed: Int? = null,
        casesTotal: Int? = null,
        casesPassed: Int? = null,
        // 그 런이 쥐고 있던 도구. Agent 가 세션을 열 때 `run_config.tools` 로 싣는 것과 같은
        // 자리다 — 0 회를 말하려면 무엇을 쥐고 있었는지가 있어야 한다(ARTEL-681).
        tools: List<String> = emptyList()
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
                stepsTotal = stepsTotal,
                stepsPassed = stepsPassed,
                casesTotal = casesTotal,
                casesPassed = casesPassed,
                startedAt = startedAt,
                completedAt = if (terminal) startedAt.plusMillis(durationMs) else null,
                runConfig = Json.of(
                    """{"tools":[${tools.joinToString(",") { "\"$it\"" }}]}"""
                )
            )
        )!!.id!!
    }

    /** `TOOL` 프레임 한 줄. 모양은 Agent 의 `channel.tool_call` 이 내는 것과 같다. */
    private suspend fun seedToolCall(
        qaTryId: Long,
        tool: String,
        args: String = "{}"
    ) {
        qaLogRepository.save(
            QaLogEntity(
                qaTryId = qaTryId,
                direction = "AGENT_TO_ORCHE",
                type = "TOOL",
                message = tool,
                payload = Json.of("""{"tool":"$tool","args":$args}""")
            )
        )
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
