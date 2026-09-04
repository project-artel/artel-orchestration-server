package kr.artel.orchestration.qa

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityObservationEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.repository.CapabilityObservationRepository
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.entity.IssueSeverity
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.llmusage.entity.LlmUsageEntity
import kr.artel.orchestration.llmusage.repository.LlmUsageRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaTryDetailService
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
 * QA 히스토리에서 런 하나를 펼쳤을 때 내려가는 값들(ARTEL-819).
 *
 * 실제 PostgreSQL을 쓴다 — 이 기능은 전부 SQL 안에 있다. LATERAL 집계, `FILTER`,
 * `payload ->> 'tool'` 중 어느 하나라도 인메모리 대역으로 바꾸면 검증할 것이 남지 않는다.
 *
 * 가장 중요한 케이스는 [한 런의 호출이 여럿이어도 이슈와 피드백 수가 안 부풀려진다]이다.
 * `llm_usage`를 `qa_try`에 그냥 조인하면 옆에 붙은 스칼라 서브쿼리는 멀쩡한데 호출 수만큼
 * 행이 복제돼 수가 배로 뛴다 — 숫자가 그럴듯해 화면만 봐서는 안 잡힌다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaTryDetailIntegrationTest {

    @Autowired private lateinit var detailService: QaTryDetailService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var llmUsageRepository: LlmUsageRepository
    @Autowired private lateinit var issueRepository: IssueRepository
    @Autowired private lateinit var observationRepository: CapabilityObservationRepository
    @Autowired private lateinit var capabilityRepository: CapabilityRepository
    @Autowired private lateinit var sceneRepository: SceneRepository
    @Autowired private lateinit var contentMapRepository: ContentMapRepository
    @Autowired private lateinit var gameBuildRepository: GameBuildRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    private val now: Instant = Instant.parse("2026-09-04T00:00:00Z")

    private var projectId: Long = 0
    private var scenarioId: Long = 0
    private var ownerId: Long = 0
    private var strangerId: Long = 0
    private var gameInstanceId: Long = 0
    private var instanceSeq: Int = 0

    @BeforeEach
    fun seed(): Unit = runBlocking {
        wipe()
        ownerId = signIn("detail-owner", "8190").userId.toLong()
        strangerId = signIn("detail-stranger", "8191").userId.toLong()
        projectId = projectRepository.save(
            ProjectEntity(name = "detail-project", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = projectId,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        scenarioId = testScenarioRepository.save(
            TestScenarioEntity(projectId = projectId, title = "게임 시작 -> 1 스테이지 완료까지")
        )!!.id!!
        gameInstanceId = newGameInstance()
    }

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        observationRepository.deleteAll()
        capabilityRepository.deleteAll()
        sceneRepository.deleteAll()
        contentMapRepository.deleteAll()
        issueRepository.deleteAll()
        llmUsageRepository.deleteAll()
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        testRunRepository.deleteAll()
        gameBuildRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `펼친 자리에 시나리오와 실행 설정이 함께 선다`(): Unit = runBlocking {
        val qaTryId = seedRun(status = "COMPLETED", stepsPassed = 17, stepsTotal = 17)

        val detail = detailService.detail(qaTryId, ownerId)

        assertThat(detail.qaTryId).isEqualTo(qaTryId.toString())
        assertThat(detail.scenarioTitle).isEqualTo("게임 시작 -> 1 스테이지 완료까지")
        assertThat(detail.model).isEqualTo("bedrock/claude-haiku-4-5")
        assertThat(detail.promptVersion).isEqualTo("v16")
        assertThat(detail.stepsPassed).isEqualTo(17)
        assertThat(detail.stepsTotal).isEqualTo(17)
    }

    @Test
    fun `취소된 런은 step 이 0 이 아니라 없는 채로 내려간다`(): Unit = runBlocking {
        // 실측: 취소된 런의 steps_passed/steps_total 은 둘 다 NULL 이다. 여기서 0으로 채우면
        // 화면이 "판정 없음"과 "0 통과"를 갈라 볼 근거를 잃고 "0 / 17 실패"처럼 읽힌다.
        val qaTryId = seedRun(status = "CANCELLED", stepsPassed = null, stepsTotal = null)
        seedUsage(qaTryId, inputTokens = 2_010_834, outputTokens = 3_176, costUsd = "0.060586")

        val detail = detailService.detail(qaTryId, ownerId)

        assertThat(detail.status).isEqualTo("CANCELLED")
        assertThat(detail.stepsPassed).isNull()
        assertThat(detail.stepsTotal).isNull()
        // 비용은 실제로 나간 돈이라 취소와 무관하게 남는다.
        assertThat(detail.usage.costUsd).isEqualByComparingTo("0.060586")
    }

    @Test
    fun `한 런의 호출이 여럿이어도 이슈와 피드백 수가 안 부풀려진다`(): Unit = runBlocking {
        val qaTryId = seedRun(status = "COMPLETED")
        repeat(3) { seedUsage(qaTryId, inputTokens = 100, outputTokens = 10, costUsd = "0.001000") }
        seedIssue(qaTryId)
        seedIssue(qaTryId)
        seedFeedback(qaTryId)

        val detail = detailService.detail(qaTryId, ownerId)

        // `llm_usage` 를 그냥 조인했다면 이 셋이 3배로 나온다.
        assertThat(detail.usage.calls).isEqualTo(3)
        assertThat(detail.issues).isEqualTo(2)
        assertThat(detail.feedback).isEqualTo(1)
    }

    @Test
    fun `피드백은 qa_run 이 아니라 이 try 가 남긴 것만 센다`(): Unit = runBlocking {
        val qaRunId = newQaRun()
        val mine = seedRun(status = "COMPLETED", qaRunId = qaRunId)
        val sibling = seedRun(status = "COMPLETED", qaRunId = qaRunId)
        seedFeedback(mine, qaRunId = qaRunId)
        seedFeedback(sibling, qaRunId = qaRunId)
        // 같은 `qa_run` 이지만 어느 try 가 남겼는지 모르는 행(V71 이전). 어느 쪽에도 안 붙는다.
        seedFeedback(qaTryId = null, qaRunId = qaRunId)

        assertThat(detailService.detail(mine, ownerId).feedback).isEqualTo(1)
        assertThat(detailService.detail(sibling, ownerId).feedback).isEqualTo(1)
    }

    @Test
    fun `도구는 이름별로 세고 많이 부른 것이 앞에 선다`(): Unit = runBlocking {
        val qaTryId = seedRun(status = "COMPLETED")
        repeat(19) { seedToolCall(qaTryId, "observe_scene") }
        repeat(17) { seedToolCall(qaTryId, "report_step") }
        repeat(1) { seedToolCall(qaTryId, "finish_run") }
        repeat(1) { seedToolCall(qaTryId, "click_at") }
        // 도구가 아닌 프레임은 안 센다. 한 런의 `qa_log` 는 대부분 PULSE 다.
        repeat(30) { seedLog(qaTryId, type = "PULSE") }
        seedLog(qaTryId, type = "TOOL_RESULT")

        val tools = detailService.detail(qaTryId, ownerId).toolCalls

        assertThat(tools.map { it.tool }).containsExactly(
            "observe_scene",
            "report_step",
            // 동점은 이름으로 갈린다. 안 그러면 같은 런을 두 번 열었을 때 순서가 달라 보인다.
            "click_at",
            "finish_run"
        )
        assertThat(tools.first().calls).isEqualTo(19)
        assertThat(tools.sumOf { it.calls }).isEqualTo(38)
    }

    @Test
    fun `단가를 안 주는 provider 의 비용은 0 이 아니라 모른다로 남는다`(): Unit = runBlocking {
        val qaTryId = seedRun(status = "COMPLETED")
        seedUsage(qaTryId, inputTokens = 100, outputTokens = 10, costUsd = null)
        seedUsage(qaTryId, inputTokens = 200, outputTokens = 20, costUsd = null)

        val usage = detailService.detail(qaTryId, ownerId).usage

        // 0으로 뭉개면 "아무도 단가를 말한 적 없다"가 "공짜였다"로 읽힌다.
        assertThat(usage.costUsd).isNull()
        assertThat(usage.calls).isEqualTo(2)
        assertThat(usage.pricedCalls).isZero()
    }

    @Test
    fun `단가를 아는 호출과 모르는 호출이 섞이면 금액이 하한이라는 것이 함께 간다`(): Unit = runBlocking {
        val qaTryId = seedRun(status = "COMPLETED")
        seedUsage(qaTryId, inputTokens = 100, outputTokens = 10, costUsd = "0.001000")
        seedUsage(qaTryId, inputTokens = 200, outputTokens = 20, costUsd = null)

        val usage = detailService.detail(qaTryId, ownerId).usage

        assertThat(usage.costUsd).isEqualByComparingTo("0.001000")
        // 셋 중 하나만 값이 있다는 것을 화면이 알아야 그 금액을 하한으로 그린다.
        assertThat(usage.calls).isEqualTo(2)
        assertThat(usage.pricedCalls).isEqualTo(1)
    }

    @Test
    fun `추정으로 매긴 호출이 하나라도 있으면 합계가 추정으로 표시된다`(): Unit = runBlocking {
        val qaTryId = seedRun(status = "COMPLETED")
        seedUsage(qaTryId, inputTokens = 100, outputTokens = 10, costUsd = "0.001000", estimated = false)
        seedUsage(qaTryId, inputTokens = 200, outputTokens = 20, costUsd = "0.002000", estimated = true)

        // 섞였다는 사실이 그 합계에 대한 판단을 바꾼다 — 청구액과 같은 얼굴을 하면 안 된다.
        assertThat(detailService.detail(qaTryId, ownerId).usage.costEstimated).isTrue()
    }

    @Test
    fun `캐시 읽기와 캐시 쓰기가 갈려서 내려간다`(): Unit = runBlocking {
        val qaTryId = seedRun(status = "COMPLETED")
        seedUsage(
            qaTryId,
            inputTokens = 2_010_834,
            outputTokens = 3_176,
            cachedInputTokens = 1_938_811,
            cacheWriteTokens = 71_885,
            costUsd = "0.060586"
        )

        val usage = detailService.detail(qaTryId, ownerId).usage

        // 캐시 읽기는 input 에 포함된 값이고 캐시 쓰기는 아니다. 셋을 더하면 두 번 센 수가 된다.
        assertThat(usage.inputTokens).isEqualTo(2_010_834)
        assertThat(usage.cachedInputTokens).isEqualTo(1_938_811)
        assertThat(usage.cacheWriteTokens).isEqualTo(71_885)
    }

    @Test
    fun `호출 기록이 하나도 없는 옛 런도 안 깨진다`(): Unit = runBlocking {
        // `llm_usage` 가 생기기 전 런이 실제로 있다. LEFT JOIN LATERAL 이 빈 줄을 주므로
        // 합계는 0이 되고 비용만 "모른다"로 남는다.
        val qaTryId = seedRun(status = "COMPLETED")

        val detail = detailService.detail(qaTryId, ownerId)

        assertThat(detail.usage.calls).isZero()
        assertThat(detail.usage.inputTokens).isZero()
        assertThat(detail.usage.costUsd).isNull()
        assertThat(detail.usage.costEstimated).isFalse()
        assertThat(detail.toolCalls).isEmpty()
        assertThat(detail.issues).isZero()
        assertThat(detail.feedback).isZero()
    }

    @Test
    fun `다른 프로젝트의 런은 404 다`(): Unit = runBlocking {
        val qaTryId = seedRun(status = "COMPLETED")

        // 빈 응답으로 위장하지 않는다 — 남의 프로젝트에 런이 있다는 것도 알려주지 않는다.
        assertThatThrownBy { runBlocking { detailService.detail(qaTryId, strangerId) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `없는 런은 404 다`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { detailService.detail(999_999, ownerId) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `도는 중인 런도 그 시점까지의 합을 준다`(): Unit = runBlocking {
        // 화면은 안 부르기로 했지만(중간값이라), 그 판단은 화면의 것이고 서버가 막지 않는다.
        val qaTryId = seedRun(status = "RUNNING", stepsPassed = null, stepsTotal = null)
        seedUsage(qaTryId, inputTokens = 100, outputTokens = 10, costUsd = "0.001000")

        val detail = detailService.detail(qaTryId, ownerId)

        assertThat(detail.status).isEqualTo("RUNNING")
        assertThat(detail.completedAt).isNull()
        assertThat(detail.usage.calls).isEqualTo(1)
    }

    // ------------------------------------------------------------------ seeds

    private suspend fun seedRun(
        status: String,
        stepsPassed: Int? = null,
        stepsTotal: Int? = null,
        qaRunId: Long? = null
    ): Long {
        val terminal = status in setOf("COMPLETED", "FAILED", "CANCELLED")
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenarioId,
                gameInstanceId = newGameInstance(),
                qaRunId = qaRunId,
                startedBy = ownerId,
                status = status,
                model = "bedrock/claude-haiku-4-5",
                promptVersion = "v16",
                stepsPassed = stepsPassed,
                stepsTotal = stepsTotal,
                startedAt = now,
                completedAt = if (terminal) now.plus(Duration.ofMinutes(5)) else null
            )
        )!!.id!!
    }

    private suspend fun seedUsage(
        qaTryId: Long,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: String?,
        cachedInputTokens: Int = 0,
        cacheWriteTokens: Int = 0,
        estimated: Boolean = false
    ) {
        val amount = costUsd?.let(::BigDecimal)
        llmUsageRepository.save(
            LlmUsageEntity(
                service = "QA_RUN",
                referenceId = qaTryId,
                provider = "bedrock",
                model = "claude-haiku-4-5",
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedInputTokens = cachedInputTokens,
                cacheWriteTokens = cacheWriteTokens,
                costUsd = amount,
                // `ck_llm_usage_cost_origin`: 금액이 없으면 출처도 없어야 한다.
                costEstimated = if (amount == null) null else estimated,
                latencyMs = 1_200,
                calledAt = now,
                createdAt = now
            )
        )
    }

    private suspend fun seedToolCall(qaTryId: Long, tool: String) {
        qaLogRepository.save(
            QaLogEntity(
                qaTryId = qaTryId,
                direction = "AGENT_TO_ORCHE",
                type = "TOOL",
                message = tool,
                payload = Json.of("""{"tool":"$tool","tool_call_id":"${UUID.randomUUID()}"}"""),
                createdAt = now
            )
        )
    }

    private suspend fun seedLog(qaTryId: Long, type: String) {
        qaLogRepository.save(
            QaLogEntity(
                qaTryId = qaTryId,
                direction = "AGENT_TO_ORCHE",
                type = type,
                message = "not a tool call",
                createdAt = now
            )
        )
    }

    private suspend fun seedIssue(qaTryId: Long) {
        issueRepository.save(
            IssueEntity(
                qaTryId = qaTryId,
                severity = IssueSeverity.MAJOR.name,
                title = "스테이지 클리어 후 점수가 안 오른다",
                reportedAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    /**
     * `capability_observation` 한 행. FK가 요구하는 `capability` 사슬(빌드 → 지도 → scene)을
     * 함께 세운다 — 세는 것이 목적이라 내용은 최소로 둔다.
     */
    private suspend fun seedFeedback(qaTryId: Long?, qaRunId: Long? = null) {
        // `qa_run_id` 는 NOT NULL 이라 안 주면 이 행만을 위한 run 을 하나 세운다. try 하나짜리
        // 케이스는 run 을 신경 쓸 이유가 없다.
        val runId = qaRunId ?: newQaRun()
        val buildId = gameBuildRepository.save(
            GameBuildEntity(
                projectId = projectId,
                version = "build-${instanceSeq++}",
                createdAt = now,
                updatedAt = now
            )
        )!!.id!!
        val contentMapId = contentMapRepository.save(
            ContentMapEntity(gameBuildId = buildId, createdAt = now, updatedAt = now)
        )!!.id!!
        val sceneId = sceneRepository.save(
            SceneEntity(contentMapId = contentMapId, name = "TitleScene")
        )!!.id!!
        val capabilityId = capabilityRepository.save(
            CapabilityEntity(
                sceneId = sceneId,
                contentMapId = contentMapId,
                origin = "observed",
                summary = "MapSceneButton 을 누르면 StoryScene 으로 간다",
                interaction = "click"
            )
        )!!.id!!
        observationRepository.save(
            CapabilityObservationEntity(
                capabilityId = capabilityId,
                qaRunId = runId,
                qaTryId = qaTryId,
                source = "agent",
                // `ck_capability_observation_shape`: agent 가 남긴 행은 판정과 그 사유를 함께
                // 요구한다. 무엇을 보고 그렇게 말했는지 없는 판정은 지도에 못 선다.
                verdict = "works",
                rationale = "버튼을 눌렀더니 StoryScene 으로 넘어갔다",
                actedAt = now
            )
        )
    }

    private suspend fun newQaRun(): Long {
        val testRunId = testRunRepository.save(
            TestRunEntity(
                projectId = projectId,
                name = "detail-run-${instanceSeq++}",
                createdAt = now,
                updatedAt = now
            )
        )!!.id!!
        return qaRunRepository.save(
            QaRunEntity(
                testRunId = testRunId,
                gameInstanceId = gameInstanceId,
                startedBy = ownerId,
                status = "COMPLETED",
                startedAt = now,
                // `ck_qa_run_completed_at`: 끝난 상태는 끝난 시각을 요구한다(`qa_try`와 같은 불변식).
                completedAt = now.plus(Duration.ofMinutes(5)),
                createdAt = now,
                updatedAt = now
            )
        )!!.id!!
    }

    private suspend fun newGameInstance(): Long =
        gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId,
                name = "instance-${instanceSeq++}",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!.id!!

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
